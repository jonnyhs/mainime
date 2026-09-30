package eu.kanade.tachiyomi.ui.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import okhttp3.Headers
import tachiyomi.domain.anime.interactor.GetAnime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** WebView fallback for extensions that cannot resolve an episode's video URLs. */
class WebViewCaptureActivity : ComponentActivity() {
    private val captures = mutableStateListOf<CapturedVideo>()
    private lateinit var webContainer: FrameLayout
    private var currentWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = intent.getStringExtra(URL_KEY) ?: return finish()
        val animeId = intent.getLongExtra(ANIME_ID_KEY, -1)
        val episodeId = intent.getLongExtra(EPISODE_ID_KEY, -1)
        setContent {
            TachiyomiTheme {
                Column(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.weight(1f),
                        factory = { context ->
                            FrameLayout(context).also { container ->
                                webContainer = container
                                showWebView(createWebView(context), url)
                            }
                        },
                    )
                    Text(
                        text = stringResource(AMR.strings.webview_capture_links, captures.size),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(captures, key = { it.url }) { capture ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(capture.url.substringAfterLast('/').take(48), Modifier.weight(1f))
                                OutlinedButton(onClick = { play(capture, animeId, episodeId) }) {
                                    Text(stringResource(AMR.strings.action_play_captured_video))
                                }
                                Button(onClick = { download(capture, animeId, episodeId) }) {
                                    Text(stringResource(AMR.strings.action_download_captured_video))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = captureClient()
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // Players commonly open their iframe host in a user-initiated window. Silent windows are ads.
                if (!isUserGesture) return false
                val popup = createWebView(context)
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                showWebView(popup, null)
                return true
            }
        }
    }

    private fun captureClient() = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
            super.shouldInterceptRequest(view, request).also {
                capture(request.url.toString(), view.url, view.settings.userAgentString)
            }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }
    }

    private fun capture(url: String, referer: String?, userAgent: String) {
        if (!isVideoUrl(url) || isAdvertisement(url) || captures.any { it.url == url }) return
        val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
        captures += CapturedVideo(url, referer.orEmpty(), userAgent, cookies)
    }

    private fun isVideoUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4")
    }

    private fun isAdvertisement(url: String): Boolean = listOf("doubleclick", "googlesyndication", "adservice", "popads", "trafficjunky")
        .any { it in url.lowercase() }

    private fun showWebView(webView: WebView, url: String?) {
        currentWebView?.destroy()
        currentWebView = webView
        webContainer.removeAllViews()
        webContainer.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        url?.let(webView::loadUrl)
    }

    private fun download(capture: CapturedVideo, animeId: Long, episodeId: Long) = lifecycleScope.launch {
        val anime = Injekt.get<GetAnime>().await(animeId) ?: return@launch
        val episode = Injekt.get<GetEpisode>().await(episodeId) ?: return@launch
        Injekt.get<DownloadManager>().downloadEpisodes(anime, listOf(episode), video = capture.toVideo())
    }

    private fun play(capture: CapturedVideo, animeId: Long, episodeId: Long) {
        startActivity(
            PlayerActivity.newIntent(
                this,
                animeId,
                episodeId,
                listOf(Hoster(hosterName = "WebView capture", videoList = listOf(capture.toVideo()))),
            ),
        )
    }

    override fun onDestroy() {
        currentWebView?.destroy()
        super.onDestroy()
    }

    private data class CapturedVideo(val url: String, val referer: String, val userAgent: String, val cookies: String) {
        fun toVideo() = Video(
            videoUrl = url,
            videoTitle = "WebView capture",
            headers = Headers.Builder().apply {
                if (referer.isNotBlank()) add("Referer", referer)
                if (userAgent.isNotBlank()) add("User-Agent", userAgent)
                if (cookies.isNotBlank()) add("Cookie", cookies)
            }.build(),
            initialized = true,
        )
    }

    companion object {
        private const val URL_KEY = "url"
        private const val ANIME_ID_KEY = "anime_id"
        private const val EPISODE_ID_KEY = "episode_id"
        fun newIntent(context: Context, url: String, animeId: Long, episodeId: Long) = Intent(context, WebViewCaptureActivity::class.java).apply {
            putExtra(URL_KEY, url)
            putExtra(ANIME_ID_KEY, animeId)
            putExtra(EPISODE_ID_KEY, episodeId)
        }
    }
}
