package id.pribadi.minilms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

data class ProfileState(
    val fullName: String, val siteName: String, val siteUrl: String, val syncedAt: Long?, val nowSec: Long, val wifiOnly: Boolean, val reminders: Boolean,
    val theme: ThemeMode, val storageUsed: Long, val cardsTotal: Int, val provider: Provider, val model: String, val keys: Set<Provider>, val sessionUsage: Usage, val version: String, val hasCrash: Boolean = false,
)

class ProfileActions(
    val onGrades: () -> Unit = {}, val onReview: () -> Unit = {}, val onOpenWeb: (String) -> Unit = {}, val onSync: () -> Unit = {}, val onWifiOnly: (Boolean) -> Unit = {},
    val onReminders: (Boolean) -> Unit = {}, val onTheme: (ThemeMode) -> Unit = {}, val onDeleteDownloads: () -> Unit = {}, val onModel: (String) -> Unit = {},
    val onDiagnostics: () -> Unit = {}, val onClearCrash: () -> Unit = {}, val onProvider: (Provider) -> Unit = {}, val onSaveKey: (Provider, String) -> Unit = { _, _ -> }, val onClearKey: (Provider) -> Unit = {}, val onLogout: () -> Unit = {},
)

@Composable
fun ProfileScreen(vm: MainViewModel, onGrades: () -> Unit, onReview: () -> Unit = {}) {
    val site by vm.site.collectAsStateWithLifecycle(); val snap by vm.snapshot.collectAsStateWithLifecycle(); val tick by vm.tick.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle(); val cards by vm.cards.collectAsStateWithLifecycle()
    val model by vm.ai.model.collectAsStateWithLifecycle(); val keys by vm.ai.keys.collectAsStateWithLifecycle(); val provider by vm.ai.provider.collectAsStateWithLifecycle()
    val usage by vm.ai.sessionUsage.collectAsStateWithLifecycle()
    var wifi by remember { mutableStateOf(vm.repo.wifiOnly) }; var remind by remember { mutableStateOf(vm.repo.reminders) }
    val used = remember(tick) { vm.repo.storageUsed() }
    val st = ProfileState(site?.fullName.orEmpty(), site?.siteName.orEmpty(), site?.url.orEmpty(), snap?.syncedAt, System.currentTimeMillis() / 1000, wifi, remind, theme, used, cards.size, provider, model, keys, usage, vm.versionName(), vm.hasCrash())
    ProfileContent(st, ProfileActions(onGrades, onReview, vm::openWeb, { vm.sync() }, { wifi = it; vm.setWifiOnly(it) }, { remind = it; vm.setReminders(it) }, vm::setTheme,
        { vm.deleteDownloads() }, { vm.ai.setModel(it) }, vm::shareDiagnostics, vm::clearCrash, vm.ai::setProvider, { p, k -> vm.ai.setKey(k, p) }, { vm.ai.clearKey(it) }, vm::logout))
}

