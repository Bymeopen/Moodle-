package id.pribadi.minilms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeadlineRow(val course: Course, val item: Item)

fun deadlinesOf(s: Snapshot?): List<DeadlineRow> = s?.courses.orEmpty()
    .flatMap { c -> c.items.filter { it.type == "assignment" && it.dueAt != null }.map { DeadlineRow(c, it) } }.sortedBy { it.item.dueAt }

private fun fmtDay(sec: Long) = SimpleDateFormat("EEE, d MMM · HH:mm", Locale("id")).format(Date(sec * 1000))

// =====================================================================================
// BERANDA
// =====================================================================================
@Composable
fun HomeScreen(
    name: String, snapshot: Snapshot?, stats: Map<Long, Repo.CourseStats>, dl: DlState, online: Boolean, busy: Boolean, syncError: String?,
    doneIds: Set<Long>, dueCards: Int, hasKey: Boolean, nowSec: Long, hour: Int,
    onSync: () -> Unit, onOpenCourse: (Long) -> Unit, onDeadlines: () -> Unit, onCourses: () -> Unit, onGrades: () -> Unit,
    onDownloadAll: () -> Unit, onPause: () -> Unit, onReview: () -> Unit, onAskAi: () -> Unit,
) {
    val courses = snapshot?.courses.orEmpty()
    val upcoming = remember(snapshot, doneIds, nowSec) { deadlinesOf(snapshot).filter { it.item.id !in doneIds && it.item.dueAt!! >= nowSec } }
    val first = Logic.firstName(name)
    val files = stats.values.sumOf { it.files }; val got = stats.values.sumOf { it.downloaded }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Logic.greeting(hour) + if (first.isNotEmpty()) "," else "", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(first.ifEmpty { "Mahasiswa" }, style = MaterialTheme.typography.headlineMedium)
                    Text(if (snapshot == null) "Belum sinkron" else "Sinkron " + Logic.ago(snapshot.syncedAt, nowSec), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onSync, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Refresh, "Sinkron sekarang")
                }
            }
        }
        if (!online) item { InfoBanner("Offline — menampilkan data yang tersimpan di ponsel.", tone = Tone.WARN, icon = Icons.Rounded.WifiOff) }
        syncError?.let { e -> item { InfoBanner("Sinkron gagal: $e", tone = Tone.ERROR, icon = Icons.Rounded.ErrorOutline, action = "Coba lagi", onAction = onSync) } }

        if (snapshot == null && busy) {
            item { Skeleton(Modifier.fillMaxWidth().height(150.dp)) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { Skeleton(Modifier.weight(1f).height(96.dp)) } } }
            item { Skeleton(Modifier.fillMaxWidth().height(110.dp)) }
        } else {
            item { DeadlineHero(upcoming.firstOrNull(), nowSec, onDeadlines) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(Icons.Rounded.School, "${courses.size}", "Kursus", MaterialTheme.colorScheme.primary, Modifier.weight(1f), onCourses)
                    StatTile(Icons.Rounded.EventAvailable, "${upcoming.size}", "Tenggat", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f), onDeadlines)
                    StatTile(Icons.Rounded.Style, "$dueCards", "Kartu hari ini", MaterialTheme.colorScheme.secondary, Modifier.weight(1f), if (dueCards > 0) onReview else null)
                }
            }
            item { OfflineCard(files, got, stats.values.sumOf { it.downloadedBytes }, stats.values.sumOf { it.bytes }, dl, onDownloadAll, onPause) }

            val graded = courses.map { it to Logic.gradeSummary(it) }.filter { it.second.percent != null }
            if (graded.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Ringkasan nilai", action = "Semua", onAction = onGrades)
                    Card2(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            graded.take(4).forEach { (c, g) -> GradeBar(Logic.splitTerm(c.name).first, g.percent!!) }
                        }
                    }
                }
            }
            if (courses.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Kursus saya", action = "Semua", onAction = onCourses)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(courses, key = { it.id }) { c -> MiniCourseCard(c, stats[c.id] ?: Repo.CourseStats(0, 0, 0, 0)) { onOpenCourse(c.id) } }
                    }
                }
            }
            item { AiPromo(hasKey, onAskAi) }
        }
    }
}

