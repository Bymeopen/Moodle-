package id.pribadi.minilms

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LibraryScreen(vm: MainViewModel, onBack: () -> Unit) {
    val st by vm.library.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.libraryLoad() }
    Column(Modifier.fillMaxSize()) {
        BackBar("Perpustakaan", onBack)
        LibraryContent(st, vm::librarySource, vm::librarySearch, vm::libraryDownload, vm::openInApp, vm::libraryOpen, vm::libraryDelete, vm::libraryCampus)
    }
}

/** Perpustakaan gratis: cari buku (Internet Archive) dan jurnal akses terbuka (DOAJ); buku bisa diunduh untuk offline. Stateless. */
@Composable
fun LibraryContent(
    st: MainViewModel.LibState, onSource: (LibSource) -> Unit, onSearch: (String) -> Unit, onDownload: (LibItem) -> Unit,
    onOpenUrl: (String) -> Unit, onOpenSaved: (LibSaved) -> Unit, onDeleteSaved: (LibSaved) -> Unit,
    onCampus: (Campus) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var tab by rememberSaveable { mutableIntStateOf(0) }            // 0 cari, 1 tersimpan
    var q by rememberSaveable { mutableStateOf(st.query) }
    var delete by remember { mutableStateOf<LibSaved?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == 0, { tab = 0 }, { Text("Cari") }, leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) })
                FilterChip(tab == 1, { tab = 1 }, { Text("Tersimpan (${st.saved.size})") }, leadingIcon = { Icon(Icons.Rounded.CloudDone, null, Modifier.size(18.dp)) })
            }
        }
        if (tab == 0) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LibSource.values().forEach { s -> FilterChip(st.source == s, { onSource(s) }, { Text(s.label) }) }
                    }
                    if (st.source == LibSource.CAMPUS) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LibraryApi.campuses.forEach { c -> FilterChip(st.campus == c, { onCampus(c) }, { Text(c.name) }) }
                        }
                        if (!st.campus.verified) Text("Situs ini belum teruji dari aplikasi; bila gagal, buka lewat browser.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MANAGEMENT_TOPICS.forEach { t -> SuggestionChip({ q = t; onSearch(t) }, { Text(t) }) }
                        }
                    }
                    Text(st.source.hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Cari judul, penulis, atau topik") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (st.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else IconButton({ onSearch(q) }, enabled = q.isNotBlank()) { Icon(Icons.Rounded.ArrowForward, "Cari") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSearch(q) }))
                }
            }
            st.error?.let { e -> item { InfoBanner(e, tone = Tone.WARN, icon = Icons.Rounded.WifiOff) } }
            if (st.searched && st.results.isEmpty() && st.error == null) item { EmptyState(Icons.Rounded.SearchOff, "Tidak ada hasil", "Coba kata kunci lain, atau bahasa Inggris (koleksi terbesar).") }
            if (!st.searched && !st.searching) item { EmptyState(Icons.Rounded.LocalLibrary, "Perpustakaan gratis", "Cari buku dan jurnal tanpa akun. Buku PDF bisa diunduh untuk dibaca offline.") }
            items(st.results, key = { "${it.source}_${it.id}" }) { r ->
                val prog = st.downloading[r.id]
                val saved = st.saved.any { it.id == r.id }
                Card2(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(when (r.source) { LibSource.BOOKS -> Icons.Rounded.MenuBook; LibSource.CAMPUS -> Icons.Rounded.School; else -> Icons.Rounded.Article }, cs.tertiary, size = 36.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val meta = listOf(r.author, r.year, r.note).filter { it.isNotBlank() }.joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            r.source != LibSource.BOOKS -> IconButton({ onOpenUrl(r.url) }) { Icon(Icons.Rounded.OpenInNew, "Buka di aplikasi", tint = cs.primary) }
                            saved -> Icon(Icons.Rounded.CloudDone, "Sudah diunduh", tint = cs.secondary)
                            prog != null -> if (prog >= 0f) ProgressRing(prog, 36.dp, 4.dp, cs.secondary) else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            else -> Row {
                                IconButton({ onOpenUrl(r.url) }) { Icon(Icons.Rounded.OpenInNew, "Buka di browser") }
                                FilledTonalIconButton({ onDownload(r) }, Modifier.size(40.dp)) { Icon(Icons.Rounded.FileDownload, "Unduh PDF") }
                            }
                        }
                    }
                }
            }
        } else {
            if (st.saved.isEmpty()) item { EmptyState(Icons.Rounded.LocalLibrary, "Belum ada buku tersimpan", "Unduh buku dari tab Cari; bisa dibaca tanpa internet.") }
            items(st.saved, key = { "s_${it.id}" }) { s ->
                Card2(Modifier.fillMaxWidth(), { onOpenSaved(s) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FileTile(FileKind.PDF)
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(s.author, Logic.fmtSize(s.size)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton({ delete = s }) { Icon(Icons.Rounded.DeleteOutline, "Hapus", tint = cs.outline) }
                    }
                }
            }
        }
    }
    delete?.let { s -> AlertDialog({ delete = null }, title = { Text("Hapus buku ini dari ponsel?") }, text = { Text(s.title) },
        confirmButton = { TextButton({ delete = null; onDeleteSaved(s) }) { Text("Hapus", color = cs.error) } },
        dismissButton = { TextButton({ delete = null }) { Text("Batal") } }) }
}
