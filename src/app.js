import express from 'express';
import { hashPassword, verifyPassword, issueToken, readToken } from './auth.js';

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
const fail = (status, msg) => { throw new HttpError(status, msg); };
const need = (cond, msg = 'Data tidak valid') => { if (!cond) fail(400, msg); };
const str = (v) => typeof v === 'string' && v.trim() ? v.trim() : null;

export function createApp(db) {
  const app = express();
  app.use(express.json({ limit: '1mb' }));
  app.use(express.static(new URL('../public', import.meta.url).pathname));

  const q = (sql) => db.prepare(sql);

  // ---------- helpers ----------
  const publicUser = (u) => ({ id: u.id, name: u.name, email: u.email, role: u.role });
  const getCourse = (id) => q('SELECT * FROM courses WHERE id = ?').get(id) ?? fail(404, 'Kursus tidak ditemukan');
  const canManage = (user, course) => user.role === 'admin' || course.teacher_id === user.id;
  const isEnrolled = (user, courseId) =>
    !!q('SELECT 1 FROM enrollments WHERE course_id = ? AND user_id = ?').get(courseId, user.id);
  const assertView = (user, course) =>
    (canManage(user, course) || isEnrolled(user, course.id)) || fail(403, 'Belum terdaftar di kursus ini');
  const getItem = (id) => q('SELECT * FROM items WHERE id = ?').get(id) ?? fail(404, 'Item tidak ditemukan');

  // ---------- middleware ----------
  const auth = (req, _res, next) => {
    const uid = readToken((req.headers.authorization || '').replace(/^Bearer /, ''));
    const user = uid && q('SELECT * FROM users WHERE id = ?').get(uid);
    if (!user) return next(new HttpError(401, 'Perlu login'));
    req.user = user;
    next();
  };
  const role = (...roles) => (req, _res, next) =>
    roles.includes(req.user.role) ? next() : next(new HttpError(403, 'Akses ditolak'));
  const route = (fn) => (req, res, next) => { try { fn(req, res); } catch (e) { next(e); } };

  // ---------- auth ----------
  app.post('/api/auth/register', route((req, res) => {
    const { name, email, password } = req.body ?? {};
    need(str(name) && str(email) && typeof password === 'string' && password.length >= 8,
      'Nama, email, dan password (min 8 karakter) wajib diisi');
    if (q('SELECT 1 FROM users WHERE email = ?').get(email.toLowerCase())) fail(409, 'Email sudah terdaftar');
    // pengguna pertama otomatis menjadi admin
    const first = q('SELECT COUNT(*) AS n FROM users').get().n === 0;
    const { lastInsertRowid: id } = q('INSERT INTO users (name, email, password_hash, role) VALUES (?,?,?,?)')
      .run(name.trim(), email.toLowerCase(), hashPassword(password), first ? 'admin' : 'student');
    res.status(201).json({ token: issueToken(Number(id)) });
  }));

  app.post('/api/auth/login', route((req, res) => {
    const { email, password } = req.body ?? {};
    const u = typeof email === 'string' && q('SELECT * FROM users WHERE email = ?').get(email.toLowerCase());
    if (!u || typeof password !== 'string' || !verifyPassword(password, u.password_hash)) fail(401, 'Email atau password salah');
    res.json({ token: issueToken(u.id) });
  }));

  app.get('/api/me', auth, route((req, res) => res.json(publicUser(req.user))));

  // ---------- admin: pengguna ----------
  app.get('/api/users', auth, role('admin'), route((_req, res) =>
    res.json(q('SELECT id, name, email, role FROM users ORDER BY id').all())));

  app.put('/api/users/:id/role', auth, role('admin'), route((req, res) => {
    need(['admin', 'teacher', 'student'].includes(req.body?.role), 'Role tidak valid');
    const r = q('UPDATE users SET role = ? WHERE id = ?').run(req.body.role, req.params.id);
    if (!r.changes) fail(404, 'Pengguna tidak ditemukan');
    res.json({ ok: true });
  }));

  // ---------- kursus ----------
  app.get('/api/courses', auth, route((req, res) => {
    const rows = q(`
      SELECT c.id, c.title, c.description, u.name AS teacher,
             c.teacher_id = ? AS owned,
             EXISTS (SELECT 1 FROM enrollments e WHERE e.course_id = c.id AND e.user_id = ?) AS enrolled
      FROM courses c JOIN users u ON u.id = c.teacher_id ORDER BY c.id DESC`).all(req.user.id, req.user.id);
    res.json(rows.map((r) => ({ ...r, owned: !!r.owned, enrolled: !!r.enrolled })));
  }));

  app.post('/api/courses', auth, role('admin', 'teacher'), route((req, res) => {
    need(str(req.body?.title), 'Judul wajib diisi');
    const { lastInsertRowid: id } = q('INSERT INTO courses (title, description, teacher_id) VALUES (?,?,?)')
      .run(req.body.title.trim(), req.body.description ?? '', req.user.id);
    res.status(201).json({ id: Number(id) });
  }));

  app.post('/api/courses/:id/enroll', auth, route((req, res) => {
    getCourse(req.params.id);
    q('INSERT OR IGNORE INTO enrollments (course_id, user_id) VALUES (?,?)').run(req.params.id, req.user.id);
    res.json({ ok: true });
  }));

  app.get('/api/courses/:id', auth, route((req, res) => {
    const course = getCourse(req.params.id);
    assertView(req.user, course);
    const manage = canManage(req.user, course);
    const items = q('SELECT * FROM items WHERE course_id = ? ORDER BY position, id').all(course.id).map((it) => {
      const out = { ...it };
      if (it.type === 'quiz') {
        // siswa tidak boleh melihat kunci jawaban
        out.questions = q('SELECT id, text, options, correct FROM questions WHERE item_id = ? ORDER BY id').all(it.id)
          .map(({ correct, options, ...rest }) => ({ ...rest, options: JSON.parse(options), ...(manage ? { correct } : {}) }));
      }
      if (!manage && it.type === 'assignment') {
        out.submission = q('SELECT text, grade, feedback, submitted_at FROM submissions WHERE item_id = ? AND user_id = ?')
          .get(it.id, req.user.id) ?? null;
      }
      if (!manage && it.type === 'quiz') {
        out.best_score = q('SELECT MAX(score) AS s FROM attempts WHERE item_id = ? AND user_id = ?').get(it.id, req.user.id).s;
      }
      return out;
    });
    res.json({ ...course, can_manage: manage, items });
  }));

  app.delete('/api/courses/:id', auth, route((req, res) => {
    if (!canManage(req.user, getCourse(req.params.id))) fail(403, 'Akses ditolak');
    q('DELETE FROM courses WHERE id = ?').run(req.params.id);
    res.json({ ok: true });
  }));

  // ---------- item (materi/tugas/kuis) ----------
  app.post('/api/courses/:id/items', auth, route((req, res) => {
    const course = getCourse(req.params.id);
    if (!canManage(req.user, course)) fail(403, 'Akses ditolak');
    const { type, title, content = '', max_grade = 100, due_at = null, questions = [] } = req.body ?? {};
    need(['page', 'link', 'assignment', 'quiz'].includes(type) && str(title), 'Tipe dan judul wajib diisi');
    need(typeof max_grade === 'number' && max_grade > 0, 'Nilai maksimum tidak valid');
    if (type === 'quiz') {
      need(Array.isArray(questions) && questions.length > 0, 'Kuis butuh minimal 1 soal');
      for (const qu of questions) {
        need(str(qu.text) && Array.isArray(qu.options) && qu.options.length >= 2
          && Number.isInteger(qu.correct) && qu.correct >= 0 && qu.correct < qu.options.length, 'Soal tidak valid');
      }
    }
    const pos = q('SELECT COALESCE(MAX(position), 0) + 1 AS p FROM items WHERE course_id = ?').get(course.id).p;
    const { lastInsertRowid } = q('INSERT INTO items (course_id, type, title, content, max_grade, due_at, position) VALUES (?,?,?,?,?,?,?)')
      .run(course.id, type, title.trim(), String(content), max_grade, due_at, pos);
    const id = Number(lastInsertRowid);
    if (type === 'quiz') {
      const ins = q('INSERT INTO questions (item_id, text, options, correct) VALUES (?,?,?,?)');
      for (const qu of questions) ins.run(id, qu.text.trim(), JSON.stringify(qu.options.map(String)), qu.correct);
    }
    res.status(201).json({ id });
  }));

  app.delete('/api/items/:id', auth, route((req, res) => {
    const item = getItem(req.params.id);
    if (!canManage(req.user, getCourse(item.course_id))) fail(403, 'Akses ditolak');
    q('DELETE FROM items WHERE id = ?').run(item.id);
    res.json({ ok: true });
  }));

  // ---------- tugas ----------
  app.post('/api/items/:id/submit', auth, route((req, res) => {
    const item = getItem(req.params.id);
    need(item.type === 'assignment', 'Bukan tugas');
    if (!isEnrolled(req.user, item.course_id)) fail(403, 'Belum terdaftar di kursus ini');
    if (item.due_at && Date.parse(item.due_at) < Date.now()) fail(403, 'Tenggat sudah lewat');
    need(str(req.body?.text), 'Jawaban kosong');
    // kirim ulang menimpa jawaban lama dan mereset nilai
    q(`INSERT INTO submissions (item_id, user_id, text) VALUES (?,?,?)
       ON CONFLICT (item_id, user_id) DO UPDATE SET text = excluded.text, grade = NULL, feedback = NULL,
       submitted_at = CURRENT_TIMESTAMP`).run(item.id, req.user.id, req.body.text.trim());
    res.json({ ok: true });
  }));

  app.get('/api/items/:id/submissions', auth, route((req, res) => {
    const item = getItem(req.params.id);
    if (!canManage(req.user, getCourse(item.course_id))) fail(403, 'Akses ditolak');
    res.json(q(`SELECT s.id, s.text, s.grade, s.feedback, s.submitted_at, u.name AS student
                FROM submissions s JOIN users u ON u.id = s.user_id WHERE s.item_id = ? ORDER BY s.submitted_at`).all(item.id));
  }));

  app.post('/api/submissions/:id/grade', auth, route((req, res) => {
    const sub = q('SELECT * FROM submissions WHERE id = ?').get(req.params.id) ?? fail(404, 'Pengumpulan tidak ditemukan');
    const item = getItem(sub.item_id);
    if (!canManage(req.user, getCourse(item.course_id))) fail(403, 'Akses ditolak');
    const { grade, feedback = '' } = req.body ?? {};
    need(typeof grade === 'number' && grade >= 0 && grade <= item.max_grade, `Nilai harus 0–${item.max_grade}`);
    q('UPDATE submissions SET grade = ?, feedback = ? WHERE id = ?').run(grade, String(feedback), sub.id);
    res.json({ ok: true });
  }));

  // ---------- kuis ----------
  app.post('/api/items/:id/attempt', auth, route((req, res) => {
    const item = getItem(req.params.id);
    need(item.type === 'quiz', 'Bukan kuis');
    if (!isEnrolled(req.user, item.course_id)) fail(403, 'Belum terdaftar di kursus ini');
    const answers = req.body?.answers;
    need(answers && typeof answers === 'object', 'Jawaban wajib dikirim');
    const qs = q('SELECT id, correct FROM questions WHERE item_id = ?').all(item.id);
    const right = qs.filter((x) => answers[x.id] === x.correct).length;
    const score = Math.round((right / qs.length) * item.max_grade * 100) / 100;
    q('INSERT INTO attempts (item_id, user_id, score) VALUES (?,?,?)').run(item.id, req.user.id, score);
    res.json({ score, max: item.max_grade, correct: right, total: qs.length });
  }));

  // ---------- buku nilai ----------
  app.get('/api/courses/:id/grades', auth, route((req, res) => {
    const course = getCourse(req.params.id);
    assertView(req.user, course);
    const manage = canManage(req.user, course);
    const students = manage
      ? q('SELECT u.id, u.name FROM enrollments e JOIN users u ON u.id = e.user_id WHERE e.course_id = ? ORDER BY u.name').all(course.id)
      : [{ id: req.user.id, name: req.user.name }];
    const items = q("SELECT id, title, type, max_grade FROM items WHERE course_id = ? AND type IN ('assignment','quiz') ORDER BY position, id").all(course.id);
    const sub = q('SELECT grade FROM submissions WHERE item_id = ? AND user_id = ?');
    const att = q('SELECT MAX(score) AS s FROM attempts WHERE item_id = ? AND user_id = ?');
    const rows = students.map((s) => {
      const grades = items.map((it) => (it.type === 'assignment' ? sub.get(it.id, s.id)?.grade : att.get(it.id, s.id).s) ?? null);
      // total = jumlah nilai yang sudah ada / jumlah maksimum seluruh item, dalam persen
      const total = items.reduce((a, it, i) => a + (grades[i] ?? 0), 0);
      const max = items.reduce((a, it) => a + it.max_grade, 0);
      return { student: s.name, grades, percent: max ? Math.round((total / max) * 1000) / 10 : null };
    });
    res.json({ items, rows });
  }));

  // ---------- error handler ----------
  app.use('/api', (_req, _res, next) => next(new HttpError(404, 'Endpoint tidak ditemukan')));
  app.use((err, _req, res, _next) => {
    if (err instanceof HttpError) return res.status(err.status).json({ error: err.message });
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'JSON tidak valid' });
    console.error(err);
    res.status(500).json({ error: 'Kesalahan server' });
  });

  return app;
}
