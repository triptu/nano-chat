package ai.tushar.nanochat.attachments

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

/** Longest voice note. Recording stops by itself here. */
const val MaxVoiceNoteMs = 30_000L

/** One recording in progress: how long it has run and how loud the last moment was (0 to 1). */
data class Recording(val file: File, val elapsedMs: Long = 0, val level: Float = 0f)

/**
 * Records a voice note from the microphone into a WAV file, 16 kHz mono PCM 16-bit.
 * The caller must hold RECORD_AUDIO before calling [start].
 */
class VoiceRecorder(private val scope: CoroutineScope) {
    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()

    private var job: Job? = null
    private var onLimit: ((File, Long) -> Unit)? = null

    /** Starts recording into [file]. [onAutoStop] runs if the note reaches [MaxVoiceNoteMs]. Returns false if the mic couldn't open. */
    @SuppressLint("MissingPermission")
    fun start(file: File, onAutoStop: (File, Long) -> Unit): Boolean {
        if (job != null) return false
        val minBuffer = AudioRecord.getMinBufferSize(VoiceSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, VoiceSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return false
        onLimit = onAutoStop
        _recording.value = Recording(file)
        job = scope.launch(Dispatchers.IO) {
            val writer = WavWriter(file)
            // 50 ms per read keeps the level meter lively.
            val buffer = ShortArray(VoiceSampleRate / 20)
            var hitLimit = false
            try {
                record.startRecording()
                while (isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    writer.write(buffer, n)
                    _recording.value = Recording(file, writer.durationMs, rms(buffer, n))
                    if (writer.durationMs >= MaxVoiceNoteMs) { hitLimit = true; break }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                writer.close()
            }
            if (hitLimit) {
                job = null
                _recording.value = null
                onLimit?.invoke(file, writer.durationMs)
            }
        }
        return true
    }

    /** Stops and keeps the note. Returns its file and length, or null when nothing was recording. */
    suspend fun stop(): Pair<File, Long>? {
        val j = job ?: return null
        val file = _recording.value?.file ?: return null
        j.cancel()
        j.join()
        job = null
        _recording.value = null
        return file to wavDurationMs(file)
    }

    /** Stops and throws the note away. */
    suspend fun cancel() {
        stop()?.first?.delete()
    }
}

private fun rms(samples: ShortArray, count: Int): Float {
    var sum = 0.0
    for (i in 0 until count) { val v = samples[i] / 32768.0; sum += v * v }
    // Speech sits well below full scale, so stretch the meter to make normal talking fill most of it.
    return (sqrt(sum / count) * 4).toFloat().coerceIn(0f, 1f)
}
