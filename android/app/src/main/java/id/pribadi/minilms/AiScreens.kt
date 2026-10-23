package id.pribadi.minilms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate

private enum class AiMode(val label: String) { CHAT("Chat"), SUMMARY("Ringkas"), QUIZ("Soal"), CARDS("Kartu") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTab(vm: MainViewModel, onReview: () -> Unit) {
    val ai = vm.ai
    val hasKey by ai.hasKey.collectAsStateWithLifecycle()
    if (!hasKey) { KeySetup(vm); return }

    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val busy by ai.busy.collectAsStateWithLifecycle()
    val error by ai.error.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(AiMode.CHAT) }
    var courseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    val courses = snap?.courses ?: emptyList()
    val selected = courses.firstOrNull { it.id == courseId }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Claude AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Box {
                OutlinedButton({ menu = true }, Modifier.fillMaxWidth()) { Text(selected?.name ?: "Tanpa kursus (chat umum)", maxLines = 1) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Tanpa kursus (chat umum)") }, { courseId = null; menu = false })
                    courses.forEach { c -> DropdownMenuItem({ Text(c.name, maxLines = 2) }, { courseId = c.id; menu = false }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiMode.entries.forEach { m -> FilterChip(mode == m, { mode = m }, { Text(m.label) }) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (mode != AiMode.CHAT && selected == null) Text("Pilih kursus untuk fitur ini.", style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.weight(1f)) {
            when (mode) {
                AiMode.CHAT -> ChatPane(vm, courseId)
                AiMode.SUMMARY -> SummaryPane(vm, selected)
                AiMode.QUIZ -> QuizPane(vm, selected)
                AiMode.CARDS -> CardsPane(vm, selected, onReview)
            }
        }
    }
}

@Composable
private fun KeySetup(vm: MainViewModel) {
    var key by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Claude AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Tanya jawab, ringkasan, soal latihan, dan kartu hafalan dari materi kursus Anda.")
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Masukkan kunci API Claude Anda", fontWeight = FontWeight.Medium)
            OutlinedTextField(key, { key = it }, label = { Text("sk-ant-…") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button({ vm.ai.setKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Simpan") }
        } }
        Text("• Claude tidak punya login SSO/Google untuk aplikasi pihak ketiga; aksesnya lewat kunci API (buat di console.anthropic.com).\n" +
            "• Kunci disimpan terenkripsi di ponsel ini (Android Keystore) dan hanya dikirim ke api.anthropic.com.\n" +
            "• Pemakaian ditagih ke akun Anda. Model bisa diganti di Lainnya (Haiku/Sonnet lebih murah).\n" +
            "• Isi PDF/slide tidak dibaca; AI memakai teks halaman, deskripsi tugas, dan daftar file.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChatPane(vm: MainViewModel, courseId: Long?) {
    val ai = vm.ai
    val chat by ai.chat.collectAsStateWithLifecycle()
    val busy by ai.busy.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(chat.size, chat.lastOrNull()?.text?.length) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size - 1) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.isEmpty()) item { Text(if (courseId == null) "Tanyakan apa saja." else "Tanyakan apa saja tentang kursus ini; jawaban memakai materinya.", style = MaterialTheme.typography.bodySmall) }
            items(chat.size) { i ->
                val m = chat[i]; val me = m.role == "user"
                Card(Modifier.fillMaxWidth().padding(start = if (me) 32.dp else 0.dp, end = if (me) 0.dp else 32.dp),
                    colors = CardDefaults.cardColors(containerColor = if (me) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    SelectionContainer { Text(m.text.ifEmpty { "…" }, Modifier.padding(12.dp)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Tulis pesan…") }, maxLines = 4)
            if (busy) Button({ ai.stop() }) { Text("Stop") } else Button({ ai.send(courseId, input); input = "" }, enabled = input.isNotBlank()) { Text("Kirim") }
        }
        if (chat.isNotEmpty() && !busy) TextButton({ ai.newChat() }, Modifier.padding(horizontal = 8.dp)) { Text("Percakapan baru") }
    }
}

@Composable
private fun SummaryPane(vm: MainViewModel, c: Course?) {
    val text by vm.ai.summary.collectAsStateWithLifecycle()
    val busy by vm.ai.busy.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ c?.let { vm.ai.summarize(it.id) } }, enabled = c != null && !busy) { Text("Ringkas kursus ini") }
        SelectionContainer { Text(text) }
    }
}

@Composable
private fun QuizPane(vm: MainViewModel, c: Course?) {
    val quiz by vm.ai.quiz.collectAsStateWithLifecycle()
    val busy by vm.ai.busy.collectAsStateWithLifecycle()
    var answers by remember(quiz) { mutableStateOf(mapOf<Int, Int>()) }
    var checked by remember(quiz) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button({ c?.let { vm.ai.makeQuiz(it.id) } }, enabled = c != null && !busy) { Text("Buat 5 soal latihan") }
        quiz.forEachIndexed { qi, q ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${qi + 1}. ${q.text}", fontWeight = FontWeight.Medium)
                q.options.forEachIndexed { oi, o ->
                    val tag = if (!checked) "" else if (oi == q.correct) " ✓" else if (answers[qi] == oi) " ✗" else ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(answers[qi] == oi, { if (!checked) answers = answers + (qi to oi) }); Text(o + tag)
                    }
                }
            } }
        }
        if (quiz.isNotEmpty()) {
            if (!checked) Button({ checked = true }, enabled = answers.size == quiz.size) { Text("Periksa jawaban") }
            else Text("Skor: ${quiz.indices.count { answers[it] == quiz[it].correct }} / ${quiz.size}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CardsPane(vm: MainViewModel, c: Course?, onReview: () -> Unit) {
    val draft by vm.ai.draft.collectAsStateWithLifecycle()
    val busy by vm.ai.busy.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val due = cards.count { Srs.isDue(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${cards.size} kartu · $due jatuh tempo", Modifier.weight(1f))
            Button(onReview, enabled = due > 0) { Text("Review") }
        } }
        Button({ c?.let { vm.ai.makeCards(it.id) } }, enabled = c != null && !busy) { Text("Buat 10 kartu dengan AI") }
        if (draft.isNotEmpty()) {
            Text("Draf (tinjau dulu; belum tersimpan):", style = MaterialTheme.typography.labelLarge)
            draft.forEach { Text("• ${it.front} → ${it.back}", style = MaterialTheme.typography.bodyMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ c?.let { vm.ai.saveDraft(it.id) } }) { Text("Simpan semua") }
                OutlinedButton({ vm.ai.draft.value = emptyList() }) { Text("Buang") }
            }
        }
        val mine = cards.filter { it.courseId == c?.id }
        if (mine.isNotEmpty()) {
            Text("Kartu kursus ini:", style = MaterialTheme.typography.labelLarge)
            mine.forEach { k -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${k.front} → ${k.back}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton({ vm.repo.deleteCard(k.id) }) { Text("Hapus") }
            } }
        }
    }
}

/** Sesi review kartu jatuh tempo; kartu yang dijawab "Lupa" muncul lagi di akhir antrean. */
@Composable
fun ReviewScreen(vm: MainViewModel, onBack: () -> Unit) {
    val queue = remember { mutableStateListOf<Card>().apply { addAll(vm.repo.cards.value.filter { Srs.isDue(it) }) } }
    var revealed by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onBack) { Text("← Selesai") }
        val card = queue.firstOrNull()
        if (card == null) {
            Text("Selesai 🎉", style = MaterialTheme.typography.headlineSmall); Text("$done kartu direview. Sampai jumpa besok!")
        } else {
            Text("Sisa ${queue.size}", style = MaterialTheme.typography.bodySmall)
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(card.front, style = MaterialTheme.typography.titleLarge)
                if (!revealed) Button({ revealed = true }) { Text("Tampilkan jawaban") }
                else {
                    HorizontalDivider(); Text(card.back)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("again" to "Lupa", "hard" to "Sulit", "good" to "Bisa", "easy" to "Mudah").forEach { (r, label) ->
                            Button({
                                val next = Srs.review(card, r); vm.repo.updateCard(next)
                                queue.removeAt(0); done++; if (r == "again") queue.add(next); revealed = false
                            }, colors = if (r == "again") ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text(label) }
                        }
                    }
                }
            } }
        }
    }
}
