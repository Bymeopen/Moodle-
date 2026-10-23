package id.pribadi.minilms

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/** Pemutar video layar penuh untuk file yang sudah diunduh (VideoView bawaan Android; tanpa dependensi tambahan, jalan offline). */
@Composable
fun VideoPlayerDialog(file: File, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    val mc = MediaController(ctx).also { it.setAnchorView(this) }
                    setMediaController(mc)
                    setVideoPath(file.absolutePath)
                    setOnPreparedListener { start() }
                    setOnErrorListener { _, _, _ -> onClose(); true }       // format tak didukung: tutup, pengguna bisa buka via aplikasi lain
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.stopPlayback() },
        )
    }
}
