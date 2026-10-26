package ai.tushar.nanochat.attachments

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @Test
    fun writerProducesAValidHeaderAndLength() {
        val file = File.createTempFile("note", ".wav")
        try {
            // Half a second of 16 kHz samples, written in two chunks like the recorder does.
            WavWriter(file).use { w ->
                val chunk = ShortArray(4000) { (it % 100).toShort() }
                w.write(chunk, chunk.size)
                w.write(chunk, chunk.size)
                assertEquals(500L, w.durationMs)
            }
            val bytes = file.readBytes()
            val dataBytes = 8000 * 2
            assertEquals(WavHeaderBytes + dataBytes, bytes.size)
            val h = ByteBuffer.wrap(bytes, 0, WavHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(36 + dataBytes, h.getInt(4))
            assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
            assertEquals(1, h.getShort(20).toInt()) // PCM
            assertEquals(1, h.getShort(22).toInt()) // mono
            assertEquals(16_000, h.getInt(24))
            assertEquals(32_000, h.getInt(28)) // byte rate
            assertEquals(16, h.getShort(34).toInt()) // bits per sample
            assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
            assertEquals(dataBytes, h.getInt(40))
            // Samples are little-endian right after the header.
            assertEquals(1.toShort(), ByteBuffer.wrap(bytes, WavHeaderBytes + 2, 2).order(ByteOrder.LITTLE_ENDIAN).short)
            assertEquals(500L, wavDurationMs(file))
        } finally {
            file.delete()
        }
    }
}
