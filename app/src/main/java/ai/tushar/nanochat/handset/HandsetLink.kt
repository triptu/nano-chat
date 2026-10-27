package ai.tushar.nanochat.handset

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Handset's package, its connect screen, and where people can read about it. */
object HandsetApp {
    const val Package = "ai.tushar.handset"
    const val ConnectAction = "ai.tushar.handset.action.CONNECT_APP"
    const val ReadmeUrl = "https://github.com/triptu/handset#readme"

    fun connectIntent() = Intent(ConnectAction).setPackage(Package)
}

/** Where Handset's server is and the token it gave this app. One connection serves every companion. */
data class HandsetConnection(val url: String, val token: String, val agentId: String)

/** What came back from Handset's connect screen. */
sealed interface ConnectResult {
    data class Connected(val connection: HandsetConnection) : ConnectResult
    /** [message] is worded for the person, and says what to do next. */
    data class NotConnected(val message: String) : ConnectResult

    companion object {
        fun parse(resultCode: Int, data: Intent?): ConnectResult {
            val url = data?.getStringExtra("mcp_url")
            val token = data?.getStringExtra("token")
            val agentId = data?.getStringExtra("agent_id")
            if (resultCode == Activity.RESULT_OK && url != null && token != null && agentId != null) {
                return Connected(HandsetConnection(url, token, agentId))
            }
            return NotConnected(
                when (data?.getStringExtra("reason")) {
                    "denied" -> "Not connected. Handset asks again next time you turn this on."
                    "handset_off" -> "Handset's Direct connection is off. Turn it on in Handset's Settings, under Connections, then try again."
                    else -> "Not connected. Try again?"
                },
            )
        }

        const val NoConnectScreen = "This version of Handset can't connect to other apps yet. Update Handset, then try again."
    }
}

/** The tools a reply may call, read from Handset, and the client to call them with. */
class PhoneSession(val client: McpClient, val schemas: Map<String, JsonObject>, val connection: HandsetConnection)

/**
 * This app's one connection to Handset. The token is encrypted with a key that never leaves the Android Keystore, and
 * the encrypted copy is kept in app-private preferences.
 */
class HandsetLink(private val context: Context, private val appVersion: String) {
    private val prefs = context.getSharedPreferences("handset", Context.MODE_PRIVATE)
    private val _connection = MutableStateFlow(load())
    val connection: StateFlow<HandsetConnection?> = _connection.asStateFlow()

    private val lock = Mutex()
    private var session: PhoneSession? = null
    private var sessionAt = 0L

    fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(HandsetApp.Package, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** True when the installed Handset has the screen that connects other apps. */
    fun canConnect(): Boolean = context.packageManager.resolveActivity(HandsetApp.connectIntent(), 0) != null

    fun save(c: HandsetConnection) {
        prefs.edit {
            putString(KeyUrl, c.url)
            putString(KeyAgent, c.agentId)
            putString(KeyToken, SecretBox.seal(c.token))
        }
        session = null
        _connection.value = c
    }

    /** Drops the token, as when Handset stops accepting it. Turning a companion's switch on connects again. */
    fun forget() {
        prefs.edit { clear() }
        session = null
        _connection.value = null
    }

    /**
     * Opens a session and lists Handset's tools, keeping only the ones companions are offered. The list is kept for a
     * minute, since Handset lets clients cache it that long.
     */
    suspend fun session(): PhoneSession = lock.withLock {
        val c = _connection.value ?: throw McpException(McpException.Kind.Unauthorized, "Not connected to Handset")
        val now = System.currentTimeMillis()
        session?.takeIf { it.connection == c && now - sessionAt < SessionTtlMs }?.let { return it }
        val client = McpClient(c.url, c.token, appVersion)
        val offered = PhoneTool.entries.map { it.slug }.toSet()
        val schemas = client.listTools().filter { it.name in offered }.associate { it.name to it.inputSchema }
        PhoneSession(client, schemas, c).also { session = it; sessionAt = now }
    }

    private fun load(): HandsetConnection? {
        val url = prefs.getString(KeyUrl, null) ?: return null
        val agent = prefs.getString(KeyAgent, null) ?: return null
        val token = prefs.getString(KeyToken, null)?.let { runCatching { SecretBox.open(it) }.getOrNull() } ?: return null
        return HandsetConnection(url, token, agent)
    }

    private companion object {
        const val KeyUrl = "url"
        const val KeyAgent = "agent_id"
        const val KeyToken = "token"
        const val SessionTtlMs = 60_000L
    }
}

/** AES-GCM with a key kept in the Android Keystore. A sealed value is the IV and the ciphertext, base64. */
private object SecretBox {
    private const val Alias = "nanochat_handset"
    private const val Transformation = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(Alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(Alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(Transformation).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(Transformation).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}