@Composable
private fun DeadlineHero(next: DeadlineRow?, nowSec: Long, onAll: () -> Unit) {
    val brush = Brush.linearGradient(listOf(Color(0xFFFF8A4C), Color(0xFFD9480F)))
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent) {
        Box(Modifier.background(brush).padding(20.dp)) {
            if (next == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("TENGGAT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.85f))
                    Text("Tidak ada tenggat dekat", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("Nikmati waktu luang, atau lanjutkan belajar dengan kartu hafalan.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.9f))
                }
            } else {
                val due = next.item.dueAt!!
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TENGGAT TERDEKAT", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.85f))
                    Text(next.item.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(Logic.splitTerm(next.course.name).first, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(Logic.relative(due, nowSec), Color.White.copy(0.22f), Color.White, Icons.Rounded.Schedule)
                        Text(fmtDay(due), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.9f))
                    }
                    Button(onAll, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB83A05)), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Lihat semua tenggat"); Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineCard(files: Int, got: Int, gotBytes: Long, allBytes: Long, dl: DlState, onDownload: () -> Unit, onPause: () -> Unit) =
    Card2(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val frac = if (files == 0) 0f else got.toFloat() / files
                ProgressRing(frac, 64.dp, 7.dp, MaterialTheme.colorScheme.secondary) {
                    Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.weight(1f)) {
                    Text("Siap dibaca offline", style = MaterialTheme.typography.titleMedium)
                    Text(if (files == 0) "Kursus Anda belum punya file" else "$got dari $files file · ${Logic.fmtSize(gotBytes)} dari ${Logic.fmtSize(allBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (dl.running) {
                val f = if (dl.bytesTotal > 0) (dl.bytes.toFloat() / dl.bytesTotal).coerceIn(0f, 1f) else 0f
                ProgressBar(f, color = MaterialTheme.colorScheme.secondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${dl.done}/${dl.total} · ${dl.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onPause) { Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Jeda") }
                }
            } else {
                if (dl.failed > 0) InfoBanner("${dl.failed} file gagal: ${dl.error}", tone = Tone.ERROR, icon = Icons.Rounded.ErrorOutline)
                if (files > 0) Button(onDownload, enabled = got < files, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (got == 0) Icons.Rounded.CloudDownload else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (got == files) "Semua file sudah diunduh" else if (got == 0) "Unduh semua" else "Lanjutkan unduhan")
                }
            }
        }
    }

@Composable
private fun GradeBar(title: String, pct: Double) {
    val color = gradeColor(pct)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${pct.toInt()}%", style = MaterialTheme.typography.titleSmall, color = color)
        }
        ProgressBar((pct / 100).toFloat(), color = color)
    }
}

@Composable
fun gradeColor(pct: Double): Color = when { pct >= 80 -> Color(0xFF1E9E5A); pct >= 60 -> Color(0xFFD98A06); else -> MaterialTheme.colorScheme.error }

@Composable
private fun MiniCourseCard(c: Course, st: Repo.CourseStats, onClick: () -> Unit) {
    val (title, term) = Logic.splitTerm(c.name)
    Card2(Modifier.width(220.dp), onClick) {
        CourseBanner(c.name, c.id, height = 64.dp)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            Text(if (st.files == 0) "${c.items.size} materi" else "${st.downloaded}/${st.files} file offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (term != null) Text(term, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AiPromo(hasKey: Boolean, onClick: () -> Unit) =
    Card2(Modifier.fillMaxWidth(), onClick, color = MaterialTheme.colorScheme.tertiaryContainer, border = false) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(Icons.Rounded.AutoAwesome, MaterialTheme.colorScheme.tertiary, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text("Belajar bersama Claude", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(if (hasKey) "Tanya materi, ringkas, buat soal dan kartu hafalan." else "Atur kunci API untuk mulai.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(0.85f))
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }

// =====================================================================================
// KURSUS
// =====================================================================================
enum class CourseFilter(val label: String) { ALL("Semua"), OFFLINE("Siap offline"), PENDING("Belum diunduh") }

@Composable
fun CoursesScreen(snapshot: Snapshot?, stats: Map<Long, Repo.CourseStats>, busy: Boolean, query: String, filter: CourseFilter,
                  onQuery: (String) -> Unit, onFilter: (CourseFilter) -> Unit, onOpen: (Long) -> Unit) {
    val all = snapshot?.courses.orEmpty()
    val shown = remember(snapshot, stats, query, filter) {
        all.filter { c ->
            val s = stats[c.id]
            (query.isBlank() || c.name.contains(query, ignoreCase = true)) && when (filter) {
                CourseFilter.ALL -> true
                CourseFilter.OFFLINE -> s != null && s.files > 0 && s.downloaded == s.files
                CourseFilter.PENDING -> s != null && s.downloaded < s.files
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenTitle("Kursus", "${all.size} kursus semester ini") }
        item {
            OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Cari kursus") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, "Hapus") } })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CourseFilter.entries.forEach { f -> FilterChip(filter == f, { onFilter(f) }, { Text(f.label) }, shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    border = FilterChipDefaults.filterChipBorder(f == filter || true, filter == f, borderColor = MaterialTheme.colorScheme.outlineVariant, selectedBorderColor = MaterialTheme.colorScheme.primaryContainer)) }
            }
        }
        if (snapshot == null && busy) items(3) { Skeleton(Modifier.fillMaxWidth().height(170.dp)) }
        else if (shown.isEmpty()) item {
            EmptyState(Icons.Rounded.School, if (all.isEmpty()) "Belum ada kursus" else "Tidak ada hasil", if (all.isEmpty()) "Tarik ke bawah atau ketuk sinkron untuk memuat kursus." else "Coba kata kunci atau filter lain.")
        }
        items(shown, key = { it.id }) { c -> CourseCard(c, stats[c.id] ?: Repo.CourseStats(0, 0, 0, 0)) { onOpen(c.id) } }
    }
}

@Composable
fun CourseCard(c: Course, st: Repo.CourseStats, onClick: () -> Unit) {
    val (title, term) = Logic.splitTerm(c.name)
    Card2(Modifier.fillMaxWidth(), onClick) {
        CourseBanner(c.name, c.id, height = 76.dp) { if (term != null) Pill(term, Color.White.copy(0.25f), Color.White, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(buildString {
                    append(c.items.size).append(" materi")
                    if (st.files > 0) append(" · ").append(st.downloaded).append('/').append(st.files).append(" file offline")
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (st.files > 0) {
                val done = st.downloaded == st.files
                ProgressRing(st.downloaded.toFloat() / st.files, 40.dp, 4.dp, MaterialTheme.colorScheme.secondary) {
                    Icon(if (done) Icons.Rounded.CloudDone else Icons.Rounded.CloudDownload, null, Modifier.size(18.dp), tint = if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

// =====================================================================================
// TENGGAT
// =====================================================================================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DeadlinesScreen(rows: List<DeadlineRow>, doneIds: Set<Long>, nowSec: Long, showDone: Boolean, onShowDone: (Boolean) -> Unit,
                    onToggleDone: (Long) -> Unit, onOpenCourse: (Long) -> Unit) {
    val visible = remember(rows, doneIds, showDone, nowSec) {
        rows.filter { r ->
            val due = r.item.dueAt!!; val done = r.item.id in doneIds
            (showDone || !done) && (due >= nowSec || due > nowSec - 14 * 86400)     // yang terlambat > 14 hari disembunyikan
        }
    }
    val groups = remember(visible, nowSec) { visible.groupBy { Logic.bucket(it.item.dueAt!!, nowSec) }.toSortedMap() }
    val pending = rows.count { it.item.id !in doneIds && it.item.dueAt!! >= nowSec }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenTitle("Tenggat", if (pending == 0) "Tidak ada tugas menunggu" else "$pending tugas menunggu")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tampilkan yang selesai", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(showDone, onShowDone)
                }
            }
        }
        if (groups.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Semua beres", "Tidak ada tugas dengan tenggat dalam waktu dekat.") }
        groups.forEach { (bucket, list) ->
            stickyHeader(key = "h_${bucket.name}") {
                Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(bucket.label, style = MaterialTheme.typography.titleSmall, color = if (bucket == Bucket.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        Pill("${list.size}", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(list, key = { it.item.id }) { r -> DeadlineCard(r, r.item.id in doneIds, nowSec, { onToggleDone(r.item.id) }, { onOpenCourse(r.course.id) }) }
        }
    }
}

@Composable
private fun DeadlineCard(r: DeadlineRow, done: Boolean, nowSec: Long, onToggle: () -> Unit, onOpen: () -> Unit) {
    val due = r.item.dueAt!!
    val urg = Logic.urgency(due, nowSec)
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when { done -> cs.surfaceVariant to cs.onSurfaceVariant; urg == Urgency.HIGH -> cs.errorContainer to cs.onErrorContainer; urg == Urgency.MEDIUM -> cs.primaryContainer to cs.onPrimaryContainer; else -> cs.surfaceVariant to cs.onSurfaceVariant }
    Card2(Modifier.fillMaxWidth().padding(horizontal = 20.dp), onOpen) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(end = 12.dp).width(4.dp).fillMaxHeight().clip(CircleShape).background(Palette.course(r.course.id).second))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (done) cs.onSurfaceVariant else cs.onSurface, textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                    Text(Logic.splitTerm(r.course.name).first, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(if (done) "Selesai" else Logic.relative(due, nowSec), bg, fg, if (done) Icons.Rounded.Check else Icons.Rounded.Schedule)
                        Text(fmtDay(due), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                IconButton(onToggle) { Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (done) "Tandai belum selesai" else "Tandai selesai", tint = if (done) cs.secondary else cs.outline) }
            }
        }
    }
}

// =====================================================================================
// NILAI
// =====================================================================================
@Composable
fun GradesScreen(courses: List<Course>) {
    val rows = remember(courses) { courses.map { it to Logic.gradeSummary(it) }.sortedByDescending { it.second.percent != null } }
    var open by remember { mutableStateOf(setOf<Long>()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Nilai", "Hanya item yang sudah dinilai") }
        if (rows.isEmpty()) item { EmptyState(Icons.Rounded.Insights, "Belum ada nilai", "Nilai muncul setelah sinkron. Beberapa kampus menyembunyikan nilai dari aplikasi.") }
        items(rows, key = { it.first.id }) { (c, g) ->
            val expanded = c.id in open && g.rows.isNotEmpty()
            Card2(Modifier.fillMaxWidth(), if (g.rows.isNotEmpty()) ({ open = if (c.id in open) open - c.id else open + c.id }) else null) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val pct = g.percent
                    ProgressRing((pct ?: 0.0).toFloat() / 100f, 56.dp, 6.dp, if (pct != null) gradeColor(pct) else MaterialTheme.colorScheme.outline) {
                        Text(if (pct != null) "${pct.toInt()}" else "–", style = MaterialTheme.typography.titleSmall)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Logic.splitTerm(c.name).first, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (g.rows.isEmpty()) "Belum ada nilai" else "${g.graded} dari ${g.total} item bernilai", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (g.rows.isNotEmpty()) Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.outline)
                }
                AnimatedVisibility(expanded) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        g.rows.forEach { r ->
                            val p = if ((r.max ?: 0.0) > 0) r.grade!! / r.max!! * 100 else null
                            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("%.0f".format(Locale.US, r.grade) + (r.max?.let { " / %.0f".format(Locale.US, it) } ?: ""), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (p != null) { Spacer(Modifier.width(8.dp)); Pill("${p.toInt()}%", gradeColor(p).copy(alpha = 0.15f), gradeColor(p)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
