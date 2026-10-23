package id.pribadi.minilms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/** Pembungkus berstatus: mengambil state dari ViewModel lalu memanggil [CourseDetailContent]. */
@Composable
fun CourseDetailScreen(vm: MainViewModel, c: Course, st: Repo.CourseStats, onBack: () -> Unit, onAskAi: (AiMode) -> Unit) {
    val tick by vm.tick.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val progress by vm.fileProgress.collectAsStateWithLifecycle()
    val done by vm.doneAssignments.collectAsStateWithLifecycle()
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportZip(c, uri) }
    val nowSec by rememberNowSec(tick)
    CourseDetailContent(c, st, nowSec, done, dl, progress,
        isDownloaded = { i, f -> tick >= 0 && vm.repo.isDownloaded(c.id, i.id, f) }, fileKey = { i, f -> vm.repo.fileKey(c.id, i.id, f) },
        onBack = onBack, onDownloadCourse = { vm.downloadCourse(c.id) }, onPause = vm::pause, onDeleteDownloads = { vm.deleteDownloads(c.id) },
        onOpenFile = { i, f -> vm.open(c, i, f) }, onDownloadFile = { i, f -> vm.downloadFile(c, i, f) }, onLink = vm::openInApp, onOpenMoodle = vm::openMoodle, onToggleDone = vm::toggleDone,
        onAskAi = onAskAi, onExportZip = { zip.launch("${safeName(c.name)}.zip") }, onShareNotebook = { vm.shareToNotebook(c) }, onOpenWeb = vm::openWeb)
}

private enum class DetailTab(val label: String) { MATERI("Materi"), TUGAS("Tugas"), NILAI("Nilai") }

