package id.pribadi.minilms

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Kebijakan navigasi WebView: murni agar bisa dites. Halaman boleh berpindah dalam host yang sama dan situs video tepercaya; sisanya dibuka di browser. */
object WebPolicy {
    private val videoHosts = listOf("youtube.com", "youtube-nocookie.com", "youtu.be", "vimeo.com", "googlevideo.com", "ytimg.com", "gstatic.com")
    fun hostOf(url: String): String? = try { java.net.URI(url).takeIf { it.scheme == "https" || it.scheme == "http" }?.host?.lowercase() } catch (e: Exception) { null }
    fun allowedInApp(startHost: String?, target: String): Boolean {
        val h = hostOf(target) ?: return false
        return h == startHost || videoHosts.any { h == it || h.endsWith(".$it") }
    }
}

/**
 * Peramban dalam aplikasi untuk halaman LMS (tes, forum, tugas, rekaman, video) sehingga seluruh isi Moodle bisa dipakai tanpa keluar aplikasi.
 * Sesi login berasal dari kunci autologin (bukan password) dan cookie dihapus saat layar ditutup.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MoodleWebScreen(url: String, onClose: () -> Unit, onExternal: (String) -> Unit, onSessionExpired: () -> Unit = {}) {
    val startHost = remember(url) { WebPolicy.hostOf(url) }
    var title by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCb by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val back = { if (customView != null) { customCb?.onCustomViewHidden() } else if (web?.canGoBack() == true) web?.goBack() else onClose() }
    BackHandler { back() }
    var expired by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { CookieManager.getInstance().flush(); web?.destroy() } }       // cookie sesi dipertahankan; dihapus saat logout

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Tutup") }
            Text(title.ifBlank { startHost.orEmpty() }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            IconButton({ web?.reload() }) { Icon(Icons.Rounded.Refresh, "Muat ulang") }
            IconButton({ onExternal(web?.url ?: url) }) { Icon(Icons.Rounded.OpenInNew, "Buka di browser") }
        }
        if (expired) InfoBanner("Sesi Moodle berakhir.", Modifier.padding(horizontal = 12.dp), tone = Tone.WARN, icon = Icons.Rounded.Lock, action = "Masuk otomatis", onAction = onSessionExpired)
        if (progress in 1..99) ProgressBar(progress / 100f, Modifier.fillMaxWidth().height(3.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false; settings.allowContentAccess = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.useWideViewPort = true; settings.loadWithOverviewMode = true         // halaman desktop muat di lebar layar
                        settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                val t = r.url.toString()
                                if (WebPolicy.allowedInApp(startHost, t)) return false
                                onExternal(t); return true
                            }
                            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { progress = 1 }
                            override fun onPageFinished(v: WebView, u: String) { expired = u.contains("/login/index.php") && startHost != null && WebPolicy.hostOf(u) == startHost }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                            override fun onReceivedTitle(v: WebView, t: String) { title = t }
                            override fun onShowCustomView(v: View, cb: CustomViewCallback) { customView = v; customCb = cb }      // video layar penuh
                            override fun onHideCustomView() { customView = null; customCb = null }
                        }
                        setDownloadListener { dl, _, _, _, _ -> onExternal(dl) }        // unduhan file: lewat browser/pengunduh sistem
                        web = this
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            customView?.let { cv ->
                AndroidView({ ctx -> FrameLayout(ctx).apply { setBackgroundColor(0xFF000000.toInt()); (cv.parent as? ViewGroup)?.removeView(cv); addView(cv) } }, Modifier.fillMaxSize())
            }
        }
    }
}
