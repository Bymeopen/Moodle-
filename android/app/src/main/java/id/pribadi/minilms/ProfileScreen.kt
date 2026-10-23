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
    val theme: ThemeMode, val storageUsed: Long, val cardsTotal: Int, val model: String, val hasKey: Boolean, val sessionUsage: Usage, val version: String,
)

class ProfileActions(
    val onGrades: () -> Unit = {}, val onReview: () -> Unit = {}, val onNotebook: () -> Unit = {}, val onSync: () -> Unit = {}, val onWifiOnly: (Boolean) -> Unit = {},
    val onReminders: (Boolean) -> Unit = {}, val onTheme: (ThemeMode) -> Unit = {}, val onDeleteDownloads: () -> Unit = {}, val onModel: (String) -> Unit = {},
    val onClearKey: () -> Unit = {}, val onLogout: () -> Unit = {},
)

@Composable
fun ProfileScreen(vm: MainViewModel, onGrades: () -> Unit, onReview: () -> Unit = {}) {
    val site by vm.site.collectAsStateWithLifecycle(); val snap by vm.snapshot.collectAsStateWithLifecycle(); val tick by vm.tick.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle(); val cards by vm.cards.collectAsStateWithLifecycle()
    val model by vm.ai.model.collectAsStateWithLifecycle(); val hasKey by vm.ai.hasKey.collectAsStateWithLifecycle(); val usage by vm.ai.sessionUsage.collectAsStateWithLifecycle()
    var wifi by remember { mutableStateOf(vm.repo.wifiOnly) }; var remind by remember { mutableStateOf(vm.repo.reminders) }
    val used = remember(tick) { vm.repo.storageUsed() }
    val st = ProfileState(site?.fullName.orEmpty(), site?.siteName.orEmpty(), site?.url.orEmpty(), snap?.syncedAt, System.currentTimeMillis() / 1000, wifi, remind, theme, used, cards.size, model, hasKey, usage, "2.1")
    ProfileContent(st, ProfileActions(onGrades, onReview, vm::openNotebookLm, { vm.sync() }, { wifi = it; vm.setWifiOnly(it) }, { remind = it; vm.setReminders(it) }, vm::setTheme,
        { vm.deleteDownloads() }, vm.ai::setModel, vm.ai::clearKey, vm::logout))
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
            Row2(Icons.Rounded.OpenInNew, "NotebookLM", "Buka di browser (login Google)", cs.tertiary, onClick = a.onNotebook) { Chevron() }
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
        Group("Claude AI") {
            val prices = mapOf("claude-opus-5-5" to "Terpintar · $4 / $20 per juta token", "claude-sonnet-5-5" to "Seimbang · $2 / $10", "claude-haiku-5-5" to "Termurah · $0,10 / $0,50")
            Models.all.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { a.onModel(id) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(s.model == id, { a.onModel(id) })
                    Column { Text(label.substringBefore(" ("), style = MaterialTheme.typography.bodyMedium); Text(prices.getValue(id), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
            Row2(Icons.Rounded.Key, "Kunci API", if (s.hasKey) "Tersimpan terenkripsi" else "Belum diisi (tab AI)", cs.tertiary) { if (s.hasKey) TextButton(a.onClearKey) { Text("Hapus", color = cs.error) } }
            if (!s.sessionUsage.isEmpty) Row2(Icons.Rounded.Payments, "Pemakaian sesi ini", s.sessionUsage.label(s.model), cs.outline) {}
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
