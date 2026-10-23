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
