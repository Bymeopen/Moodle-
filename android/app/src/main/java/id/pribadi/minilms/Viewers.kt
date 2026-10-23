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
import kotlinx.coroutines.launch
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
                        FileKind.IMG -> ImageView(doc.file, onExternal)
                        else -> TextView(doc.file, doc.title, onExternal)
                    }
                }
            }
        }
    }
}

/** Dokumen PDF terbuka; ditutup lewat [mutex] agar tidak menabrak halaman yang sedang dirender. */
private class PdfDoc(val pfd: ParcelFileDescriptor, val renderer: PdfRenderer, val mutex: Mutex = Mutex())
private sealed interface PdfOpen { object Loading : PdfOpen; object Failed : PdfOpen; class Ready(val doc: PdfDoc) : PdfOpen }

@Composable
private fun PdfPages(file: File, onExternal: () -> Unit) {
    val opened by produceState<PdfOpen>(PdfOpen.Loading, file) {
        var doc: PdfDoc? = null
        value = withContext(Dispatchers.IO) {                                 // I/O dan konstruktor PdfRenderer tidak boleh di main thread
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try { PdfRenderer(pfd).let { r -> if (r.pageCount == 0) { r.close(); pfd.close(); PdfOpen.Failed } else PdfOpen.Ready(PdfDoc(pfd, r).also { doc = it }) } }
                catch (e: Exception) { runCatching { pfd.close() }; PdfOpen.Failed }                 // PDF terproteksi/rusak: jangan bocorkan descriptor
            } catch (e: Exception) { PdfOpen.Failed }
        }
        awaitDispose { doc?.let { d -> kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { d.mutex.withLock { runCatching { d.renderer.close() }; runCatching { d.pfd.close() } } } } }
    }
    when (val o = opened) {
        PdfOpen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        PdfOpen.Failed -> Failure("PDF ini tidak bisa dibaca di aplikasi (mungkin terproteksi).", onExternal)
        is PdfOpen.Ready -> PdfContent(o.doc)
    }
}

@Composable
private fun PdfContent(doc: PdfDoc) {
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    val list = rememberLazyListState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pageWidth = maxWidth * zoom
        val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }.coerceIn(200, 2400)
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(pageWidth).fillMaxHeight(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(doc.renderer.pageCount, key = { it }) { i -> PdfPage(doc.renderer, doc.mutex, i, widthPx) }
            }
        }
        Surface(Modifier.align(Alignment.BottomEnd).padding(16.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 4.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${list.firstVisibleItemIndex + 1} / ${doc.renderer.pageCount}", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.labelLarge)
                IconButton({ zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Icon(Icons.Rounded.ZoomOut, "Perkecil") }
                IconButton({ zoom = (zoom + 0.5f).coerceAtMost(2f) }, enabled = zoom < 2f) { Icon(Icons.Rounded.ZoomIn, "Perbesar") }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, mutex: Mutex, index: Int, widthPx: Int) {
    var failed by remember(index, widthPx) { mutableStateOf(false) }
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
        }.also { if (it == null) failed = true }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), "Halaman ${index + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    else Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
        if (failed) Text("Halaman ${index + 1} gagal ditampilkan (memori terbatas); kecilkan zoom.", style = MaterialTheme.typography.bodySmall) else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** Pecah teks jadi potongan kecil untuk daftar malas: normalkan CRLF, pisah per paragraf, dan potong paragraf panjang (satu Text raksasa membuat layout macet). */
fun textChunks(raw: String, max: Int = 2000): List<String> {
    val out = mutableListOf<String>()
    for (para in raw.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))) {
        var rest = para.trim()
        while (rest.length > max) {
            val cut = rest.lastIndexOf('\n', max).takeIf { it > max / 2 } ?: rest.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
            out += rest.substring(0, cut).trim(); rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
    }
    return out
}

@Composable
private fun TextView(file: File, name: String, onExternal: () -> Unit) {
    val text by produceState<String?>(null, file) { value = withContext(Dispatchers.IO) { FileText.extract(file, name) ?: "" } }
    when (val t = text) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        "" -> Failure("Isi dokumen tidak bisa dibaca di aplikasi.", onExternal)
        else -> {
            val paras = remember(t) { textChunks(t) }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Text("Tampilan teks; tabel dan gambar tidak ditampilkan.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(paras.size) { Text(paras[it], style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

@Composable
private fun ImageView(file: File, onExternal: () -> Unit) {
    var done by remember(file) { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, o)
                var s = 1; while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2           // hindari OOM pada foto besar
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (e: OutOfMemoryError) { null }
        }
        done = true
    }
    val b = bmp
    when {
        b != null -> Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        done -> Failure("Gambar rusak atau formatnya tidak didukung.", onExternal)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
private fun Failure(msg: String, onExternal: () -> Unit) = Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(msg, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    OutlinedButton(onExternal) { Text("Buka dengan aplikasi lain") }
}
