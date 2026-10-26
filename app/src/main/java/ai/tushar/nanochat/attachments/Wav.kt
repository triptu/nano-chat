package ai.tushar.nanochat.attachments

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Voice notes are 16 kHz mono 16-bit PCM, the format Gemma's audio encoder expects. */
const val VoiceSampleRate = 16_000
private const val Channels = 1
private const val BitsPerSample = 16
const val WavHeaderBytes = 44

/** The 44-byte RIFF header of a PCM WAV file holding [dataBytes] bytes of samples. */
fun wavHeader(dataBytes: Int, sampleRate: Int = VoiceSampleRate): ByteArray {
    val byteRate = sampleRate * Channels * BitsPerSample / 8
    return ByteBuffer.allocate(WavHeaderBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + dataBytes)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // fmt chunk size
        putShort(1) // PCM
        putShort(Channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort((Channels * BitsPerSample / 8).toShort())
        putShort(BitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataBytes)
    }.array()
}

/** Writes PCM samples to a WAV file: a placeholder header first, the real one once the length is known. */
class WavWriter(file: File) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(wavHeader(0)) }
    var dataBytes = 0
        private set

    val durationMs: Long get() = dataBytes * 1000L / (VoiceSampleRate * 2)

    fun write(samples: ShortArray, count: Int) {
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) bytes.putShort(samples[i])
        out.write(bytes.array())
        dataBytes += count * 2
    }

    override fun close() {
        out.seek(0)
        out.write(wavHeader(dataBytes))
        out.close()
    }
}

/** Length of a 16 kHz mono 16-bit WAV file, from its size. */
fun wavDurationMs(file: File): Long = ((file.length() - WavHeaderBytes).coerceAtLeast(0) * 1000L) / (VoiceSampleRate * 2)
