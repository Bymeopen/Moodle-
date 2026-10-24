package id.pribadi.minilms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Foto keadaan AI untuk UI; dibangun dari [AiController] sehingga layar bisa dirender tanpa ViewModel. */
data class AiState(
    val hasKey: Boolean, val provider: Provider, val keys: Set<Provider>, val model: String, val mode: AiMode, val courses: List<Course>, val courseId: Long?, val socratic: Boolean,
    val chat: List<ChatMsg>, val busy: Boolean, val error: String?, val summary: String, val quiz: List<Quiz>, val draft: List<Draft>,
    val cards: List<Card>, val pdfs: List<PdfSource>, val selected: Set<String>, val sessionUsage: Usage, val suggestions: List<String>,
    val agent: Boolean = false,
)

class AiActions(
    val saveKey: (String) -> Unit = {}, val setProvider: (Provider) -> Unit = {}, val openLink: (String) -> Unit = {}, val selectCourse: (Long?) -> Unit = {}, val setMode: (AiMode) -> Unit = {}, val send: (String) -> Unit = {},
    val stop: () -> Unit = {}, val newChat: () -> Unit = {}, val setSocratic: (Boolean) -> Unit = {}, val retry: () -> Unit = {},
    val summarize: () -> Unit = {}, val makeQuiz: () -> Unit = {}, val makeCards: () -> Unit = {}, val saveDraft: () -> Unit = {}, val discardDraft: () -> Unit = {},
    val togglePdf: (String) -> Unit = {}, val deleteCard: (String) -> Unit = {}, val review: () -> Unit = {},
    val setAgent: (Boolean) -> Unit = {},
)

@Composable
fun AiScreen(vm: MainViewModel, onReview: () -> Unit) {
    val ai = vm.ai
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val hasKey by ai.hasKey.collectAsStateWithLifecycle(); val model by ai.model.collectAsStateWithLifecycle()
    val provider by ai.provider.collectAsStateWithLifecycle(); val keys by ai.keys.collectAsStateWithLifecycle()
    val mode by ai.mode.collectAsStateWithLifecycle(); val cid by ai.courseId.collectAsStateWithLifecycle(); val soc by ai.socratic.collectAsStateWithLifecycle(); val agentOn by ai.agent.collectAsStateWithLifecycle()
    val chat by ai.chat.collectAsStateWithLifecycle(); val busy by ai.busy.collectAsStateWithLifecycle(); val err by ai.error.collectAsStateWithLifecycle()
    val summary by ai.summary.collectAsStateWithLifecycle(); val quiz by ai.quiz.collectAsStateWithLifecycle(); val draft by ai.draft.collectAsStateWithLifecycle()
    val sel by ai.selected.collectAsStateWithLifecycle(); val usage by ai.sessionUsage.collectAsStateWithLifecycle()
    val courses = snap?.courses.orEmpty()
    val course = courses.firstOrNull { it.id == cid }
    val pdfs = remember(course, tick) { course?.let { ai.pdfsOf(it) }.orEmpty() }
    val state = AiState(hasKey, provider, keys, model, mode, courses, cid, soc, chat, busy, err, summary, quiz, draft, cards, pdfs, sel, usage, ai.suggestions(course != null), agentOn)
    val actions = remember(ai) {
        AiActions({ ai.setKey(it) }, ai::setProvider, vm::openLink, ai::selectCourse, { ai.mode.value = it }, ai::send, ai::stop, ai::newChat, { ai.socratic.value = it }, ai::retry,
            ai::summarize, { ai.makeQuiz() }, { ai.makeCards() }, ai::saveDraft, ai::discardDraft, ai::toggle, vm::deleteCard, onReview, ai::setAgent)
    }
    AiContent(state, actions)
}

@Composable
fun AiContent(s: AiState, a: AiActions) {
    if (!s.hasKey) { KeySetup(s.provider, a); return }
    val course = s.courses.firstOrNull { it.id == s.courseId }
    Column(Modifier.fillMaxSize().imePadding()) {
        AiHeader(s, course, a)
        Box(Modifier.weight(1f)) {
            when (s.mode) {
                AiMode.CHAT -> ChatPane(s, a)
                AiMode.SUMMARY -> SummaryPane(s, course, a)
                AiMode.QUIZ -> QuizPane(s, course, a)
                AiMode.CARDS -> CardsPane(s, course, a)
            }
        }
    }
}

