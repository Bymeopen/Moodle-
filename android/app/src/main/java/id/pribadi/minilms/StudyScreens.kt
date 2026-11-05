package id.pribadi.minilms

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.format.DateTimeFormatter
import java.util.Locale

// =====================================================================================
// PENCARIAN DI SEMUA MATERI
// =====================================================================================

@Composable
fun SearchScreen(vm: MainViewModel, onOpenCourse: (Long) -> Unit, onBack: () -> Unit) {
    val st by vm.search.collectAsStateWithLifecycle()
    SearchContent(st, vm::searchFor, onOpenCourse, onBack)
}

@Composable
fun SearchContent(st: MainViewModel.SearchState, onQuery: (String) -> Unit, onOpenCourse: (Long) -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        BackBar("Cari di semua materi", onBack)
        OutlinedTextField(st.query, onQuery, Modifier.fillMaxWidth().padding(horizontal = 20.dp).focusRequester(focus), singleLine = true,
            placeholder = { Text("Kata kunci: materi, tugas, forum, tes, file, catatan") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (st.query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, "Hapus") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { fm.clearFocus() }))
        Text("Berjalan offline di data yang tersimpan; isi file docx/pptx/txt ikut dicari bila sudah diunduh.", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        when {
            st.query.trim().length < Search.MIN_QUERY -> EmptyState(Icons.Rounded.Search, "Cari apa saja", "Ketik minimal ${Search.MIN_QUERY} huruf. Semua kata harus ada; huruf besar/kecil tidak berpengaruh.")
            st.searching && st.hits.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
            st.hits.isEmpty() -> EmptyState(Icons.Rounded.SearchOff, "Tidak ditemukan", "Coba kata lain, atau sinkron dan unduh file kursus agar isinya ikut dicari.")
            else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("${st.hits.size} hasil", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant) }
                items(st.hits) { h ->
                    Card2(Modifier.fillMaxWidth(), { onOpenCourse(h.courseId) }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill(h.kind.label, kindColor(h.kind).copy(alpha = 0.15f), kindColor(h.kind))
                                Text(h.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (h.snippet.isNotBlank()) Text(h.snippet, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (h.course.isNotEmpty()) Text(Logic.splitTerm(h.course).first, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun kindColor(k: HitKind) = when (k) {
    HitKind.TUGAS, HitKind.TES -> MaterialTheme.colorScheme.error
    HitKind.FORUM -> MaterialTheme.colorScheme.tertiary
    HitKind.FILE -> MaterialTheme.colorScheme.secondary
    HitKind.CATATAN, HitKind.KARTU -> MaterialTheme.colorScheme.secondary
    HitKind.MATERI -> MaterialTheme.colorScheme.primary
}

// =====================================================================================
// RENCANA BELAJAR
// =====================================================================================

@Composable
fun PlannerScreen(vm: MainViewModel, onReview: () -> Unit, onDeadlines: () -> Unit, onBack: () -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val done by vm.doneAssignments.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val cfg by vm.planCfg.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val nowSec by rememberNowSec(tick)
    val dueCards = remember(cards) { cards.count { Srs.isDue(it) } }
    val plan = remember(snap, done, dueCards, cfg.minutes, nowSec / 60) { Planner.plan(Planner.tasksOf(snap?.courses.orEmpty(), done, nowSec), nowSec, cfg.minutes, dueCards) }
    PlannerContent(plan, cfg, dueCards, nowSec, vm::setPlanMinutes, vm::setPlanHour, vm::setPlanReminder, onReview, onDeadlines, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlannerContent(plan: StudyPlan, cfg: MainViewModel.PlanCfg, dueCards: Int, nowSec: Long, onMinutes: (Int) -> Unit, onHour: (Int) -> Unit, onReminder: (Boolean) -> Unit,
                   onReview: () -> Unit, onDeadlines: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE, d MMM", Locale("id")) }
    val busy = plan.days.count { it.total > 0 }
    Column(Modifier.fillMaxSize()) {
        BackBar("Rencana belajar", onBack)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Disusun otomatis dari tenggat dan beban tugas. Perkiraan waktu hanya untuk membagi hari; tidak mengubah apa pun di Moodle.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            if (plan.overdue.isNotEmpty()) item {
                InfoBanner("${plan.overdue.size} tugas terlambat dan belum ditandai selesai: " + plan.overdue.take(3).joinToString("; ") { it.title } + if (plan.overdue.size > 3) "…" else "",
                    tone = Tone.ERROR, icon = Icons.Rounded.ErrorOutline, action = "Lihat tenggat", onAction = onDeadlines)
            }
            if (plan.atRisk.isNotEmpty()) item {
                InfoBanner("Tidak muat dalam ${Planner.fmtMinutes(cfg.minutes)} per hari sebelum tenggat: " + plan.atRisk.take(3).joinToString("; ") { it.title } + ". Naikkan menit belajar atau mulai lebih awal.",
                    tone = Tone.WARN, icon = Icons.Rounded.WarningAmber)
            }
            if (dueCards > 0) item {
                Card2(Modifier.fillMaxWidth(), onReview) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Rounded.Style, cs.secondary)
                        Column(Modifier.weight(1f)) {
                            Text("$dueCards kartu hafalan jatuh tempo", style = MaterialTheme.typography.titleSmall)
                            Text("Sekitar ${Planner.fmtMinutes(minOf(20, (dueCards + 1) / 2))} — sudah dihitung di rencana hari ini", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = cs.outline)
                    }
                }
            }
            if (busy == 0 && plan.overdue.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Minggu ini lega", "Tidak ada tugas bertenggat dalam ${plan.horizon} hari ke depan yang perlu dijadwalkan.") }
            items(plan.days.filter { it.total > 0 }) { day ->
                Card2(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((if (day == plan.days.first()) "Hari ini · " else "") + day.date.format(fmt).replaceFirstChar { it.uppercase() }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Pill(Planner.fmtMinutes(day.total), cs.primaryContainer, cs.onPrimaryContainer, Icons.Rounded.Timer)
                        }
                        day.sessions.forEach { s ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Assignment, null, Modifier.size(20.dp).padding(top = 2.dp), tint = cs.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(s.course + " · tenggat " + Logic.relative(s.dueAt, nowSec), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(Planner.fmtMinutes(s.minutes), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                            }
                        }
                        if (day.cardMinutes > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.Style, null, Modifier.size(20.dp), tint = cs.secondary)
                            Text("Kartu hafalan", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(Planner.fmtMinutes(day.cardMinutes), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Card2(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Pengaturan", style = MaterialTheme.typography.titleSmall)
                        Text("Menit belajar per hari", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Planner.DAILY_CHOICES.forEach { m -> FilterChip(cfg.minutes == m, { onMinutes(m) }, { Text(Planner.fmtMinutes(m)) }) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Pengingat harian", style = MaterialTheme.typography.bodyMedium)
                                Text("Notifikasi ringkasan rencana hari ini", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Switch(cfg.reminder, onReminder)
                        }
                        if (cfg.reminder) {
                            Text("Jam pengingat", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(6, 12, 17, 19, 21).forEach { h -> FilterChip(cfg.hour == h, { onHour(h) }, { Text("%02d.00".format(h)) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
// CATATAN & SOROTAN
// =====================================================================================

/** Teks materi dengan bagian yang disorot (cocok dengan kutipan catatan). */
@Composable
fun highlighted(content: String, quotes: List<String>): AnnotatedString {
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    return remember(content, quotes, bg) {
        val rs = Notes.ranges(content, quotes)
        if (rs.isEmpty()) AnnotatedString(content) else buildAnnotatedString {
            append(content)
            rs.forEach { addStyle(SpanStyle(background = bg), it.first, it.last + 1) }
        }
    }
}

/** Dialog tambah/ubah catatan: kutipan materi (opsional, bisa ditempel dari salinan) + catatan. */
@Composable
fun NoteDialog(itemTitle: String, initialQuote: String, initialText: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var quote by rememberSaveable { mutableStateOf(initialQuote) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    val clip = LocalClipboardManager.current
    AlertDialog(onDismiss, title = { Text(if (initialQuote.isEmpty() && initialText.isEmpty()) "Catatan baru" else "Ubah catatan") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(itemTitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedTextField(quote, { quote = it.take(Notes.MAX_QUOTE) }, Modifier.fillMaxWidth(), label = { Text("Bagian materi yang disorot (opsional)") }, minLines = 2, maxLines = 4,
                supportingText = { Text("Tekan lama teks materi → Salin, lalu Tempel. Bagian itu akan disorot di materi.") })
            TextButton({ clip.getText()?.text?.let { quote = it.take(Notes.MAX_QUOTE) } }) { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tempel dari salinan") }
            OutlinedTextField(text, { text = it.take(Notes.MAX_TEXT) }, Modifier.fillMaxWidth(), label = { Text("Catatan saya") }, minLines = 3, maxLines = 8)
        }
    }, confirmButton = { TextButton({ onSave(quote, text) }, enabled = quote.isNotBlank() || text.isNotBlank()) { Text("Simpan") } },
        dismissButton = { TextButton(onDismiss) { Text("Batal") } })
}

@Composable
fun NotesScreen(vm: MainViewModel, onOpenCourse: (Long) -> Unit, onBack: () -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    NotesContent(notes, snap?.courses.orEmpty(), vm::editNote, vm::deleteNote, vm::noteToCard, vm::shareNotes, onOpenCourse, onBack)
}

@Composable
fun NotesContent(notes: List<Note>, courses: List<Course>, onEdit: (Note, String, String) -> Unit, onDelete: (String) -> Unit, onToCard: (Note) -> Unit,
                 onShare: (Course) -> Unit, onOpenCourse: (Long) -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<Note?>(null) }
    var deleting by remember { mutableStateOf<Note?>(null) }
    val byCourse = remember(notes, courses) { notes.groupBy { it.courseId }.toList().sortedBy { (id, _) -> courses.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it } } }
    Column(Modifier.fillMaxSize()) {
        BackBar("Catatan saya", onBack)
        if (notes.isEmpty()) EmptyState(Icons.Rounded.EditNote, "Belum ada catatan", "Buka sebuah kursus, ketuk ikon catatan pada materi atau tugas, lalu tulis catatan atau sorot bagian penting.")
        else LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            byCourse.forEach { (courseId, list) ->
                val c = courses.firstOrNull { it.id == courseId }
                item(key = "c_$courseId") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c?.let { Logic.splitTerm(it.name).first } ?: "Kursus lain", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (c != null) IconButton({ onShare(c) }) { Icon(Icons.Rounded.Share, "Bagikan catatan kursus ini") }
                    }
                }
                items(list.sortedByDescending { it.createdAt }, key = { it.id }) { n ->
                    val title = c?.items?.firstOrNull { it.id == n.itemId }?.title ?: "Item"
                    Card2(Modifier.fillMaxWidth(), if (c != null) ({ onOpenCourse(courseId) }) else null) {
                        Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (n.quote.isNotEmpty()) Surface(shape = MaterialTheme.shapes.small, color = cs.tertiaryContainer) {
                                Text(n.quote, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                            if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton({ onToCard(n) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.Style, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Jadikan kartu") }
                                Spacer(Modifier.weight(1f))
                                IconButton({ editing = n }) { Icon(Icons.Rounded.Edit, "Ubah catatan") }
                                IconButton({ deleting = n }) { Icon(Icons.Rounded.DeleteOutline, "Hapus catatan") }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { n -> NoteDialog(courses.firstOrNull { it.id == n.courseId }?.items?.firstOrNull { it.id == n.itemId }?.title ?: "Item", n.quote, n.text, { q, t -> onEdit(n, q, t); editing = null }, { editing = null }) }
    deleting?.let { n -> AlertDialog({ deleting = null }, title = { Text("Hapus catatan ini?") }, text = { Text("Catatan hanya tersimpan di ponsel dan tidak bisa dikembalikan.") },
        confirmButton = { TextButton({ onDelete(n.id); deleting = null }) { Text("Hapus", color = cs.error) } }, dismissButton = { TextButton({ deleting = null }) { Text("Batal") } }) }
}

/** Catatan milik satu item, ditampilkan di bawah materi/tugas. */
@Composable
fun ItemNotes(notes: List<Note>, onDelete: (String) -> Unit, modifier: Modifier = Modifier) {
    if (notes.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEach { n ->
            Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLow) {
                Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.EditNote, null, Modifier.size(18.dp).padding(top = 2.dp), tint = cs.tertiary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (n.quote.isNotEmpty()) Text(n.quote, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton({ onDelete(n.id) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Hapus catatan", Modifier.size(16.dp), tint = cs.outline) }
                }
            }
        }
    }
}
