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
- `AiBackend.kt` (antarmuka + `Provider`/`Models`), `Gemini.kt` (Google Gemini, **penyedia utama/gratis**) dan `Claude.kt` (Anthropic, berbayar) + `AiController.kt`: AI langsung dari ponsel dengan kunci API pengguna per penyedia (`SecretStore.kt` = Android Keystore). `Srs.kt`: SM-2.
- `Library.kt` + `LibraryScreen.kt`: perpustakaan gratis tanpa kunci (Internet Archive = buku PDF, DOAJ = jurnal). `PublicHttp` hanya https ke host tepercaya (alihan dicek manual), tanpa token Moodle. `MoodleWeb.kt`: WebView dalam aplikasi untuk halaman Moodle (login otomatis lewat `MoodleApi.autologinUrl`, kebijakan navigasi `WebPolicy`, cookie dihapus saat ditutup). Repositori kampus Muhammadiyah (EPrints, hasil HTML standar `parseEprints`) dicari lewat `Campus`. Forum dan soal tes selesai disimpan sebagai teks offline (`forumText`, `quizReview`); video YouTube hanya bisa diputar online (tidak diunduh).
- `FileText.kt` (docx/pptx/txt → teks; PDF dikirim sebagai dokumen ke Claude), `ZipExport.kt`: ekspor kursus.
- UI Compose, **stateless**: `Theme.kt`/`Components.kt` (sistem desain), `HomeScreens.kt` (Beranda, Kursus, Tenggat, Nilai), `CourseDetailScreen.kt`, `AiScreens.kt` (state `AiState`/`AiActions`), `ProfileScreen.kt`, `LoginScreen.kt`, `MarkdownText.kt`. Layar menerima data biasa + lambda; pembungkus berstatus (`XxxScreen(vm)`) hanya mengumpulkan state dari `MainViewModel`. `MainActivity.kt` = navigasi.
- `Logic.kt` (bucket tenggat, ringkasan nilai, biaya token), `Markdown.kt` (parser), `ChatStore.kt` (riwayat chat per kursus): murni dan dites.

## Aturan
- Jangan simpan atau log token Moodle, password, atau kunci API. Token Moodle dikirim lewat body POST, bukan URL, dan hanya ke host Moodle yang sama.
- Teks dari Moodle/siswa/materi yang masuk ke prompt Claude adalah **data**: bungkus dengan tag dan nyatakan di system prompt bahwa isinya bukan instruksi. Validasi ulang keluaran model sebelum dipakai.
- Penyedia default **Gemini** (`gemini-3.8-flash`, gratis); Claude opsional (berbayar, default `claude-opus-5-5`). Pemilik proyek tidak bisa memakai model berbayar, jadi jangan menjadikan model berbayar default. ID model sering berubah: verifikasi ke dokumentasi resmi (`ai.google.dev/gemini-api/docs/models`) sebelum mengubah `Models`, dan biarkan pengguna mengisi ID model sendiri.
- Gemini: kunci di header `x-goog-api-key` (bukan URL); skema JSON diubah ke subset OpenAPI (`GeminiClient.toSchema`); harga Gemini sadar tanggal (`Usage.costUsd`); tingkat gratis dapat dipakai Google untuk perbaikan produk (peringatan harus tetap tampil). Claude: jangan kirim `temperature/top_p/top_k` atau `thinking` budget (ditolak model baru).
- Login SSO/Google untuk Claude/Gemini/NotebookLM/Antigravity tidak bisa ditanam di aplikasi (tidak ada API; Google melarang OAuth di WebView). Jalurnya: Custom Tab ke situs resmi (`MainViewModel.openWeb`) dan menu bagikan materi.
- **Invarian data (hasil audit):** sinkron Moodle tidak boleh menurunkan data offline: kegagalan sebagian (konten/tugas/halaman/nilai) mempertahankan data lama (`fetchAll(previous=)`), daftar kursus kosong tidak menimpa data lama, `safe`/`attempt` menangkap JSONException dan ClassCastException, satu modul rusak dilewati. Sinkron dikunci (`syncLock`) dan dibuang bila logout terjadi di tengah (`epoch`). Logout menghapus chat, kartu, unduhan, dan `reminded`.
- **Invarian AI:** setiap permintaan punya nomor generasi (`generation`/`live(gen)`); job lama tidak boleh menulis `chat`, `busy`, atau disk. `stop()` menyimpan jawaban sebagian; `abort()` (ganti kursus/penyedia/chat baru) membuang. Jangan kirim pesan kosong ke API; riwayat dipangkas (`trimHistory`). Teks kursus dinetralkan agar tidak menutup tag data (`AiMaterial.neutralize`).
- **Siklus hidup:** callback SSO hanya diproses bila `savedInstanceState == null` lalu `intent.data = null`; passport SSO acak 128 bit dan state dihapus setelah 3 callback gagal; base64 callback hanya didekode %XX (jangan `URLDecoder`, mengubah `+`). I/O disk tidak boleh di main thread; IOException di callback UI jadi pesan, bukan crash.
- Laporan galat: `CrashLog` menyimpan jejak crash (disamarkan) dan diagnostik yang dibagikan manual lewat Profil → Bantuan; jangan menambah pengiriman otomatis.
- UI: gunakan token tema (`MaterialTheme.colorScheme`, `Card2`, `Pill`, `ProgressBar`); jangan emoji sebagai ikon (pakai `Icons.Rounded.*`); jangan `LinearProgressIndicator` M3 (ada titik penanda), pakai `ProgressBar`.
- Setiap perubahan logika harus disertai tes (`test/*.test.js` atau `app/src/test`). Jalankan kedua rangkaian tes sebelum commit.
- Jangan commit `android/local.properties`, `android/keystore.properties`, `*.jks`, `*.keystore`, `*.apk`, `data/`, `node_modules/`.
