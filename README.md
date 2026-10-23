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
