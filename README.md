# Mini LMS

LMS minimal mirip Moodle. Node ≥ 22.13, Express, SQLite bawaan (`node:sqlite`), frontend vanilla JS.

```bash
npm install
npm start        # http://localhost:3000
npm test
```

Env: `PORT`, `DB_FILE` (default `data/lms.db`), `SECRET` (kunci token; set agar login bertahan antar restart).

## Fitur
- Auth + role (admin/teacher/student); akun pertama = admin, admin mengubah role di menu Pengguna
- Kursus & enrolment mandiri
- Item: halaman, tautan, tugas (kirim ulang, tenggat, penilaian + umpan balik), kuis pilihan ganda (auto-grade)
- Buku nilai per kursus (guru: semua siswa, siswa: miliknya)

## Belajar mandiri
- Penanda "Selesai" per item + bar progres per kursus
- Kartu hafalan dengan pengulangan berjarak (SM-2): tombol Lupa/Sulit/Bisa/Mudah, antrean review harian
- Beranda: jumlah kartu jatuh tempo dan runtun hari belajar berturut-turut
- AI (opsional): buat kartu dari materi kursus, ditinjau sebelum disimpan
- `ALLOW_REGISTRATION=false` menutup pendaftaran setelah akun pertama (untuk server pribadi)

## Hubungkan Moodle kampus (menu "Moodle kampus")
Impor read-only lewat Moodle Web Service resmi: mata kuliah, materi (halaman, tautan), tugas + tenggat, dan nilai. Sinkron ulang aman (upsert; progres, kartu, dan penanda selesai tidak hilang). Tenggat tampil di beranda; materi bisa dipakai untuk kartu hafalan dan tutor AI.

- Login: username+password (dipakai sekali untuk meminta token dari `/login/token.php`, tidak disimpan) **atau** tempel token dari Moodle (Preferensi → Kunci keamanan).
- Syarat: kampus mengaktifkan layanan `moodle_mobile_app`. Jika `login/token.php` menolak, minta admin kampus atau pakai token manual.
- Token disimpan **plaintext** di `data/lms.db`: jaga file itu (`chmod 600`) dan cabut token di Moodle bila perlu.
- Keamanan: hanya `https://`, alamat jaringan internal diblokir (anti-SSRF), redirect tidak diikuti. `MOODLE_ALLOW_PRIVATE=true` hanya untuk dev/test.
- File materi (PDF, slide, dll.): sinkron mencatat daftar file; tombol **Unduh semua file** menyimpannya ke `data/files/<user>/<kursus>/<item>/` (ubah dengan `FILES_DIR`; batas per file `MOODLE_MAX_FILE_MB`, default 200). Token dikirim lewat POST body hanya ke host Moodle yang sama; nama file disanitasi (anti path traversal). File yang berubah di Moodle diunduh ulang setelah sinkron.
- Tidak diimpor: kuis, forum, lampiran dalam tugas, pengumpulan tugas. Kumpulkan tugas tetap di Moodle.
- SSO kampus (SAML/OAuth) untuk login ke Mini LMS tidak termasuk; butuh pendaftaran aplikasi oleh admin kampus.

## Belum ada (dibanding Moodle)
Upload file, forum, grup, backup, plugin, SCORM, notifikasi, rubrik, tipe soal lain, i18n.

## Fitur AI (Claude)
Aktif bila `ANTHROPIC_API_KEY` di-set (model default `claude-opus-5-5`, ubah via `AI_MODEL`). Tanpa key, endpoint AI membalas 503 dan UI menyembunyikannya.

| Fitur | Siapa | Endpoint |
|---|---|---|
| Draf soal kuis dari materi (ditinjau guru sebelum disimpan) | guru | `POST /api/courses/:id/ai/quiz` |
| Saran nilai + umpan balik (tidak otomatis diterapkan) | guru | `POST /api/submissions/:id/ai/suggest` |
| Tutor tanya-jawab berdasar materi kursus | siswa/guru | `POST /api/courses/:id/ai/ask` |

Catatan keamanan: jawaban siswa dan materi dikirim sebagai data (dibungkus tag, system prompt melarang mengikuti instruksi di dalamnya); output model divalidasi ulang di server; nilai dibatasi ke `max_grade`.

## Aplikasi Android (native, tanpa server)
`android/` adalah aplikasi Kotlin + Jetpack Compose yang terhubung **langsung** ke Moodle kampus lewat Web Service resmi (seperti Moodle Mobile). Server Node di atas tidak diperlukan.

- Login SSO/Google otomatis: aplikasi membuka tautan resmi `launch.php` dan menangkap callback `moodlemobile://token=...` (tanda tangan md5(url+passport) diverifikasi). Alternatif: tempel token, atau username+password.
- Tab Kursus: kartu kursus dengan progres unduhan, **Unduh semua** (WorkManager + notifikasi progres, jeda/lanjut, opsi hanya Wi-Fi), detail kursus per bagian, buka file dengan aplikasi PDF/dokumen bawaan.
- Tab **AI** (Claude, kunci API milik Anda, disimpan terenkripsi di Android Keystore): chat tutor (opsional dengan konteks kursus), ringkasan, soal latihan pilihan ganda, dan kartu hafalan dengan review SM-2. Hasil AI ditinjau dulu sebelum disimpan. Claude tidak punya login SSO untuk aplikasi pihak ketiga, jadi aksesnya lewat kunci API (Claude Code adalah alat terminal dan tidak berjalan di dalam aplikasi).
- **Kirim ke NotebookLM** (detail kursus): menyiapkan ringkasan teks kursus (.md) + file yang sudah diunduh, lalu membuka menu bagikan Android; pilih NotebookLM atau Google Drive (maks 50 sumber). NotebookLM pribadi tidak punya API publik, jadi tidak ada unggah otomatis.
- Tab Tenggat, Nilai, dan Lainnya (penyimpanan, hapus unduhan, pengingat tenggat 24 jam, keluar).
- Semua data di penyimpanan privat aplikasi. Token tidak dikirim lewat URL; file hanya diunduh dari host Moodle yang sama.

Build (JDK 17+, Gradle 8.x, Android SDK platform 34 + build-tools 34):
```bash
cd android && echo "sdk.dir=$HOME/android-sdk" > local.properties
gradle testDebugUnitTest assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
```
APK debug memakai kunci debug lokal; membangun ulang di mesin lain memakai kunci berbeda sehingga pembaruan harus uninstall dulu. Belum ada: pesan/forum, kuis Moodle, pengumpulan tugas. Panduan untuk agen AI (Claude Code/Antigravity): `AGENTS.md`.
