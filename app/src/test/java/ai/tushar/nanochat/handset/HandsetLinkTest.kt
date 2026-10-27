package ai.tushar.nanochat.handset

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The kept connection, with an in-memory store in place of the Keystore and preferences. */
class HandsetLinkTest {
    private class MemoryStore(var kept: HandsetConnection? = null) : ConnectionStore {
        override fun load() = kept
        override fun save(c: HandsetConnection) { kept = c }
        override fun clear() { kept = null }
    }

    private val connection = HandsetConnection("http://127.0.0.1:8765/mcp", "secret", "agent-1")

    @Test
    fun theTokenGoesOnceNoCompanionUsesThePhone() = runTest {
        val store = MemoryStore(connection)
        val link = HandsetLink({ error("not used") }, store, "1.0")

        link.forgetIfUnused(companionsUsingIt = 1)
        assertEquals(connection, link.connection.value)

        link.forgetIfUnused(companionsUsingIt = 0)
        assertNull(link.connection.value)
        assertNull(store.kept)
        assertNull(HandsetLink({ error("not used") }, store, "1.0").connection.value)
        val e = assertThrows(McpException::class.java) { kotlinx.coroutines.runBlocking { link.session() } }
        assertEquals(McpException.Kind.Unauthorized, e.kind)
    }
}
