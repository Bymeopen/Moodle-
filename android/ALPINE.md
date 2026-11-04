# Alpine sebagai mesin inti Mini LMS — peta jalan

Tujuan: Alpine Linux di dalam aplikasi menjadi mesin kerja (shell, Python, alat PDF) yang **bisa membaca dan mengendalikan
Moodle lewat aplikasi**, tanpa pernah memegang token, tanpa jaringan sendiri, dan tanpa pernah menjalankan perintah AI sebagai root.

## Arsitektur (v2.17)

```
Chat / Agen LMS ──linux_exec──▶ CommandGate (OFF | TANYA | AUTO)
                                   │ disetujui
                                   ▼
             LinuxController.runForAi ──▶ su ai (uid 5099) · unshare -n · timeout
                                   │                 │
                      BridgeSpool.serve              │  moodle <op> …   /  import moodle
                 (filesDir/bridge ⇄ /home/ai/bridge, │  (shell / Python)
                  hanya selama satu perintah)        ▼
                                   ◀── req-<hex>.json ── sandbox
                 aplikasi ── res-<hex>.json ─────────▶
                    │
                    ├─ lokal: snapshot offline, LmsActions (sync, unduh, tanda selesai, buka, paket)
                    └─ Web Service Moodle baca-saja (token hanya di aplikasi, jawaban disaring)
```

Kontrak keamanan (dijaga tes `MoodleBridgeTest`, `BridgeLocalTest`, `AiLinuxTest`, `LinuxTest`):
- Sandbox: bukan root, tanpa jaringan, tanpa `/sdcard`, tanpa data aplikasi; materi hanya-baca di `/home/ai/lms`.
- Jembatan: daftar putih tetap; fungsi WS hanya `*_get_*`; argumen hanya bilangan bulat positif; id pengguna dari sesi;
  field token/kunci dibuang; keluaran ≤ 60K; ≤ 40 permintaan per perintah; permintaan symlink tidak dibaca; jawaban di-rename, bukan ditulis lewat symlink.
- Tidak ada operasi yang mengirim tugas, mengerjakan tes, atau memposting di forum — dan tidak akan ditambah.
- Rootfs: hash SHA-256 wajib cocok (indeks rilis resmi saat unduh, atau pin `alpine.properties` untuk bundel). Gagal = batal, tanpa fallback diam-diam.

## Fase

| Fase | Isi | Status |
|---|---|---|
| 1 | Jembatan baca-saja `moodle <op>` (spool berkas, daftar putih WS, sanitasi) | selesai |
| 2 | Rootfs Alpine dibundel di APK (`fetchAlpineRootfs`, pin SHA-256, `assets/alpine/rootfs.bin`); pemasangan tanpa unduhan untuk aarch64 | selesai |
| 3 | Kendali lokal & data offline lewat jembatan: `offline`, `items`, `deadlines`, `sync`, `download`, `done`/`undone`, `open`, `pack`; jejak `[jembatan Moodle: …]` | selesai |
| 4 | Toolkit sandbox: modul Python `moodle.py` (PYTHONPATH), `jq`, `moodle help` | selesai |
| 5 | **Uji di ponsel root sungguhan** — izin uid `ai` menulis ke `filesDir/bridge` (bind-mount), kebijakan SELinux domain `su`, waktu tunggu 30 s; perbaiki dari temuan | berikutnya |
| 6 | Bundel multi-ABI (armv7/x86_64) bila dibutuhkan; `apk` offline cache untuk Python/PDF agar "Pasang Python & PDF" tidak butuh jaringan | rencana |
| 7 | Pekerjaan latar di Alpine: skrip buatan **pengguna** (bukan AI) dijadwalkan lewat WorkManager, dijalankan sebagai `ai`, hasil ke `/home/ai/work` dan dibagikan lewat share sheet | rencana |
| 8 | Jalur **tanpa root** lewat PRoot (`libproot.so` di `jniLibs`): sandbox yang sama, tanpa `su`; mount materi lewat `-b` | rencana, perlu riset biner PRoot Android |
| 9 | Operasi jembatan tambahan yang tetap baca-saja: pencarian global (`core_search_get_results`), pesan masuk (`core_message_get_messages`), kalender per bulan | rencana, lewat audit |

Yang **tidak** akan dibuat: operasi tulis ke Moodle dari sandbox, jaringan di sandbox, eksekusi perintah AI sebagai root, perintah yang bersumber dari teks Moodle/materi.

## Mencoba di ponsel (fase 5)

1. Profil → Lab Linux → Pasang Alpine (dari bundel) → Pasang Python, PDF & jq.
2. Akses AI ke Linux → **Tanya dulu**. Di chat (Agen LMS): "jalankan `moodle offline` di Linux". Izinkan permintaannya.
3. Yang diharapkan: JSON `{"ok":true,"data":{…}}` dan baris `[jembatan Moodle: offline]`. Bila "waktu habis menunggu aplikasi":
   lihat log Lab Linux — kemungkinan `PERINGATAN: jembatan Moodle tidak aktif` (bind-mount gagal) atau uid `ai` tidak boleh menulis ke folder (SELinux/izin).
   Tidak ada data yang bocor dalam kasus itu; jembatan hanya tidak berfungsi.
4. Laporkan `Profil → Bantuan → diagnostik` beserta keluaran `moodle help` dan `id` di sandbox.