@Composable
fun CourseDetailContent(
    c: Course, st: Repo.CourseStats, nowSec: Long, doneIds: Set<Long>, dl: DlState, fileProgress: Map<String, Float>,
    isDownloaded: (Item, FileRef) -> Boolean, fileKey: (Item, FileRef) -> String,
    onBack: () -> Unit, onDownloadCourse: () -> Unit, onPause: () -> Unit, onDeleteDownloads: () -> Unit,
    onOpenFile: (Item, FileRef) -> Unit, onDownloadFile: (Item, FileRef) -> Unit, onLink: (String) -> Unit, onOpenMoodle: (String) -> Unit, onToggleDone: (Long) -> Unit,
    onAskAi: (AiMode) -> Unit, onExportZip: () -> Unit, onShareNotebook: () -> Unit, onOpenWeb: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(DetailTab.MATERI) }
    val materials = remember(c) { c.items.filter { !it.isTask } }
    val tasks = remember(c) { c.items.filter { it.isTask }.sortedBy { it.dueAt ?: Long.MAX_VALUE } }
    val sections = remember(c) { materials.groupBy { it.section } }
    var expanded by rememberSaveable(c.id) { mutableStateOf(sections.keys.take(1).toSet()) }
    val summary = remember(c) { Logic.gradeSummary(c) }
    val (title, term) = Logic.splitTerm(c.name)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CourseBanner(c.name, c.id, height = 168.dp) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledIconButton(onBack, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(0.25f), contentColor = Color.White)) { Icon(Icons.Rounded.ArrowBack, "Kembali") }
                    Box {
                        FilledIconButton({ menu = true }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(0.25f), contentColor = Color.White)) { Icon(Icons.Rounded.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Ekspor sebagai ZIP") }, { menu = false; onExportZip() }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null) })
                            DropdownMenuItem({ Text("Bagikan materi ke aplikasi AI") }, { menu = false; onShareNotebook() }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                            DropdownMenuItem({ Text("Buka Claude (login SSO)") }, { menu = false; onOpenWeb("https://claude.ai") }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                            DropdownMenuItem({ Text("Buka Gemini (login Google)") }, { menu = false; onOpenWeb("https://gemini.google.com") }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                            DropdownMenuItem({ Text("Buka NotebookLM") }, { menu = false; onOpenWeb("https://notebooklm.google.com") }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                            if (st.downloaded > 0) DropdownMenuItem({ Text("Hapus unduhan kursus") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (term != null) Pill(term, Color.White.copy(0.25f), Color.White)
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (c.summary.isNotBlank()) item { Text(c.summary, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis) }

        // unduh + AI
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (dl.running) {
                    Card2(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProgressBar(if (dl.bytesTotal > 0) dl.bytes.toFloat() / dl.bytesTotal else 0f, color = MaterialTheme.colorScheme.secondary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (dl.waiting) "Menunggu Wi-Fi atau koneksi… unduhan dimulai otomatis" else "${dl.done}/${dl.total} · ${dl.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onPause) { Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Jeda") }
                            }
                        }
                    }
                } else if (st.files > 0) {
                    val all = st.downloaded == st.files
                    Button(onDownloadCourse, enabled = !all, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Icon(if (all) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (all) "Semua file siap offline" else "Unduh ${st.files - st.downloaded} file (${Logic.fmtSize(st.bytes - st.downloadedBytes)})")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiChip(Icons.Rounded.ChatBubbleOutline, "Tanya", Modifier.weight(1f)) { onAskAi(AiMode.CHAT) }
                    AiChip(Icons.Rounded.Summarize, "Ringkas", Modifier.weight(1f)) { onAskAi(AiMode.SUMMARY) }
                    AiChip(Icons.Rounded.Quiz, "Soal", Modifier.weight(1f)) { onAskAi(AiMode.QUIZ) }
                    AiChip(Icons.Rounded.Style, "Kartu", Modifier.weight(1f)) { onAskAi(AiMode.CARDS) }
                }
            }
        }

        item {
            TabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary) {
                DetailTab.entries.forEach { t ->
                    Tab(tab == t, { tab = t }, selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, text = { Text(when (t) { DetailTab.MATERI -> "Materi (${materials.size})"; DetailTab.TUGAS -> "Tugas (${tasks.size})"; DetailTab.NILAI -> "Nilai" }) })
                }
            }
        }

        when (tab) {
            DetailTab.MATERI -> {
                if (materials.isEmpty()) item { EmptyState(Icons.Rounded.Notes, "Belum ada materi", "Dosen belum menambahkan materi di kursus ini.") }
                sections.forEach { (name, list) ->
                    val open = name in expanded
                    item(key = "s_$name") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(MaterialTheme.shapes.medium).clickable { expanded = if (open) expanded - name else expanded + name }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name.ifEmpty { "Umum" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Pill("${list.size}", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                    if (open) items(list.size, key = { "m_${list[it].id}" }) { idx -> MaterialCard(list[idx], isDownloaded, fileKey, fileProgress, onOpenFile, onDownloadFile, onLink, onOpenMoodle) }
                }
            }
            DetailTab.TUGAS -> {
                if (tasks.isEmpty()) item { EmptyState(Icons.Rounded.Assignment, "Tidak ada tugas", "Kursus ini tidak punya tugas yang terlihat.") }
                items(tasks.size, key = { "t_${tasks[it].id}" }) { TaskCard(tasks[it], tasks[it].id in doneIds, nowSec, onOpenMoodle) { onToggleDone(tasks[it].id) } }
            }
            DetailTab.NILAI -> item { GradesPane(c, summary) }
        }
    }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Hapus unduhan kursus ini?") },
        text = { Text("${st.downloaded} file (${Logic.fmtSize(st.downloadedBytes)}) dihapus dari ponsel. Anda bisa mengunduhnya lagi kapan saja.") },
        confirmButton = { TextButton({ confirmDelete = false; onDeleteDownloads() }) { Text("Hapus", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Batal") } })
}

@Composable
private fun AiChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) =
    Surface(onClick, modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }

@Composable
private fun MaterialCard(it: Item, isDownloaded: (Item, FileRef) -> Boolean, fileKey: (Item, FileRef) -> String, progress: Map<String, Float>,
                         onOpenFile: (Item, FileRef) -> Unit, onDownloadFile: (Item, FileRef) -> Unit, onLink: (String) -> Unit, onOpenMoodle: (String) -> Unit) {
    var open by rememberSaveable(it.id) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val hasText = (it.type == "page" || it.type == "forum") && it.content.isNotBlank()
    Card2(Modifier.fillMaxWidth().padding(horizontal = 20.dp).animateContentSize(), if (hasText) ({ open = !open }) else null) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(when (it.type) { "page" -> Icons.Rounded.Article; "forum" -> Icons.Rounded.Forum; else -> Icons.Rounded.Link }, if (it.type == "page" || it.type == "forum") cs.tertiary else cs.primary, size = 36.dp)
                Text(it.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (hasText) Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = cs.outline)
                else if (it.type == "link" && it.files.isEmpty() && it.content.startsWith("http")) IconButton({ onLink(it.content) }) { Icon(Icons.Rounded.OpenInNew, "Buka di aplikasi", tint = cs.primary) }
            }
            if (hasText) {
                if (open) Text(it.content, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                else Text(it.content, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            it.files.forEach { f -> FileRow(it, f, isDownloaded(it, f), progress[fileKey(it, f)], onOpenFile, onDownloadFile) }
            if (it.type == "forum" && it.url.isNotEmpty()) OutlinedButton({ onOpenMoodle(it.url) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Forum, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buka forum / balas") }
            // Modul Moodle tanpa file/tautan luar (video terpasang, forum, dll.): buka halaman aslinya dengan login otomatis.
            if (it.type == "link" && it.files.isEmpty() && !it.content.startsWith("http") && it.url.isNotEmpty())
                OutlinedButton({ onOpenMoodle(it.url) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buka di Moodle") }
        }
    }
}

@Composable
private fun FileRow(i: Item, f: FileRef, downloaded: Boolean, progress: Float?, onOpen: (Item, FileRef) -> Unit, onDownload: (Item, FileRef) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerLow).clickable(enabled = downloaded) { onOpen(i, f) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FileTile(FileKind.of(f.name, f.mime))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Logic.fmtSize(f.size) + if (downloaded) " · ketuk untuk membuka" else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        when {
            downloaded -> Icon(Icons.Rounded.CloudDone, "Sudah diunduh", tint = cs.secondary)
            progress != null -> ProgressRing(progress, 36.dp, 4.dp, cs.secondary)
            else -> FilledTonalIconButton({ onDownload(i, f) }, Modifier.size(40.dp)) { Icon(Icons.Rounded.FileDownload, "Unduh ${f.name}") }
        }
    }
}

@Composable
private fun TaskCard(it: Item, done: Boolean, nowSec: Long, onOpenMoodle: (String) -> Unit, onToggle: () -> Unit) {
    var open by rememberSaveable(it.id) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val due = it.dueAt
    val urg = due?.let { d -> Logic.urgency(d, nowSec) }
    val (bg, fg) = when { done || due == null -> cs.surfaceVariant to cs.onSurfaceVariant; urg == Urgency.HIGH -> cs.errorContainer to cs.onErrorContainer; urg == Urgency.MEDIUM -> cs.primaryContainer to cs.onPrimaryContainer; else -> cs.surfaceVariant to cs.onSurfaceVariant }
    Card2(Modifier.fillMaxWidth().padding(horizontal = 20.dp).animateContentSize(), { open = !open }) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleSmall, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis, color = if (done) cs.onSurfaceVariant else cs.onSurface)
                    if (due != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (done) "Selesai" else Logic.relative(due, nowSec), bg, fg, Icons.Rounded.Schedule)
                        Text(java.text.SimpleDateFormat("d MMM yyyy · HH:mm", Locale("id")).format(java.util.Date(due * 1000)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                if (it.type == "quiz") Pill("Tes", cs.tertiaryContainer, cs.onTertiaryContainer, Icons.Rounded.Quiz)
                IconButton(onToggle) { Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (done) "Tandai belum selesai" else "Tandai selesai", tint = if (done) cs.secondary else cs.outline) }
            }
            if (open) {
                if (it.content.isNotBlank()) Text(it.content, Modifier.padding(end = 10.dp), style = MaterialTheme.typography.bodyMedium)
                Text(if (it.type == "quiz") "Tes dikerjakan online di Moodle; info jadwal tersimpan untuk offline." else "Kumpulkan dan lihat nilai di Moodle kampus.", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                it.attempts.forEach { a ->
                    val stateLabel = when (a.state) { "finished" -> "Selesai"; "inprogress" -> "Sedang berjalan"; "overdue" -> "Lewat waktu"; "abandoned" -> "Ditinggalkan"; else -> a.state }
                    var showReview by rememberSaveable(it.id, a.number) { mutableStateOf(false) }
                    Row(Modifier.padding(end = 10.dp).then(if (a.review.isNotEmpty()) Modifier.clickable { showReview = !showReview } else Modifier), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Percobaan ${a.number}", style = MaterialTheme.typography.labelLarge)
                        Text(stateLabel + (a.finishedAt?.let { t -> " · " + java.text.SimpleDateFormat("d MMM yyyy HH:mm", Locale("id")).format(java.util.Date(t * 1000)) } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (a.review.isNotEmpty()) Icon(if (showReview) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Soal dan jawaban", tint = cs.outline)
                        a.grade?.let { g -> Pill("${Logic.fmtGrade(g)} / ${Logic.fmtGrade(a.max ?: 0.0)}", cs.secondaryContainer, cs.onSecondaryContainer) }
                    }
                    if (showReview) Text("Soal & jawaban percobaan ${a.number}\n\n${a.review}", Modifier.padding(end = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (it.url.isNotEmpty()) Button({ onOpenMoodle(it.url) }, Modifier.padding(end = 10.dp)) { Text(if (it.type == "quiz") "Kerjakan tes di Moodle" else "Buka di Moodle") }
            }
        }
    }
}

@Composable
private fun GradesPane(c: Course, s: GradeSummary) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.rows.isEmpty()) { EmptyState(Icons.Rounded.Insights, "Belum ada nilai", "Nilai muncul setelah dosen menilai."); return }
        Card2(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val pct = s.percent
                ProgressRing((pct ?: 0.0).toFloat() / 100f, 72.dp, 8.dp, if (pct != null) gradeColor(pct) else MaterialTheme.colorScheme.outline) { Text(if (pct != null) "${pct.toInt()}" else "–", style = MaterialTheme.typography.titleLarge) }
                Column { Text("Nilai kursus", style = MaterialTheme.typography.titleMedium); Text("${s.graded} dari ${s.total} item bernilai", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Card2(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                s.rows.forEach { r ->
                    val p = if ((r.max ?: 0.0) > 0) r.grade!! / r.max!! * 100 else null
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("%.0f".format(Locale.US, r.grade) + (r.max?.let { " / %.0f".format(Locale.US, it) } ?: ""), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (p != null) { Spacer(Modifier.width(8.dp)); Pill("${p.toInt()}%", gradeColor(p).copy(alpha = 0.15f), gradeColor(p)) }
                    }
                }
            }
        }
    }
}
