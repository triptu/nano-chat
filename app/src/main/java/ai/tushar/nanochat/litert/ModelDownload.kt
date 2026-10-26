package ai.tushar.nanochat.litert

import ai.tushar.nanochat.model.DownloadEvent
import ai.tushar.nanochat.model.ModelErrorKind
import ai.tushar.nanochat.model.ModelException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

private const val ProgressEveryBytes = 4L * 1024 * 1024

/**
 * Downloads [url] to [target], resuming from `<target>.part` when an earlier download was cancelled or cut off.
 * The part file is renamed to [target] only once all [expectedBytes] arrived.
 */
fun downloadFile(url: String, target: File, expectedBytes: Long): Flow<DownloadEvent> = flow {
    target.parentFile?.mkdirs()
    val part = File(target.path + ".part")
    var have = if (part.exists()) part.length() else 0L
    if (have > expectedBytes) { part.delete(); have = 0 }
    emit(DownloadEvent.Started(expectedBytes))
    emit(DownloadEvent.Progress(have))
    try {
        if (have < expectedBytes) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK && have > 0) have = 0 // The server ignored the range; start over.
                else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw IOException("HTTP $code from the model server")
                }
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(have)
                    out.seek(have)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var sinceEmit = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            sinceEmit += n
                            if (sinceEmit >= ProgressEveryBytes) { sinceEmit = 0; emit(DownloadEvent.Progress(have)) }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        if (have != expectedBytes) throw IOException("Download stopped at $have of $expectedBytes bytes")
        if (!part.renameTo(target)) throw IOException("Couldn't move the model file into place")
        emit(DownloadEvent.Progress(expectedBytes))
        emit(DownloadEvent.Completed)
    } catch (e: IOException) {
        emit(DownloadEvent.Failed(ModelException(ModelErrorKind.Other, null, "DOWNLOAD_FAILED", e.message)))
    }
}.flowOn(Dispatchers.IO)
