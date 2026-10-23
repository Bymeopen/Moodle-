package id.pribadi.minilms

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cfg by vm.loginOptions.collectAsStateWithLifecycle()
    LoginContent(busy, cfg, onContinue = vm::checkLogin, onReset = vm::resetOptions,
        onProvider = { url, c, idp -> vm.startSso(url, c, idp)?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(it))) } },
        onToken = vm::loginToken, onPassword = vm::loginPassword)
}

@Composable
fun LoginContent(busy: Boolean, cfg: PublicConfig?, onContinue: (String) -> Unit, onReset: () -> Unit, onProvider: (String, PublicConfig, Idp?) -> Unit,
                 onToken: (String, String) -> Unit, onPassword: (String, String, String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var url by rememberSaveable { mutableStateOf("https://") }
    var token by rememberSaveable { mutableStateOf("") }; var user by rememberSaveable { mutableStateOf("") }; var pass by rememberSaveable { mutableStateOf("") }
    var more by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFFFF8A4C), Color(0xFFD9480F)))).statusBarsPadding().padding(horizontal = 24.dp, vertical = 36.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.size(56.dp), shape = RoundedCornerShape(18.dp), color = Color.White.copy(0.22f)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.School, null, Modifier.size(32.dp), tint = Color.White) } }
                Text("Mini LMS", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Text("Semua kursus kampus, siap dibaca offline, ditemani Claude.", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(0.92f))
            }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Masuk ke Moodle kampus", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Alamat Moodle kampus") }, leadingIcon = { Icon(Icons.Rounded.Link, null) }, enabled = cfg == null)
            if (cfg == null) {
                Button({ onContinue(url) }, enabled = !busy && url.length > 8, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary) else { Text("Lanjut"); Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.ArrowForward, null, Modifier.size(18.dp)) }
                }
            } else {
                cfg.providers.forEach { p -> Button({ onProvider(url, cfg, p) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Rounded.Login, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(p.name) } }
                OutlinedButton({ onProvider(url, cfg, null) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (cfg.providers.isEmpty()) "Masuk lewat browser" else "Masuk dengan akun Moodle (browser)") }
                TextButton(onReset) { Text("Ganti alamat") }
                InfoBanner("Browser akan terbuka untuk login. Setelah berhasil Anda kembali ke aplikasi ini otomatis; bila Android menanyakan aplikasi pembuka, pilih Mini LMS.", icon = Icons.Rounded.Info)
            }
            TextButton({ more = !more }) { Text(if (more) "Sembunyikan cara lain" else "Cara masuk lain") }
            if (more) Card2(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Token (Preferensi → Kunci keamanan)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Token") })
                    OutlinedButton({ onToken(url, token) }, enabled = !busy && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Masuk dengan token") }
                    HorizontalDivider(color = cs.outlineVariant)
                    Text("Username & password Moodle", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
                    OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                    OutlinedButton({ onPassword(url, user, pass) }, enabled = !busy && user.isNotBlank() && pass.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Masuk") }
                    Text("Password hanya dipakai sekali untuk meminta token dan tidak disimpan.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
            listOf(Icons.Rounded.CloudDownload to "Unduh semua materi sekali, baca di mana saja", Icons.Rounded.EventAvailable to "Pengingat tenggat tugas", Icons.Rounded.AutoAwesome to "Tanya, ringkas, dan latihan soal dengan Claude").forEach { (i, t) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { IconBadge(i, cs.primary, size = 32.dp); Text(t, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            }
        }
    }
}
