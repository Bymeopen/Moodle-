package id.pribadi.minilms

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Dokumen yang sedang dibaca di dalam aplikasi. */
data class ViewDoc(val file: File, val title: String, val kind: FileKind)

/** Jenis file yang bisa dibaca di dalam aplikasi (selain video, yang punya pemutar sendiri). */
fun canViewInApp(name: String, mime: String?): Boolean = when (FileKind.of(name, mime)) {
    FileKind.PDF, FileKind.IMG -> true
    FileKind.DOC, FileKind.PPT -> name.substringAfterLast('.', "").lowercase() in setOf("docx", "pptx", "txt", "md", "csv")
    else -> false
}

/** Pembaca dokumen layar penuh: PDF (PdfRenderer bawaan Android), Word/PowerPoint/teks (diekstrak jadi teks), dan gambar. Semuanya jalan offline. */
@Composable
fun DocViewerDialog(doc: ViewDoc, onClose: () -> Unit, onExternal: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Rounded.Close, "Tutup") }
                    Text(doc.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onExternal) { Icon(Icons.Rounded.OpenInNew, "Buka dengan aplikasi lain") }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (doc.kind) {
                        FileKind.PDF -> PdfPages(doc.file, onExternal)
                        FileKind.IMG -> ImageView(doc.file)
                        else -> TextView(doc.file, doc.title, onExternal)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPages(file: File, onExternal: () -> Unit) {
    val renderer = remember(file) { runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
    if (renderer == null || renderer.pageCount == 0) { Failure("PDF ini tidak bisa dibaca di aplikasi (mungkin terproteksi).", onExternal); return }
    val mutex = remember(renderer) { Mutex() }                    // PdfRenderer hanya boleh membuka satu halaman sekaligus
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = maxWidth * zoom
        val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(pageWidth).fillMaxHeight(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(renderer.pageCount, key = { it }) { i -> PdfPage(renderer, mutex, i, widthPx) }
            }
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(16.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${list.firstVisibleItemIndex + 1} / ${renderer.pageCount}", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.labelLarge)
                IconButton({ zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Icon(Icons.Rounded.ZoomOut, "Perkecil") }
                IconButton({ zoom = (zoom + 0.5f).coerceAtMost(3f) }, enabled = zoom < 3f) { Icon(Icons.Rounded.ZoomIn, "Perbesar") }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, mutex: Mutex, index: Int, widthPx: Int) {
    val bmp by produceState<Bitmap?>(null, index, widthPx) {
        value = withContext(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val page = renderer.openPage(index)
                    try {
                        val h = (page.height.toFloat() * widthPx / page.width).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(AColor.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    } finally { page.close() }
                }
            } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
        }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), "Halaman ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    else Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
}

@Composable
private fun TextView(file: File, name: String, onExternal: () -> Unit) {
    val text by produceState<String?>(null, file) { value = withContext(Dispatchers.IO) { FileText.extract(file, name) ?: "" } }
    when (val t = text) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        "" -> Failure("Isi dokumen tidak bisa dibaca di aplikasi.", onExternal)
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tampilan teks; tabel dan gambar tidak ditampilkan.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { Text(t, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun ImageView(file: File) {
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, o)
                var s = 1; while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2           // hindari OOM pada foto besar
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (e: OutOfMemoryError) { null }
        }
    }
    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun Failure(msg: String, onExternal: () -> Unit) = Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(msg, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onExternal) { Text("Buka dengan aplikasi lain") }
}