private val NIM = Regex("\\b(\\d{6,})\\b")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(s: ProfileState, a: ProfileActions) {
    val cs = MaterialTheme.colorScheme
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nim = NIM.find(s.fullName)?.value
    val cleanName = s.fullName.replace(NIM, "").trim().split(Regex("\\s+")).joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Profil", style = MaterialTheme.typography.headlineMedium)
        Card2(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFF9966), Color(0xFFE8590C)))), contentAlignment = Alignment.Center) {
                    Text(Logic.initials(cleanName).ifEmpty { "?" }, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(cleanName.ifEmpty { "Mahasiswa" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (nim != null) Text("NIM $nim", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text(s.siteName.ifEmpty { s.siteUrl }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.syncedAt == null) "Belum sinkron" else "Sinkron " + Logic.ago(s.syncedAt, s.nowSec), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                TextButton(a.onSync) { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sinkron") }
            }
        }

        Group("Belajar") {
            Row2(Icons.Rounded.Insights, "Nilai saya", "Ringkasan per kursus", cs.primary, onClick = a.onGrades) { Chevron() }
            Row2(Icons.Rounded.Style, "Kartu hafalan", "${s.cardsTotal} kartu", cs.secondary, onClick = a.onReview) { Chevron() }
        }
        Group("Aplikasi AI resmi (login SSO / Google)") {
            Row2(Icons.Rounded.AutoAwesome, "Claude (claude.ai)", "Login SSO seperti biasa · ada paket gratis", cs.tertiary, onClick = { a.onOpenWeb("https://claude.ai") }) { Chevron() }
            Row2(Icons.Rounded.AutoAwesome, "Gemini", "Login akun Google · gratis", cs.primary, onClick = { a.onOpenWeb("https://gemini.google.com") }) { Chevron() }
            Row2(Icons.Rounded.OpenInNew, "NotebookLM", "Login akun Google · gratis", cs.secondary, onClick = { a.onOpenWeb("https://notebooklm.google.com") }) { Chevron() }
        }
        Group("Unduhan & notifikasi") {
            Row2(Icons.Rounded.Wifi, "Unduh hanya lewat Wi-Fi", "Hemat kuota seluler", cs.secondary) { Switch(s.wifiOnly, a.onWifiOnly) }
            Row2(Icons.Rounded.Notifications, "Pengingat tenggat", "Notifikasi 24 jam sebelum tenggat", cs.primary) { Switch(s.reminders, a.onReminders) }
            Row2(Icons.Rounded.Storage, "Ruang terpakai", Logic.fmtSize(s.storageUsed), cs.outline) {
                TextButton({ confirmDelete = true }, enabled = s.storageUsed > 0) { Text("Hapus", color = if (s.storageUsed > 0) cs.error else cs.outline) }
            }
        }
        Group("Tampilan") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tema", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(s.theme == m, { a.onTheme(m) }, SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = cs.primaryContainer, activeContentColor = cs.onPrimaryContainer, activeBorderColor = cs.primaryContainer)) { Text(m.label) }
                    }
                }
            }
        }
        Group("Asisten AI") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Penyedia", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Provider.entries.forEachIndexed { i, p ->
                        SegmentedButton(s.provider == p, { a.onProvider(p) }, SegmentedButtonDefaults.itemShape(i, Provider.entries.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = cs.tertiaryContainer, activeContentColor = cs.onTertiaryContainer, activeBorderColor = cs.tertiaryContainer)) { Text(p.label.substringAfter(' ')) }
                    }
                }
                if (s.provider == Provider.GEMINI && Logic.freeTier(s.model)) InfoBanner("Tingkat gratis: isi permintaan dapat dipakai Google untuk memperbaiki produknya. Jangan kirim data pribadi; kunci berbayar tidak begitu.", tone = Tone.WARN, icon = Icons.Rounded.Info)
            }
            Models.of(s.provider).forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { a.onModel(m.id) }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.model == m.id, { a.onModel(m.id) })
                    Column { Text(m.label, style = MaterialTheme.typography.bodyMedium); Text(m.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                }
            }
            var custom by remember(s.provider) { mutableStateOf(if (Models.of(s.provider).none { it.id == s.model }) s.model else "") }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("ID model lain (opsional)") }, placeholder = { Text(Models.default(s.provider)) },
                    supportingText = { Text("Nama model sering berubah; isi bila daftar di atas sudah usang.") },
                    trailingIcon = { TextButton({ a.onModel(custom) }, enabled = custom.isNotBlank() && custom != s.model) { Text("Pakai") } })
            }
            HorizontalDivider(color = cs.outlineVariant)
            Provider.entries.forEach { p ->
                var dialog by remember { mutableStateOf(false) }
                Row2(Icons.Rounded.Key, "Kunci ${p.label.substringAfter(' ')}", if (p in s.keys) "Tersimpan terenkripsi" else "Belum diisi", cs.tertiary) {
                    if (p in s.keys) TextButton({ a.onClearKey(p) }) { Text("Hapus", color = cs.error) } else TextButton({ dialog = true }) { Text("Isi") }
                }
                if (dialog) {
                    var k by remember { mutableStateOf("") }
                    AlertDialog({ dialog = false }, title = { Text("Kunci API ${p.label}") },
                        text = { OutlinedTextField(k, { k = it }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), label = { Text(if (p == Provider.GEMINI) "AIza…" else "sk-ant-…") }) },
                        confirmButton = { TextButton({ a.onSaveKey(p, k); dialog = false }, enabled = k.isNotBlank()) { Text("Simpan") } }, dismissButton = { TextButton({ dialog = false }) { Text("Batal") } })
                }
            }
            if (!s.sessionUsage.isEmpty) Row2(Icons.Rounded.Payments, "Pemakaian sesi ini", s.sessionUsage.label(s.model), cs.outline) {}
        }
        Group("Bantuan") {
            Row2(Icons.Rounded.BugReport, "Laporan galat", if (s.hasCrash) "Ada crash tercatat — bagikan agar bisa diperbaiki" else "Bagikan info diagnostik (tanpa token/kunci/isi kursus)", if (s.hasCrash) cs.error else cs.outline) {
                TextButton(a.onDiagnostics) { Text("Bagikan") }
            }
            if (s.hasCrash) Row2(Icons.Rounded.DeleteOutline, "Hapus catatan crash", "Setelah dibagikan", cs.outline) { TextButton(a.onClearCrash) { Text("Hapus") } }
        }
        Group("Akun") {
            Row2(Icons.Rounded.Logout, "Keluar", "Hapus token, data kursus, kartu, dan unduhan dari ponsel ini", cs.error, onClick = { confirmLogout = true }) {}
        }
        Text("Mini LMS ${s.version}", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = cs.outline, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, title = { Text("Hapus semua unduhan?") }, text = { Text("${Logic.fmtSize(s.storageUsed)} dihapus dari ponsel. Anda bisa mengunduhnya lagi.") },
        confirmButton = { TextButton({ confirmDelete = false; a.onDeleteDownloads() }) { Text("Hapus", color = cs.error) } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("Batal") } })
    if (confirmLogout) AlertDialog({ confirmLogout = false }, title = { Text("Keluar?") }, text = { Text("Token, data kursus, kartu hafalan, dan semua file unduhan di ponsel ini akan dihapus. Kunci API Claude tidak ikut terhapus.") },
        confirmButton = { TextButton({ confirmLogout = false; a.onLogout() }) { Text("Keluar", color = cs.error) } }, dismissButton = { TextButton({ confirmLogout = false }) { Text("Batal") } })
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card2(Modifier.fillMaxWidth(), content = content)
    }

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)

@Composable
private fun Row2(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon, tint, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
