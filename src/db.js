import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

// DB_FILE=':memory:' dipakai untuk test
export function openDb(file = process.env.DB_FILE || 'data/lms.db') {
  if (file !== ':memory:') mkdirSync(dirname(file), { recursive: true });
  const db = new DatabaseSync(file);
  db.exec(`
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY, name TEXT NOT NULL,
      email TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL,
      role TEXT NOT NULL DEFAULT 'student' CHECK (role IN ('admin','teacher','student'))
    );
    CREATE TABLE IF NOT EXISTS courses (
      id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT DEFAULT '',
      teacher_id INTEGER NOT NULL REFERENCES users(id),
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
    CREATE TABLE IF NOT EXISTS enrollments (
      course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      PRIMARY KEY (course_id, user_id)
    );
    -- item = satu aktivitas/materi dalam kursus
    CREATE TABLE IF NOT EXISTS items (
      id INTEGER PRIMARY KEY,
      course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
      type TEXT NOT NULL CHECK (type IN ('page','link','assignment','quiz')),
      title TEXT NOT NULL, content TEXT DEFAULT '',
      max_grade REAL NOT NULL DEFAULT 100,
      due_at TEXT, position INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS questions (
      id INTEGER PRIMARY KEY,
      item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
      text TEXT NOT NULL, options TEXT NOT NULL, correct INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS submissions (
      id INTEGER PRIMARY KEY,
      item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      text TEXT NOT NULL, grade REAL, feedback TEXT,
      submitted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE (item_id, user_id)
    );
    CREATE TABLE IF NOT EXISTS attempts (
      id INTEGER PRIMARY KEY,
      item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      score REAL NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
    );
    -- belajar mandiri: penanda selesai, kartu hafalan (SRS), log review harian
    CREATE TABLE IF NOT EXISTS completions (
      item_id INTEGER NOT NULL REFERENCES items(id) ON DELETE CASCADE,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      day TEXT NOT NULL,
      PRIMARY KEY (item_id, user_id)
    );
    CREATE TABLE IF NOT EXISTS cards (
      id INTEGER PRIMARY KEY,
      course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      front TEXT NOT NULL, back TEXT NOT NULL,
      ease REAL NOT NULL DEFAULT 2.5, interval INTEGER NOT NULL DEFAULT 0, reps INTEGER NOT NULL DEFAULT 0,
      due_at TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS cards_due ON cards (user_id, due_at);
    CREATE TABLE IF NOT EXISTS review_log (
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      card_id INTEGER, rating TEXT NOT NULL, day TEXT NOT NULL
    );
    CREATE INDEX IF NOT EXISTS review_log_day ON review_log (user_id, day);
    -- impor dari Moodle kampus (read-only mirror; token disimpan, password tidak)
    CREATE TABLE IF NOT EXISTS moodle_connections (
      user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
      url TEXT NOT NULL, token TEXT NOT NULL, moodle_userid INTEGER NOT NULL,
      site_name TEXT, last_sync TEXT
    );
    CREATE TABLE IF NOT EXISTS external_grades (
      course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      name TEXT NOT NULL, grade REAL, max REAL,
      PRIMARY KEY (course_id, user_id, name)
    );
  `);
  // migrasi untuk DB lama: kolom external_id penanda data hasil impor (agar sinkron ulang = upsert)
  for (const t of ['courses', 'items']) {
    if (!db.prepare(`PRAGMA table_info(${t})`).all().some((c) => c.name === 'external_id')) db.exec(`ALTER TABLE ${t} ADD COLUMN external_id TEXT`);
  }
  db.exec(`
    CREATE UNIQUE INDEX IF NOT EXISTS courses_ext ON courses (teacher_id, external_id) WHERE external_id IS NOT NULL;
    CREATE UNIQUE INDEX IF NOT EXISTS items_ext ON items (course_id, external_id) WHERE external_id IS NOT NULL;
  `);
  return db;
}
