package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLCallback
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Headers
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads video streams with yt-dlp and falls back to FFmpeg for adaptive streams.
 *
 * yt-dlp can only write to a filesystem path, while downloads may be stored through SAF. The
 * temporary file is therefore written to the app cache and copied to [destination] only after a
 * successful yt-dlp run.
 */
class ExternalDownloadEngine(
    private val context: Context,
) {

    suspend fun download(
        url: String,
        headers: Headers,
        cookies: String?,
        destination: UniFile,
        onProgress: (Int) -> Unit,
        ffmpegFallback: suspend () -> Unit,
    ) {
        try {
            downloadWithYoutubeDl(url, headers, cookies, destination, onProgress)
        } catch (error: Throwable) {
            if (error is CancellationException || !url.isAdaptiveStream()) throw error
            ffmpegFallback()
        }
    }

    suspend fun updateYoutubeDl() {
        YoutubeDL.getInstance().apply {
            init(context.applicationContext)
            updateYoutubeDL(context.applicationContext)
        }
    }

    private suspend fun downloadWithYoutubeDl(
        url: String,
        headers: Headers,
        cookies: String?,
        destination: UniFile,
        onProgress: (Int) -> Unit,
    ) {
        val outputFile = File.createTempFile("yt-dlp-", ".mkv", context.cacheDir)
        val processId = "anikku-${UUID.randomUUID()}"
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-part")
                addOption("--remux-video", "mkv")
                addOption("--output", outputFile.absolutePath)
                headers.forEach { header ->
                    if (!header.first.equals("Cookie", ignoreCase = true)) {
                        addOption("--add-header", "${header.first}: ${header.second}")
                    }
                }
                cookies?.takeIf(String::isNotBlank)?.let { addOption("--add-header", "Cookie: $it") }
            }

            suspendCancellableCoroutine { continuation ->
                try {
                    YoutubeDL.getInstance().execute(
                        request,
                        processId,
                        object : YoutubeDLCallback {
                            override fun onProgressUpdate(progress: Float, etaInSeconds: Long, line: String) {
                                onProgress(progress.toInt().coerceIn(0, 100))
                            }

                            override fun onLogUpdate(line: String) = Unit
                        },
                    )
                    if (outputFile.length() == 0L) {
                        continuation.resumeWithException(IllegalStateException("yt-dlp did not create a video file"))
                    } else {
                        destination.openOutputStream().use { output -> outputFile.inputStream().copyTo(output) }
                        continuation.resume(Unit)
                    }
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
                continuation.invokeOnCancellation {
                    YoutubeDL.getInstance().destroyProcessById(processId)
                }
            }
        } finally {
            outputFile.delete()
        }
    }

    private fun String.isAdaptiveStream(): Boolean {
        val path = substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }
}