// ------------------------------------------------------------------------------------- header
@Composable
private fun AiHeader(s: AiState, course: Course?, a: AiActions) {
    val cs = MaterialTheme.colorScheme
    var courseMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var pdfOpen by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(providerColors(s.provider))), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(22.dp), tint = Color.White)
            }
            Box(Modifier.weight(1f)) {
                var providerMenu by remember { mutableStateOf(false) }
                Column(Modifier.clip(MaterialTheme.shapes.small).clickable { providerMenu = true }.padding(vertical = 2.dp, horizontal = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.provider.label.substringAfter(' '), style = MaterialTheme.typography.titleLarge); Icon(Icons.Rounded.UnfoldMore, "Ganti penyedia AI", Modifier.size(20.dp), tint = cs.outline)
                    }
                    Text(Models.label(s.model), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                DropdownMenu(providerMenu, { providerMenu = false }) {
                    Provider.entries.forEach { p ->
                        DropdownMenuItem({ Column { Text(p.label); if (p !in s.keys) Text("Kunci belum diisi", style = MaterialTheme.typography.labelSmall, color = cs.outline) } },
                            { providerMenu = false; a.setProvider(p) }, trailingIcon = { if (p == s.provider) Icon(Icons.Rounded.Check, null, tint = cs.primary) })
                    }
                }
            }
            Box {
                IconButton({ more = true }) { Icon(Icons.Rounded.MoreVert, "Menu") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem({ Text("Percakapan baru") }, { more = false; a.newChat() }, leadingIcon = { Icon(Icons.Rounded.AddComment, null) })
                    DropdownMenuItem({ Text("Agen LMS") }, { a.setAgent(!s.agent) }, leadingIcon = { Icon(Icons.Rounded.SmartToy, null) },
                        trailingIcon = { Switch(s.agent, { a.setAgent(it) }, Modifier.height(24.dp)) })
                    DropdownMenuItem({ Text("Mode Sokrates") }, { a.setSocratic(!s.socratic) }, leadingIcon = { Icon(Icons.Rounded.Psychology, null) },
                        trailingIcon = { Switch(s.socratic, { a.setSocratic(it) }, Modifier.height(24.dp)) })
                }
            }
        }
        // kursus sebagai konteks
        Box {
            Surface({ courseMenu = true }, shape = CircleShape, color = cs.surfaceContainerLowest, border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.School, null, Modifier.size(18.dp), tint = cs.primary)
                    Text(course?.let { Logic.splitTerm(it.name).first } ?: "Tanpa kursus (chat umum)", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = cs.outline)
                }
            }
            DropdownMenu(courseMenu, { courseMenu = false }) {
                DropdownMenuItem({ Text("Tanpa kursus (chat umum)") }, { a.selectCourse(null); courseMenu = false })
                s.courses.forEach { c -> DropdownMenuItem({ Text(Logic.splitTerm(c.name).first, maxLines = 2) }, { a.selectCourse(c.id); courseMenu = false }) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val icons = mapOf(AiMode.CHAT to Icons.Rounded.ChatBubbleOutline, AiMode.SUMMARY to Icons.Rounded.Summarize, AiMode.QUIZ to Icons.Rounded.Quiz, AiMode.CARDS to Icons.Rounded.Style)
            AiMode.entries.forEach { m ->
                FilterChip(s.mode == m, { a.setMode(m) }, { Text(m.label) }, shape = CircleShape, leadingIcon = { Icon(icons.getValue(m), null, Modifier.size(18.dp)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = cs.tertiaryContainer, selectedLabelColor = cs.onTertiaryContainer, selectedLeadingIconColor = cs.tertiary),
                    border = FilterChipDefaults.filterChipBorder(true, s.mode == m, borderColor = cs.outlineVariant, selectedBorderColor = cs.tertiaryContainer))
            }
        }
        if (s.agent && s.mode == AiMode.CHAT) Pill("Agen LMS: bisa membaca & mengendalikan aplikasi", cs.secondaryContainer, cs.onSecondaryContainer, Icons.Rounded.SmartToy)
        if (s.socratic && s.mode == AiMode.CHAT) Pill("Mode Sokrates aktif", cs.tertiaryContainer, cs.onTertiaryContainer, Icons.Rounded.Psychology)
        if (course != null && s.pdfs.isNotEmpty()) {
            Surface({ pdfOpen = !pdfOpen }, shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLow) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp), tint = cs.tertiary)
                        Text("Lampirkan PDF (${s.selected.size}/${s.pdfs.size})", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Icon(if (pdfOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = cs.outline)
                    }
                    AnimatedVisibility(pdfOpen) {
                        Column {
                            Text("${s.provider.label.substringAfter(' ')} membaca PDF langsung (termasuk gambar/tabel). ${if (s.provider == Provider.GEMINI) "±258 token per halaman" else "±1.500–3.000 token per halaman"}. Maks ${Models.maxPdfBytes(s.provider) / 1048576} MB. Berlaku untuk percakapan baru dan fitur lain.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                            s.pdfs.forEach { p -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(p.key in s.selected, { a.togglePdf(p.key) }); FileTile(FileKind.PDF, 28.dp); Spacer(Modifier.width(8.dp))
                                Text("${p.name} · ${Logic.fmtSize(p.size)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            } }
                            if (s.pdfs.filter { it.key in s.selected }.sumOf { it.size } > Models.maxPdfBytes(s.provider)) Text("Total melebihi ${Models.maxPdfBytes(s.provider) / 1048576} MB.", color = cs.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = cs.tertiary, trackColor = cs.tertiaryContainer)
        s.error?.let { InfoBanner(it, tone = Tone.ERROR, icon = Icons.Rounded.ErrorOutline, action = if (s.mode == AiMode.CHAT) "Coba lagi" else null, onAction = a.retry) }
    }
}

// ------------------------------------------------------------------------------------- chat
@Composable
private fun ChatPane(s: AiState, a: AiActions) {
    val list = rememberLazyListState()
    val course = s.courses.firstOrNull { it.id == s.courseId }
    LaunchedEffect(s.chat.size, s.chat.lastOrNull()?.text?.length) { if (s.chat.isNotEmpty()) list.animateScrollToItem(s.chat.size - 1) }
    Column(Modifier.fillMaxSize()) {
        if (s.chat.isEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer(Modifier.height(8.dp))
                Text(if (course == null) "Mau belajar apa hari ini?" else "Tanyakan apa saja tentang kursus ini", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(if (course == null) "Chat umum tanpa materi kursus." else "Jawaban memakai materi kursus yang sudah Anda unduh.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                s.suggestions.forEach { q ->
                    Card2(Modifier.fillMaxWidth(), { a.send(q) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                            Text(q, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(s.chat.size) { i -> Message(s.chat[i], s.model, s.busy && i == s.chat.lastIndex) }
                if (!s.sessionUsage.isEmpty) item { Text("Sesi ini " + s.sessionUsage.label(s.model), Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center) }
            }
        }
        ChatInput(s.busy, a)
    }
}

@Composable
private fun Message(m: ChatMsg, model: String, streaming: Boolean) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    if (m.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(Modifier.widthIn(max = 320.dp), shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), color = cs.primary) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (m.docs.isNotEmpty()) Text("📎 ${m.docs.size} PDF terlampir", style = MaterialTheme.typography.labelSmall, color = cs.onPrimary.copy(0.85f))
                    Text(m.text, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimary)
                }
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(cs.tertiaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = cs.tertiary) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.text.isEmpty()) { if (streaming) TypingDots() } else MarkdownText(m.text)
                if (!streaming && m.text.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.usage?.label(model) ?: "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ clip.setText(AnnotatedString(m.text)) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.ContentCopy, "Salin jawaban", Modifier.size(16.dp), tint = cs.outline) }
                }
            }
        }
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = a)))
        }
    }
}

