package ai.tushar.nanochat.handset

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** A tool as Handset lists it. */
data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

/** What one tool call returned. [isError] is MCP's flag: the tool ran but failed or was refused. */
data class McpCallResult(val text: String, val isError: Boolean)

class McpException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** Handset no longer knows this app's token: it was replaced or Handset removed the agent. */
        Unauthorized,
        /** Nothing answered: Handset's server is off or Handset isn't running. */
        Unreachable,
        /** Handset answered with something this client can't use. */
        Protocol,
    }
}

/**
 * A small MCP client for Handset's Streamable HTTP server on this phone, JSON-RPC by hand. It speaks the 2025-06-18
 * flow: `initialize` once, then `tools/list` and `tools/call`, with the token as a bearer header. It keeps a session id
 * if the server gives one (Handset doesn't) and reads a reply sent as JSON or as an event stream.
 */
class McpClient(
    private val url: String,
    private val token: String,
    private val clientVersion: String,
    private val connectTimeoutMs: Int = 3_000,
    /** Opening an app waits for it to settle, so a call can take a while. */
    private val readTimeoutMs: Int = 45_000,
) {
    private val ids = AtomicLong()
    private val lock = Mutex()
    private var ready = false
    private var sessionId: String? = null

    suspend fun listTools(): List<McpTool> {
        val result = request("tools/list", JsonObject(emptyMap()))
        val tools = result["tools"] as? JsonArray ?: throw McpException(McpException.Kind.Protocol, "tools/list returned no tools")
        return tools.mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val name = o.string("name") ?: return@mapNotNull null
            McpTool(name, o.string("description").orEmpty(), o["inputSchema"] as? JsonObject ?: JsonObject(emptyMap()))
        }
    }

    suspend fun call(name: String, arguments: JsonObject): McpCallResult {
        val result = request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        val content = result["content"] as? JsonArray ?: JsonArray(emptyList())
        val text = content.mapNotNull { block ->
            val o = block as? JsonObject ?: return@mapNotNull null
            when (o.string("type")) {
                "text" -> o.string("text")
                "image" -> "[image]"
                else -> null
            }
        }.joinToString("\n")
        return McpCallResult(text, (result["isError"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!ready) initialize()
            try {
                send(method, params)
            } catch (e: SessionGone) {
                // The server forgot the session: start a new one and ask again, once.
                initialize()
                send(method, params)
            }
        }
    }

    private fun initialize() {
        sessionId = null
        val result = send(
            "initialize",
            buildJsonObject {
                put("protocolVersion", ProtocolVersion)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "nano-chat"); put("version", clientVersion) }
            },
            versionHeader = false,
        )
        if (result.string("protocolVersion") == null) throw McpException(McpException.Kind.Protocol, "initialize returned no protocol version")
        notify("notifications/initialized")
        ready = true
    }

    private class SessionGone : IOException()

    private fun send(method: String, params: JsonObject, versionHeader: Boolean = true): JsonObject {
        val id = ids.incrementAndGet()
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        val (status, contentType, text) = post(body.toString(), versionHeader)
        if (status == 404 && sessionId != null) throw SessionGone()
        val message = parseReply(contentType, text, id)
            ?: throw McpException(McpException.Kind.Protocol, "Handset answered $method with HTTP $status")
        (message["error"] as? JsonObject)?.let { e ->
            throw McpException(McpException.Kind.Protocol, e.string("message") ?: "Handset refused $method")
        }
        return message["result"] as? JsonObject ?: throw McpException(McpException.Kind.Protocol, "$method returned no result")
    }

    private fun notify(method: String) {
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }.toString(), versionHeader = true)
    }

    private data class Reply(val status: Int, val contentType: String, val body: String)

    private fun post(body: String, versionHeader: Boolean): Reply {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("Authorization", "Bearer $token")
                if (versionHeader) setRequestProperty("MCP-Protocol-Version", ProtocolVersion)
                sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            }
        } catch (e: IOException) {
            throw McpException(McpException.Kind.Unreachable, e.message ?: "Couldn't open $url")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            if (status == 401 || status == 403) throw McpException(McpException.Kind.Unauthorized, "Handset refused the token (HTTP $status)")
            connection.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Reply(status, connection.contentType.orEmpty(), text)
        } catch (e: McpException) {
            throw e
        } catch (e: SessionGone) {
            throw e
        } catch (e: IOException) {
            throw McpException(McpException.Kind.Unreachable, e.message ?: "Handset didn't answer")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val ProtocolVersion = "2025-06-18"
        private val json = Json { ignoreUnknownKeys = true }

        /** The JSON-RPC message answering [id], from a JSON body or the `data:` lines of an event stream. */
        internal fun parseReply(contentType: String, body: String, id: Long): JsonObject? {
            val candidates = if (contentType.startsWith("text/event-stream")) {
                body.split(Regex("\\r?\\n\\r?\\n")).mapNotNull { event ->
                    event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }.takeIf { it.isNotBlank() }
                }
            } else {
                listOf(body)
            }
            return candidates.firstNotNullOfOrNull { c ->
                val o = runCatching { json.parseToJsonElement(c).jsonObject }.getOrNull()
                o?.takeIf { (it["id"] as? JsonPrimitive)?.contentOrNull == id.toString() }
            }
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject
