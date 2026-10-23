package id.pribadi.minilms

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notif.ensureChannels(this)
        intent?.let(::route)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** Callback SSO Moodle: moodlemobile://token=... */
    private fun route(i: Intent) {
        val d = i.data ?: return
        if (d.scheme == Sso.SCHEME) vm.handleCallback(d)
    }
}

// ---------- tema ----------
private val Orange = Color(0xFFF98012)

@Composable
private fun Theme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Orange, onPrimary = Color.Black)
    else lightColorScheme(primary = Orange, onPrimary = Color.White, primaryContainer = Color(0xFFFCE9D6), onPrimaryContainer = Color(0xFF6B3300))
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun fmtSize(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}
private fun fmtDate(sec: Long) = SimpleDateFormat("EEE, d MMM yyyy HH:mm", Locale("id")).format(Date(sec * 1000))

// ---------- root ----------
@Composable
fun App(vm: MainViewModel) {
    Theme {
        val site by vm.site.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val host = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { host.showSnackbar(it); vm.clearMessage() } }
        Scaffold(snackbarHost = { SnackbarHost(host) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) { if (site == null) LoginScreen(vm) else MainScreen(vm) }
        }
    }
}

// ---------- login ----------
@Composable
fun LoginScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("https://") }
    var token by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(32.dp))
        Text("Mini LMS", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("Unduh semua kursus dan materi Moodle kampus untuk dibaca offline.")
        OutlinedTextField(url, { url = it }, label = { Text("Alamat Moodle kampus") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val cfg by vm.loginOptions.collectAsStateWithLifecycle()
        if (cfg == null) {
            Button({ vm.checkLogin(url) }, enabled = !busy && url.length > 8, modifier = Modifier.fillMaxWidth()) { Text("Lanjut") }
        } else {
            val c = cfg!!
            val open = { idp: Idp? -> vm.startSso(url, c, idp)?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) }; Unit }
            c.providers.forEach { p -> Button({ open(p) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(p.name) } }
            OutlinedButton({ open(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (c.providers.isEmpty()) "Masuk lewat browser" else "Masuk dengan akun Moodle (browser)") }
            TextButton({ vm.resetOptions() }) { Text("Ganti alamat") }
            Text("Browser akan terbuka untuk login. Setelah berhasil, Anda kembali ke aplikasi ini otomatis. Bila Android menanyakan aplikasi pembuka, pilih Mini LMS.", style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton({ more = !more }) { Text(if (more) "Sembunyikan cara lain" else "Cara masuk lain") }
        if (more) {
            Text("Token (Preferensi → Kunci keamanan di Moodle)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ vm.loginToken(url, token) }, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Masuk dengan token") }
            Text("Username dan password Moodle (bila kampus tidak memakai SSO)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedButton({ vm.loginPassword(url, user, pass) }, enabled = !busy && user.isNotBlank() && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Masuk") }
            Text("Password hanya dipakai sekali untuk meminta token dan tidak disimpan.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------- layar utama ----------
@Composable
fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openCourse by rememberSaveable { mutableStateOf<Long?>(null) }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()

    // izin notifikasi untuk unduhan dan pengingat (Android 13+)
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33 && !Notif.canPost(vm.getApplication())) perm.launch(Manifest.permission.POST_NOTIFICATIONS) }

    BackHandler(enabled = openCourse != null || reviewing) { if (reviewing) reviewing = false else openCourse = null }
    if (reviewing) { ReviewScreen(vm) { reviewing = false }; return }
    val course = snapshot?.courses?.firstOrNull { it.id == openCourse }
    if (course != null) { CourseScreen(vm, course) { openCourse = null }; return }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> CoursesTab(vm) { openCourse = it }
                1 -> DeadlinesTab(vm) { openCourse = it }
                2 -> GradesTab(vm)
                3 -> AiTab(vm) { reviewing = true }
                else -> SettingsTab(vm)
            }
        }
        NavigationBar {
            listOf("📚" to "Kursus", "⏰" to "Tenggat", "📊" to "Nilai", "✨" to "AI", "⚙️" to "Lainnya").forEachIndexed { i, (icon, label) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(icon) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun Header(title: String, content: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Row(content = content)
    }

// ---------- tab Kursus ----------
@Composable
fun CoursesTab(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val courses = snap?.courses ?: emptyList()
    // tick dibaca agar komposisi ulang saat status unduhan berubah
    val stats = remember(snap, tick) { courses.associate { it.id to vm.repo.stats(it) } }
    val totalFiles = stats.values.sumOf { it.files }; val doneFiles = stats.values.sumOf { it.downloaded }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Header("Kursus saya") { TextButton({ vm.sync() }, enabled = !busy) { Text(if (busy) "Menyinkron…" else "Sinkron") } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unduhan offline", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (dl.running) {
                        val f = if (dl.bytesTotal > 0) (dl.bytes.toFloat() / dl.bytesTotal).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                        Text("${dl.done}/${dl.total} file · ${fmtSize(dl.bytes)} / ${fmtSize(dl.bytesTotal)}\n${dl.name}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        OutlinedButton({ vm.pause() }) { Text("Jeda") }
                    } else {
                        Text("$doneFiles dari $totalFiles file sudah diunduh", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (dl.failed > 0) Text("${dl.failed} file gagal: ${dl.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Button({ vm.downloadAll() }, enabled = doneFiles < totalFiles) { Text(if (doneFiles == 0 && totalFiles > 0) "Unduh semua" else if (doneFiles < totalFiles) "Lanjutkan unduhan" else "Semua terunduh ✓") }
                    }
                }
            }
        }
        snap?.warnings?.takeIf { it.isNotEmpty() }?.let { w -> item { Text("Peringatan sinkron: ${w.joinToString("; ")}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) } }
        if (courses.isEmpty()) item { Text("Belum ada kursus. Tekan Sinkron.", Modifier.padding(16.dp)) }
        items(courses, key = { it.id }) { c ->
            val s = stats[c.id]!!
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpen(c.id) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(c.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (s.files > 0 && s.downloaded == s.files) Text("☁️✓", color = Color(0xFF2E7D32))
                    }
                    val frac = if (s.files == 0) 0f else s.downloaded.toFloat() / s.files
                    LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth())
                    Text(if (s.files == 0) "${c.items.size} item · tanpa file" else "${s.downloaded}/${s.files} file · ${fmtSize(s.downloadedBytes)} dari ${fmtSize(s.bytes)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------- detail kursus ----------
@Composable
fun CourseScreen(vm: MainViewModel, c: Course, onBack: () -> Unit) {
    val tick by vm.tick.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val stats = remember(c, tick) { vm.repo.stats(c) }
    val sections = remember(c) { c.items.groupBy { it.section } }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportZip(c, uri) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onBack) { Text("← Kembali") }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (c.summary.isNotBlank()) Text(c.summary, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text("${stats.downloaded}/${stats.files} file diunduh (${fmtSize(stats.downloadedBytes)} / ${fmtSize(stats.bytes)})", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ if (dl.running) vm.pause() else vm.downloadCourse(c.id) }, enabled = dl.running || stats.downloaded < stats.files) { Text(if (dl.running) "Jeda unduhan" else "Unduh kursus ini") }
                    if (stats.downloaded > 0) OutlinedButton({ vm.deleteDownloads(c.id) }) { Text("Hapus unduhan") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ vm.shareToNotebook(c) }) { Text("Kirim ke NotebookLM") }
                    TextButton({ vm.openNotebookLm() }) { Text("Buka NotebookLM") }
                }
                OutlinedButton({ zipLauncher.launch("${safeName(c.name)}.zip") }) { Text("Ekspor ZIP") }
            }
        }
        sections.forEach { (name, list) ->
            if (name.isNotEmpty()) item(key = "s_$name") { Text(name, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            items(list, key = { it.id }) { ItemCard(vm, c, it, tick) }
        }
        if (c.grades.isNotEmpty()) {
            item { Text("Nilai", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            item { GradeTable(c.grades) }
        }
    }
}

@Composable
private fun ItemCard(vm: MainViewModel, c: Course, it: Item, tick: Int) {
    var expanded by rememberSaveable(it.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text((when (it.type) { "assignment" -> "📝 "; "page" -> "📄 "; else -> "🔗 " }) + it.title, fontWeight = FontWeight.Medium)
            if (it.type == "assignment") {
                it.dueAt?.let { d -> Text("Tenggat: ${fmtDate(d)}", style = MaterialTheme.typography.bodySmall, color = if (d * 1000 < System.currentTimeMillis()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Kumpulkan dan lihat nilai di Moodle kampus.", style = MaterialTheme.typography.bodySmall)
            }
            if (it.type != "link" && it.content.isNotBlank()) {
                Text(it.content, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (it.content.length > 200 || it.content.count { ch -> ch == '\n' } > 3) TextButton({ expanded = !expanded }) { Text(if (expanded) "Ringkas" else "Selengkapnya") }
            }
            it.files.forEach { f ->
                val ok = remember(tick, f) { vm.repo.isDownloaded(c.id, it.id, f) }
                Row(Modifier.fillMaxWidth().clickable(enabled = ok) { vm.open(c, it, f) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ok) "✅" else "☁️")
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(fmtSize(f.size) + if (ok) " · ketuk untuk membuka" else " · belum diunduh", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (it.type == "link" && it.content.startsWith("http")) TextButton({ vm.openLink(it.content) }) { Text("Buka di browser") }
        }
    }
}

@Composable
private fun GradeTable(grades: List<Grade>) =
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            grades.forEach { g ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(g.name, Modifier.weight(1f)); Text(if (g.grade == null) "–" else "%.1f".format(g.grade) + (g.max?.let { " / %.0f".format(it) } ?: ""))
                }
            }
        }
    }

// ---------- tab Tenggat ----------
@Composable
fun DeadlinesTab(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis() / 1000
    val rows = remember(snap) {
        (snap?.courses ?: emptyList()).flatMap { c -> c.items.filter { it.type == "assignment" && it.dueAt != null }.map { c to it } }.sortedBy { it.second.dueAt }
    }
    val (upcoming, past) = rows.partition { it.second.dueAt!! >= now }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Tenggat tugas") }
        if (upcoming.isEmpty()) item { Text("Tidak ada tenggat mendatang.", Modifier.padding(16.dp)) }
        items(upcoming, key = { it.second.id }) { (c, i) -> DeadlineRow(c, i, false, onOpen) }
        if (past.isNotEmpty()) {
            item { Text("Sudah lewat", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium) }
            items(past.reversed().take(20), key = { it.second.id }) { (c, i) -> DeadlineRow(c, i, true, onOpen) }
        }
    }
}

@Composable
private fun DeadlineRow(c: Course, i: Item, past: Boolean, onOpen: (Long) -> Unit) =
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpen(c.id) }) {
        Column(Modifier.padding(14.dp)) {
            Text(i.title, fontWeight = FontWeight.Medium)
            Text(c.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(fmtDate(i.dueAt!!), color = if (past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary)
        }
    }

// ---------- tab Nilai ----------
@Composable
fun GradesTab(vm: MainViewModel) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val courses = (snap?.courses ?: emptyList()).filter { it.grades.isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Header("Nilai") }
        if (courses.isEmpty()) item { Text("Belum ada nilai (atau kampus menonaktifkannya).", Modifier.padding(16.dp)) }
        courses.forEach { c ->
            item(key = "g_${c.id}") { Text(c.name, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            item(key = "gt_${c.id}") { GradeTable(c.grades) }
        }
    }
}

// ---------- tab Lainnya ----------
@Composable
fun SettingsTab(vm: MainViewModel) {
    val site by vm.site.collectAsStateWithLifecycle()
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    var wifi by remember { mutableStateOf(vm.repo.wifiOnly) }
    var remind by remember { mutableStateOf(vm.repo.reminders) }
    var confirmLogout by remember { mutableStateOf(false) }
    val used = remember(tick) { vm.repo.storageUsed() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Lainnya", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(site?.fullName ?: "", fontWeight = FontWeight.SemiBold); Text(site?.siteName ?: "", style = MaterialTheme.typography.bodySmall); Text(site?.url ?: "", style = MaterialTheme.typography.bodySmall)
            snap?.let { Text("Sinkron terakhir: ${fmtDate(it.syncedAt)}", style = MaterialTheme.typography.bodySmall) }
        } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Unduh hanya lewat Wi-Fi"); Text("Hemat kuota seluler", style = MaterialTheme.typography.bodySmall) }
            Switch(wifi, { wifi = it; vm.setWifiOnly(it) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Pengingat tenggat"); Text("Notifikasi 24 jam sebelum tenggat (sinkron tiap ±6 jam)", style = MaterialTheme.typography.bodySmall) }
            Switch(remind, { remind = it; vm.setReminders(it) })
        }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Penggunaan ruang: ${fmtSize(used)}")
            OutlinedButton({ vm.deleteDownloads() }, enabled = used > 0) { Text("Hapus semua unduhan") }
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val model by vm.ai.model.collectAsStateWithLifecycle(); val hasKey by vm.ai.hasKey.collectAsStateWithLifecycle()
            Text("Claude AI", fontWeight = FontWeight.SemiBold)
            Models.all.forEach { (id, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(model == id, { vm.ai.setModel(id) }); Text(label) } }
            if (hasKey) OutlinedButton({ vm.ai.clearKey() }) { Text("Hapus kunci API") } else Text("Kunci API belum diisi (tab AI).", style = MaterialTheme.typography.bodySmall)
        } }
        OutlinedButton({ vm.openNotebookLm() }, Modifier.fillMaxWidth()) { Text("Buka NotebookLM (login Google)") }
        Button({ confirmLogout = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Keluar?") },
        text = { Text("Token, data kursus, kartu hafalan, dan semua file unduhan di ponsel ini akan dihapus. Kunci API Claude tidak ikut terhapus.") },
        confirmButton = { TextButton({ confirmLogout = false; vm.logout() }) { Text("Keluar") } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Batal") } })
}