@Composable
private fun ChatInput(busy: Boolean, a: AiActions) {
    val cs = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
        Row(Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Tulis pertanyaan…") }, maxLines = 5,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            if (busy) FilledIconButton(a.stop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.error)) { Icon(Icons.Rounded.Stop, "Berhenti") }
            else FilledIconButton({ a.send(text); text = "" }, enabled = text.isNotBlank(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = cs.tertiary)) { Icon(Icons.Rounded.Send, "Kirim") }
        }
    }
}

// ------------------------------------------------------------------------------------- ringkas
@Composable
private fun NeedCourse(icon: androidx.compose.ui.graphics.vector.ImageVector, what: String) =
    EmptyState(icon, "Pilih kursus dulu", "$what memakai materi sebuah kursus. Pilih kursus di atas.")

@Composable
private fun SummaryPane(s: AiState, course: Course?, a: AiActions) {
    val clip = LocalClipboardManager.current
    if (course == null) { NeedCourse(Icons.Rounded.Summarize, "Ringkasan"); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.summary.isEmpty() && !s.busy) {
            EmptyState(Icons.Rounded.Summarize, "Ringkas kursus ini", "AI membuat poin kunci, istilah penting, dan daftar yang perlu dihafal dari materi.", actionLabel = "Buat ringkasan", onAction = a.summarize)
        } else {
            Card2(Modifier.fillMaxWidth()) { MarkdownText(s.summary.ifEmpty { "…" }, Modifier.padding(16.dp)) }
            if (!s.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ clip.setText(AnnotatedString(s.summary)) }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Salin") }
                OutlinedButton(a.summarize) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Buat ulang") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ------------------------------------------------------------------------------------- soal
@Composable
private fun QuizPane(s: AiState, course: Course?, a: AiActions) {
    if (course == null) { NeedCourse(Icons.Rounded.Quiz, "Soal latihan"); return }
    val cs = MaterialTheme.colorScheme
    var answers by remember(s.quiz) { mutableStateOf(mapOf<Int, Int>()) }
    var checked by remember(s.quiz) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.quiz.isEmpty() && !s.busy) {
            EmptyState(Icons.Rounded.Quiz, "Uji pemahaman Anda", "AI membuat 5 soal pilihan ganda dari materi, lengkap dengan penjelasan jawaban.", actionLabel = "Buat soal", onAction = a.makeQuiz)
        }
        if (checked) {
            val score = s.quiz.indices.count { answers[it] == s.quiz[it].correct }
            Card2(Modifier.fillMaxWidth(), color = cs.secondaryContainer, border = false) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProgressRing(score.toFloat() / s.quiz.size, 64.dp, 7.dp, cs.secondary, cs.surface.copy(alpha = 0.5f)) { Text("$score/${s.quiz.size}", style = MaterialTheme.typography.titleMedium) }
                    Column {
                        Text(when { score == s.quiz.size -> "Sempurna! 🎉"; score * 2 >= s.quiz.size -> "Bagus, terus latih"; else -> "Ayo ulangi materinya" }, style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
                        Text("Baca penjelasan di tiap soal.", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                    }
                }
            }
        }
        s.quiz.forEachIndexed { qi, q ->
            Card2(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("${qi + 1}", cs.tertiaryContainer, cs.onTertiaryContainer)
                        Text(q.text, style = MaterialTheme.typography.titleSmall)
                    }
                    q.options.forEachIndexed { oi, o ->
                        val picked = answers[qi] == oi
                        val (bg, fg, icon) = when {
                            checked && oi == q.correct -> Triple(cs.secondaryContainer, cs.onSecondaryContainer, Icons.Rounded.CheckCircle)
                            checked && picked -> Triple(cs.errorContainer, cs.onErrorContainer, Icons.Rounded.Cancel)
                            picked -> Triple(cs.tertiaryContainer, cs.onTertiaryContainer, Icons.Rounded.RadioButtonChecked)
                            else -> Triple(cs.surfaceContainerLow, cs.onSurface, Icons.Rounded.RadioButtonUnchecked)
                        }
                        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(bg).clickable(enabled = !checked) { answers = answers + (qi to oi) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(icon, null, Modifier.size(20.dp), tint = fg); Text(o, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = fg)
                        }
                    }
                    if (checked && q.explanation.isNotBlank()) Surface(shape = MaterialTheme.shapes.medium, color = cs.tertiaryContainer.copy(alpha = 0.6f)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Lightbulb, null, Modifier.size(18.dp), tint = cs.tertiary)
                            Text(q.explanation, style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
                        }
                    }
                }
            }
        }
        if (s.quiz.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!checked) Button({ checked = true }, enabled = answers.size == s.quiz.size && !s.busy) { Text(if (answers.size == s.quiz.size) "Periksa jawaban" else "Jawab semua (${answers.size}/${s.quiz.size})") }
            OutlinedButton(a.makeQuiz, enabled = !s.busy) { Text("Soal baru") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ------------------------------------------------------------------------------------- kartu
@Composable
private fun CardsPane(s: AiState, course: Course?, a: AiActions) {
    val cs = MaterialTheme.colorScheme
    val due = s.cards.count { Srs.isDue(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card2(Modifier.fillMaxWidth(), color = cs.secondaryContainer, border = false) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(Icons.Rounded.Style, cs.secondary, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text("${s.cards.size} kartu", style = MaterialTheme.typography.titleMedium, color = cs.onSecondaryContainer)
                    Text(if (due > 0) "$due jatuh tempo hari ini" else "Tidak ada yang jatuh tempo", style = MaterialTheme.typography.bodySmall, color = cs.onSecondaryContainer)
                }
                Button(a.review, enabled = due > 0, colors = ButtonDefaults.buttonColors(containerColor = cs.secondary)) { Text("Review") }
            }
        }
        if (course == null) NeedCourse(Icons.Rounded.Style, "Membuat kartu") else {
            OutlinedButton(a.makeCards, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Buat 10 kartu dengan AI") }
            if (s.draft.isNotEmpty()) {
                Text("Draf — tinjau dulu, belum tersimpan", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                s.draft.forEach { d -> MiniCard(d.front, d.back) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(a.saveDraft) { Text("Simpan ${s.draft.size} kartu") }; OutlinedButton(a.discardDraft) { Text("Buang") } }
            }
            val mine = s.cards.filter { it.courseId == course.id }
            if (mine.isNotEmpty()) {
                Text("Kartu kursus ini (${mine.size})", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                mine.forEach { k -> MiniCard(k.front, k.back, onDelete = { a.deleteCard(k.id) }) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun MiniCard(front: String, back: String, onDelete: (() -> Unit)? = null) =
    Card2(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = if (onDelete != null) 4.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(front, style = MaterialTheme.typography.titleSmall); Text(back, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) IconButton(onDelete) { Icon(Icons.Rounded.DeleteOutline, "Hapus kartu", tint = MaterialTheme.colorScheme.outline) }
        }
    }

// ------------------------------------------------------------------------------------- kunci API
fun providerColors(p: Provider) = if (p == Provider.GEMINI) listOf(Color(0xFF4285F4), Color(0xFF9B72CB)) else listOf(Color(0xFF8B7BFF), Color(0xFF5B3FD9))

@Composable
private fun KeySetup(p: Provider, a: AiActions) {
    var key by rememberSaveable(p) { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { x -> FilterChip(p == x, { a.setProvider(x) }, { Text(x.label) }, shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = cs.tertiaryContainer, selectedLabelColor = cs.onTertiaryContainer)) }
        }
        Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(providerColors(p))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(30.dp), tint = Color.White) }
        Text(if (p == Provider.GEMINI) "Belajar bersama Gemini" else "Belajar bersama Claude", style = MaterialTheme.typography.headlineMedium)
        Text("Tanya jawab, ringkasan, soal latihan berpenjelasan, dan kartu hafalan — semuanya dari materi kursus Anda.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        Card2(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Kunci API ${p.label.substringAfter(' ')}", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(if (p == Provider.GEMINI) "AIza…" else "sk-ant-…") }, visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Rounded.Key, null) })
                Button({ a.saveKey(key); key = "" }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Simpan kunci") }
                OutlinedButton({ a.openLink(p.keyUrl) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (p == Provider.GEMINI) "Buat kunci gratis di Google AI Studio" else "Buat kunci di console.anthropic.com") }
            }
        }
        if (p == Provider.GEMINI) InfoBanner("Tingkat gratis: isi permintaan (materi, pertanyaan) dapat dipakai Google untuk memperbaiki produknya. Jangan kirim data pribadi; kunci berbayar tidak begitu.", tone = Tone.WARN, icon = Icons.Rounded.Info)
        val points = if (p == Provider.GEMINI) listOf(
            Icons.Rounded.Lock to "Kunci disimpan terenkripsi di ponsel (Android Keystore) dan hanya dikirim ke generativelanguage.googleapis.com.",
            Icons.Rounded.Payments to "Model Flash dan Flash-Lite GRATIS (dibatasi per menit/hari). Jika batas tercapai, tunggu sebentar atau pilih Flash-Lite.",
            Icons.Rounded.PictureAsPdf to "PDF terpilih dibaca langsung oleh Gemini; teks halaman dan docx/pptx ikut sebagai konteks.",
            Icons.Rounded.Key to "Google tidak menyediakan login akun Google untuk aplikasi lain; aksesnya lewat kunci API.")
        else listOf(
            Icons.Rounded.Lock to "Kunci disimpan terenkripsi di ponsel (Android Keystore) dan hanya dikirim ke api.anthropic.com.",
            Icons.Rounded.Payments to "Claude API BERBAYAR (tidak ada tingkat gratis; butuh saldo di console.anthropic.com). Jika tidak ingin membayar, pakai Gemini.",
            Icons.Rounded.PictureAsPdf to "PDF terpilih dibaca langsung oleh Claude; teks halaman dan docx/pptx ikut sebagai konteks.",
            Icons.Rounded.Key to "Claude tidak punya login SSO/Google untuk aplikasi lain; aksesnya lewat kunci API.")
        points.forEach { (i, t) -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(i, null, Modifier.size(20.dp), tint = cs.tertiary); Text(t, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) } }
    }
}

