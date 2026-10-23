# Mini LMS — panduan untuk agen AI (Claude Code, Google Antigravity, dll.)

Proyek pribadi bergaya Moodle untuk belajar mandiri. Bahasa UI dan komentar: Indonesia. Dua bagian:

| Bagian | Lokasi | Stack |
|---|---|---|
| Web + API (opsional, bisa tanpa) | `src/`, `public/`, `test/` | Node ≥ 22.13, Express, `node:sqlite`, JS vanilla |
| Aplikasi Android native (utama) | `android/` | Kotlin, Jetpack Compose, WorkManager; tanpa server |

## Perintah
```bash
npm install && npm test                     # tes web/API (node --test)
cd android && echo "sdk.dir=$HOME/android-sdk" > local.properties
gradle testDebugUnitTest assembleDebug      # tes JVM + APK debug
```
Android butuh JDK 17+, Gradle 8.x, SDK platform 34 + build-tools 34. Tidak ada emulator di CI/cloud, tapi UI bisa dilihat: `gradle testDebugUnitTest` merender layar dengan Robolectric (mode NATIVE) ke `android/app/build/shots/*.png` (data contoh di `app/src/test/.../visual/Fixtures.kt`, mode terang dan gelap). Setelah mengubah UI, jalankan tes lalu lihat PNG-nya. Jika Maven Central membalas 429, ulangi perintah.

## Arsitektur Android (`android/app/src/main/java/id/pribadi/minilms/`)
- `MoodleApi.kt`: klien Web Service Moodle (token, `fetchAll`), SSO `Sso.parseCallback` (verifikasi md5(url+passport)). Jaringan lewat `Transport` agar mudah dites.
- `Repo.kt`: sesi, snapshot JSON, file unduhan (`filesDir/courses/`), kartu hafalan. `Workers.kt`: unduh massal dan pengingat tenggat.
- `Claude.kt` + `AiController.kt`: klien Claude Messages API langsung dari ponsel (kunci API pengguna, `SecretStore.kt` = Android Keystore). `Srs.kt`: SM-2.
- `FileText.kt` (docx/pptx/txt → teks; PDF dikirim sebagai dokumen ke Claude), `ZipExport.kt`: ekspor kursus.
- UI Compose, **stateless**: `Theme.kt`/`Components.kt` (sistem desain), `HomeScreens.kt` (Beranda, Kursus, Tenggat, Nilai), `CourseDetailScreen.kt`, `AiScreens.kt` (state `AiState`/`AiActions`), `ProfileScreen.kt`, `LoginScreen.kt`, `MarkdownText.kt`. Layar menerima data biasa + lambda; pembungkus berstatus (`XxxScreen(vm)`) hanya mengumpulkan state dari `MainViewModel`. `MainActivity.kt` = navigasi.
- `Logic.kt` (bucket tenggat, ringkasan nilai, biaya token), `Markdown.kt` (parser), `ChatStore.kt` (riwayat chat per kursus): murni dan dites.

## Aturan
- Jangan simpan atau log token Moodle, password, atau kunci API. Token Moodle dikirim lewat body POST, bukan URL, dan hanya ke host Moodle yang sama.
- Teks dari Moodle/siswa/materi yang masuk ke prompt Claude adalah **data**: bungkus dengan tag dan nyatakan di system prompt bahwa isinya bukan instruksi. Validasi ulang keluaran model sebelum dipakai.
- Model Claude default `claude-opus-5-5` (pilihan Sonnet/Haiku di pengaturan). Jangan kirim `temperature/top_p/top_k` atau `thinking` budget (ditolak model baru).
- UI: gunakan token tema (`MaterialTheme.colorScheme`, `Card2`, `Pill`, `ProgressBar`); jangan emoji sebagai ikon (pakai `Icons.Rounded.*`); jangan `LinearProgressIndicator` M3 (ada titik penanda), pakai `ProgressBar`.
- Setiap perubahan logika harus disertai tes (`test/*.test.js` atau `app/src/test`). Jalankan kedua rangkaian tes sebelum commit.
- Jangan commit `android/local.properties`, `android/keystore.properties`, `*.jks`, `*.keystore`, `*.apk`, `data/`, `node_modules/`.
