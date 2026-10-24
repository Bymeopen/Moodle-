package id.pribadi.minilms

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class LinuxActions(
    val refresh: () -> Unit = {}, val install: () -> Unit = {}, val installTools: () -> Unit = {}, val mount: () -> Unit = {},
    val unmount: () -> Unit = {}, val remove: () -> Unit = {}, val run: (String) -> Unit = {},
    val setAiMode: (LinuxAiMode) -> Unit = {}, val refreshAiMaterials: () -> Unit = {},
)

@Composable
fun LinuxScreen(vm: MainViewModel, onBack: () -> Unit) {
    val st by vm.linux.state.collectAsStateWithLifecycle()
    val aiMode by vm.ai.linuxAiMode.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.linux.refresh() }
    val actions = remember(vm) { LinuxActions(vm.linux::refresh, vm.linux::install, vm.linux::installTools, vm.linux::mount, vm.linux::unmount, vm.linux::remove, { vm.linux.exec(it) }, vm.ai::setLinuxAiMode, vm.linux::refreshAiMaterials) }
    Column(Modifier.fillMaxSize()) { BackBar("Lab Linux (root)", onBack); LinuxContent(st, actions, aiMode) }
}

/** Lab Linux: pasang Alpine di chroot (ponsel root), lihat status, dan jalankan perintah di terminal mini. Stateless. */
@Composable
fun LinuxContent(s: LinuxState, a: LinuxActions, aiMode: LinuxAiMode = LinuxAiMode.OFF) {
    val cs = MaterialTheme.colorScheme
    var confirm by rememberSaveable { mutableStateOf("") }          // "", "install", "remove"
    var cmd by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoBanner("Fitur ini menjalankan perintah sebagai root. Asisten AI tidak punya akses ke terminal ini; hanya Anda.", tone = Tone.WARN, icon = Icons.Rounded.Security)
        Card2(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusRow("Root", when (s.rootOk) { true -> "Tersedia"; false -> "Tidak tersedia"; null -> "Belum dicek" }, s.rootOk == true)
                StatusRow("Linux", if (s.installed) "Alpine ${s.version}".trim() else "Belum terpasang", s.installed)
                StatusRow("Materi LMS", if (s.mounted) "Terpasang di /mnt/lms (hanya-baca)" else "Belum di-mount", s.mounted)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.installed) Button({ confirm = "install" }, enabled = !s.busy && s.rootOk != false) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pasang Alpine (±4 MB)") }
            else {
                FilledTonalButton(a.installTools, enabled = !s.busy) { Text("Pasang Python & PDF") }
                FilledTonalButton(a.mount, enabled = !s.busy) { Text("Mount") }
                FilledTonalButton(a.unmount, enabled = !s.busy) { Text("Lepas mount") }
                OutlinedButton({ confirm = "remove" }, enabled = !s.busy) { Text("Hapus", color = cs.error) }
            }
            OutlinedButton(a.refresh, enabled = !s.busy) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cek ulang") }
        }
        if (s.busy) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.step.ifEmpty { "Bekerja…" }, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            s.progress?.let { ProgressBar(it, Modifier.fillMaxWidth().height(4.dp)) } ?: LinearWaitBar()
        }
        if (s.installed) Card2(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Akses AI ke Linux", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinuxAiMode.values().forEach { m -> FilterChip(aiMode == m, { a.setAiMode(m) }, { Text(m.label) }) }
                }
                Text(when (aiMode) {
                    LinuxAiMode.OFF -> "AI tidak bisa menjalankan perintah apa pun."
                    LinuxAiMode.ASK -> "AI harus meminta izin Anda untuk tiap perintah (Izinkan/Tolak di chat)."
                    LinuxAiMode.AUTO -> "AI menjalankan perintah tanpa bertanya. Tetap di sandbox, tetapi pilih ini hanya bila Anda mengawasi."
                } + " Perintah AI berjalan sebagai pengguna terbatas (bukan root), tanpa jaringan dan tanpa akses penyimpanan ponsel; AI hanya melihat salinan materi di /home/ai/lms dan menulis di /home/ai/work. Semua perintahnya tercatat di terminal ini.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (aiMode != LinuxAiMode.OFF) OutlinedButton(a.refreshAiMaterials, enabled = !s.busy) { Text("Segarkan salinan materi untuk AI") }
            }
        }
        // terminal mini
        val listState = rememberLazyListState()
        LaunchedEffect(s.log.size) { if (s.log.isNotEmpty()) listState.scrollToItem(s.log.lastIndex) }
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF0D1117)) {
            if (s.log.isEmpty()) Box(Modifier.padding(14.dp)) { Text("Keluaran perintah tampil di sini.", color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            else LazyColumn(Modifier.padding(12.dp), state = listState) {
                items(s.log.size) { Text(s.log[it], color = if (s.log[it].startsWith("$ ")) Color(0xFF7EE787) else Color(0xFFE6EDF3), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp) }
            }
        }
        if (s.installed) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ls /mnt/lms", "python3 --version", "pdftotext -v", "df -h /", "uname -a").forEach { q -> SuggestionChip({ cmd = q }, { Text(q, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }) }
        }
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(cmd, { cmd = it }, Modifier.weight(1f), singleLine = true, enabled = s.installed && !s.busy, placeholder = { Text("perintah Linux", fontFamily = FontFamily.Monospace) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (cmd.isNotBlank()) { a.run(cmd); cmd = "" } }))
            FilledIconButton({ a.run(cmd); cmd = "" }, enabled = s.installed && !s.busy && cmd.isNotBlank()) { Icon(Icons.Rounded.PlayArrow, "Jalankan") }
        }
    }
    when (confirm) {
        "install" -> AlertDialog({ confirm = "" }, title = { Text("Pasang Alpine Linux?") },
            text = { Text("Mengunduh rootfs resmi Alpine (±4 MB), memverifikasi checksum SHA-256, lalu mengekstraknya sebagai root ke /data/local/minilms-chroot. Aplikasi meminta izin root dari manajer root Anda.") },
            confirmButton = { TextButton({ confirm = ""; a.install() }) { Text("Pasang") } }, dismissButton = { TextButton({ confirm = "" }) { Text("Batal") } })
        "remove" -> AlertDialog({ confirm = "" }, title = { Text("Hapus Linux dari ponsel?") },
            text = { Text("Semua isi Linux (paket dan file di dalamnya) dihapus. Mount dilepas lebih dulu; bila masih ada yang tersangkut, penghapusan dibatalkan demi keamanan penyimpananmu.") },
            confirmButton = { TextButton({ confirm = ""; a.remove() }) { Text("Hapus", color = cs.error) } }, dismissButton = { TextButton({ confirm = "" }) { Text("Batal") } })
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(18.dp), tint = if (ok) cs.secondary else cs.outline)
        Text(label, Modifier.width(92.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Penanda menunggu tanpa titik penanda M3: batang tipis berdenyut (aturan UI: jangan LinearProgressIndicator M3). */
@Composable
private fun LinearWaitBar() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "wait")
    val p by t.animateFloat(0.2f, 0.9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "p")
    ProgressBar(p, Modifier.fillMaxWidth().height(4.dp))
}