// ------------------------------------------------------------------------------------- review kartu
@Composable
fun ReviewScreen(vm: MainViewModel, onBack: () -> Unit) {
    val initial = remember { vm.repo.cards.value.filter { Srs.isDue(it) } }
    ReviewContent(initial, onRate = vm::rateCard, onClose = onBack)
}

@Composable
fun ReviewContent(initial: List<Card>, onRate: (Card, String) -> Card, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val queue = remember { mutableStateListOf<Card>().apply { addAll(initial) } }
    var flipped by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    val total = initial.size
    val rot by animateFloatAsState(if (flipped) 180f else 0f, tween(380), label = "flip")
    val density = LocalDensity.current.density

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Tutup") }
            ProgressBar(if (total == 0) 1f else done.toFloat() / (done + queue.size).coerceAtLeast(1), Modifier.weight(1f), cs.secondary, height = 8.dp)
            Text("${queue.size} sisa", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        val card = queue.firstOrNull()
        if (card == null) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.Rounded.EmojiEvents, if (total == 0) "Tidak ada kartu hari ini" else "Selesai! 🎉", if (total == 0) "Buat kartu dari materi di tab AI." else "$done kartu direview. Sampai jumpa besok!", actionLabel = "Kembali", onAction = onClose)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
              Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 460.dp).graphicsLayer { rotationY = rot; cameraDistance = 14f * density }.clickable { flipped = !flipped }) {
                Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge, color = if (rot <= 90f) cs.surfaceContainerLowest else cs.tertiaryContainer,
                    border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = if (rot > 90f) 180f else 0f }.padding(28.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (rot <= 90f) "PERTANYAAN" else "JAWABAN", style = MaterialTheme.typography.labelSmall, color = if (rot <= 90f) cs.outline else cs.tertiary)
                            Text(if (rot <= 90f) card.front else card.back, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                                color = if (rot <= 90f) cs.onSurface else cs.onTertiaryContainer)
                            if (rot <= 90f) Text("Ketuk untuk membalik", style = MaterialTheme.typography.bodySmall, color = cs.outline)
                        }
                    }
                }
              }
            }
            if (flipped) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("again", "Lupa", cs.error), Triple("hard", "Sulit", Color(0xFFD98A06)), Triple("good", "Bisa", cs.secondary), Triple("easy", "Mudah", cs.primary)).forEach { (r, label, color) ->
                    Button({
                        val next = onRate(card, r); queue.removeAt(0); done++; flipped = false
                        if (r == "again") queue.add(next)               // lupa: muncul lagi di sesi ini
                    }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label, fontWeight = FontWeight.SemiBold); Text(Srs.preview(card, r), style = MaterialTheme.typography.labelSmall) }
                    }
                }
            } else Button({ flipped = true }, Modifier.fillMaxWidth().height(52.dp)) { Text("Tampilkan jawaban") }
        }
    }
}
