package ai.tushar.nanochat.handset

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A fake Handset: answers like its McpHandler, over real HTTP on loopback. */
class McpClientTest {
    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private var token = "hs_good"
    private var eventStream = false
    private var sessionId: String? = null
    private var forgetSessionOnce = false

    private val url get() = "http://127.0.0.1:${server.address.port}/mcp"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            val message = Json.parseToJsonElement(body).jsonObject
            val method = (message["method"] as JsonPrimitive).content
            seen += method to headers
            fun reply(status: Int, text: String?) {
                if (method == "initialize") sessionId?.let { ex.responseHeaders.add("Mcp-Session-Id", it) }
                val bytes = text?.toByteArray() ?: ByteArray(0)
                ex.responseHeaders.add("Content-Type", if (eventStream) "text/event-stream" else "application/json")
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            if (headers["authorization"] != "Bearer $token") return@createContext reply(401, """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}""")
            if (sessionId != null && method != "initialize" && method.startsWith("tools") && forgetSessionOnce) {
                forgetSessionOnce = false
                return@createContext reply(404, "")
            }
            val id = message["id"] ?: return@createContext reply(202, null)
            val result: JsonObject = when (method) {
                "initialize" -> buildJsonObject { put("protocolVersion", "2025-06-18"); put("capabilities", buildJsonObject {}) }
                "tools/list" -> Json.parseToJsonElement(
                    """{"tools":[{"name":"search_context","description":"Search","inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}},
                       {"name":"tap","description":"Tap","inputSchema":{"type":"object"}}]}""",
                ).jsonObject
                "tools/call" -> {
                    val params = message["params"]!!.jsonObject
                    val name = (params["name"] as JsonPrimitive).content
                    val query = (params["arguments"]!!.jsonObject["query"] as? JsonPrimitive)?.contentOrNull
                    if (name == "inbox_since") {
                        Json.parseToJsonElement("""{"content":[{"type":"text","text":"Notifications access is off for Nano Chat. Ask the user to turn on Notifications for Nano Chat in Handset's Settings, under Connected agents."}],"isError":true}""").jsonObject
                    } else {
                        Json.parseToJsonElement("""{"content":[{"type":"text","text":"1 match for “$query”"},{"type":"image","data":"AA==","mimeType":"image/png"}],"isError":false}""").jsonObject
                    }
                }
                else -> return@createContext reply(200, """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found: $method"}}""")
            }
            val envelope = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
            reply(200, if (eventStream) "event: message\ndata: $envelope\n\n" else envelope)
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun initializesOnceThenListsAndCalls() = runTest {
        val client = McpClient(url, "hs_good", "test")
        val tools = client.listTools()
        assertEquals(listOf("search_context", "tap"), tools.map { it.name })
        assertEquals("[\"query\"]", tools.first().inputSchema["required"].toString())

        val r = client.call("search_context", buildJsonObject { put("query", "Toit") })
        assertFalse(r.isError)
        assertEquals("1 match for “Toit”\n[image]", r.text)

        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), seen.map { it.first })
        // The version header goes on every message after initialize.
        assertEquals(null, seen[0].second["mcp-protocol-version"])
        assertTrue(seen.drop(1).all { it.second["mcp-protocol-version"] == "2025-06-18" })
        assertTrue(seen.all { it.second["accept"] == "application/json, text/event-stream" })
    }

    @Test
    fun aRefusalIsAnErrorResultNotAnException() = runTest {
        val r = McpClient(url, "hs_good", "test").call("inbox_since", buildJsonObject { put("time", "3h") })
        assertTrue(r.isError)
        assertTrue(isRefusal(r.text))
    }

    @Test
    fun readsEventStreamReplies() = runTest {
        eventStream = true
        val tools = McpClient(url, "hs_good", "test").listTools()
        assertEquals(2, tools.size)
    }

    @Test
    fun aReplacedTokenIsUnauthorized() = runTest {
        token = "hs_new"
        try {
            McpClient(url, "hs_old", "test").listTools()
            fail("should refuse")
        } catch (e: McpException) {
            assertEquals(McpException.Kind.Unauthorized, e.kind)
        }
    }

    @Test
    fun nothingListeningIsUnreachable() = runTest {
        server.stop(0)
        try {
            McpClient(url, "hs_good", "test", connectTimeoutMs = 500).listTools()
            fail("should fail")
        } catch (e: McpException) {
            assertEquals(McpException.Kind.Unreachable, e.kind)
        }
    }

    @Test
    fun keepsTheSessionAndStartsAgainWhenItsGone() = runTest {
        sessionId = "s1"
        val client = McpClient(url, "hs_good", "test")
        client.listTools()
        assertEquals("s1", seen.last().second["mcp-session-id"])
        forgetSessionOnce = true
        client.listTools()
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list", "initialize", "notifications/initialized", "tools/list"), seen.map { it.first })
    }

    @Test
    fun anUnknownMethodErrorIsAProtocolProblem() = runTest {
        assertEquals(null, McpClient.parseReply("application/json", """{"jsonrpc":"2.0","id":9,"result":{}}""", id = 3))
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"ok\":true}}\n\n"
        assertEquals(JsonPrimitive(true), McpClient.parseReply("text/event-stream", sse, id = 3)!!["result"]!!.jsonObject["ok"])
    }
}
