// SPA kecil tanpa dependensi. Semua teks dari pengguna dirender via textContent (anti-XSS).
const $app = document.getElementById('app');
const $nav = document.getElementById('nav');
let token = localStorage.getItem('token');
let me = null;

// ---------- util ----------
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) el[k in el ? k : 'className'] = v;
  }
  for (const kid of kids.flat()) if (kid != null) el.append(kid);
  return el;
}
async function api(method, path, body) {
  const res = await fetch('/api' + path, {
    method,
    headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }) },
    body: body && JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || 'Gagal');
  return data;
}
const form = (fields, submitLabel, onSubmit) => {
  const err = h('div', { className: 'error' });
  const inputs = fields.map(([name, label, type = 'text']) =>
    [name, type === 'textarea' ? h('textarea', { rows: 4 }) : h('input', { type, required: true })]);
  const el = h('form', {
    onsubmit: async (e) => {
      e.preventDefault();
      err.textContent = '';
      try { await onSubmit(Object.fromEntries(inputs.map(([n, i]) => [n, i.value]))); }
      catch (ex) { err.textContent = ex.message; }
    },
  }, fields.map(([, label], i) => h('label', {}, label, inputs[i][1])), err, h('button', {}, submitLabel));
  return el;
};
const show = (...nodes) => $app.replaceChildren(...nodes.flat());

// ---------- nav & auth ----------
function renderNav() {
  $nav.replaceChildren(...(me ? [
    h('span', { className: 'muted' }, `${me.name} (${me.role})`),
    me.role === 'admin' ? h('a', { href: '#/users' }, 'Pengguna') : null,
    h('button', { className: 'ghost', onclick: () => { localStorage.removeItem('token'); token = me = null; route(); } }, 'Keluar'),
  ] : []).filter(Boolean));
}
function authView() {
  const login = form([['email', 'Email', 'email'], ['password', 'Password', 'password']], 'Masuk',
    async (v) => { token = (await api('POST', '/auth/login', v)).token; localStorage.setItem('token', token); route(); });
  const reg = form([['name', 'Nama'], ['email', 'Email', 'email'], ['password', 'Password (min 8)', 'password']], 'Daftar',
    async (v) => { token = (await api('POST', '/auth/register', v)).token; localStorage.setItem('token', token); route(); });
  show(h('div', { className: 'card' }, h('h2', {}, 'Masuk'), login),
    h('div', { className: 'card' }, h('h2', {}, 'Daftar akun baru'),
      h('p', { className: 'muted' }, 'Akun pertama otomatis menjadi admin.'), reg));
}

// ---------- views ----------
async function homeView() {
  const courses = await api('GET', '/courses');
  const canCreate = me.role !== 'student';
  show(
    canCreate && h('div', { className: 'card' }, h('h3', {}, 'Buat kursus'),
      form([['title', 'Judul'], ['description', 'Deskripsi', 'textarea']], 'Buat',
        async (v) => { const { id } = await api('POST', '/courses', v); location.hash = `#/course/${id}`; })),
    h('h2', {}, 'Daftar kursus'),
    courses.length ? courses.map((c) => h('div', { className: 'card' },
      h('div', { className: 'row between' },
        h('div', {}, h('a', { href: `#/course/${c.id}` }, h('strong', {}, c.title)), h('div', { className: 'muted' }, `Pengajar: ${c.teacher}`)),
        c.enrolled || c.owned || me.role === 'admin'
          ? h('span', { className: 'badge' }, c.owned ? 'Milik saya' : 'Terdaftar')
          : h('button', { onclick: async () => { await api('POST', `/courses/${c.id}/enroll`); route(); } }, 'Daftar')),
      h('p', {}, c.description))) : h('p', { className: 'muted' }, 'Belum ada kursus.'));
}

async function courseView(id) {
  let course;
  try { course = await api('GET', `/courses/${id}`); }
  catch (e) { return show(h('div', { className: 'error' }, e.message), h('a', { href: '#/' }, '← Kembali')); }
  const m = course.can_manage;
  show(
    h('a', { href: '#/' }, '← Semua kursus'),
    h('div', { className: 'row between' }, h('h2', {}, course.title),
      h('div', { className: 'row' }, h('a', { href: `#/grades/${id}` }, 'Buku nilai'),
        m && h('button', { className: 'danger', onclick: async () => { if (confirm('Hapus kursus?')) { await api('DELETE', `/courses/${id}`); location.hash = '#/'; } } }, 'Hapus'))),
    h('p', {}, course.description),
    course.items.map((it) => itemCard(it, m)),
    m && addItemForm(id));
}

function itemCard(it, manage) {
  const body = [];
  const reload = () => route();
  if (it.type === 'page') body.push(h('p', { style: 'white-space:pre-wrap' }, it.content));
  if (it.type === 'link') body.push(h('a', { href: /^https?:\/\//.test(it.content) ? it.content : '#', target: '_blank', rel: 'noopener' }, it.content));
  if (it.type === 'assignment') {
    body.push(h('p', { style: 'white-space:pre-wrap' }, it.content), h('div', { className: 'muted' }, `Nilai maks ${it.max_grade}${it.due_at ? ` · tenggat ${it.due_at}` : ''}`));
    if (manage) body.push(h('button', { className: 'ghost', onclick: () => (location.hash = `#/submissions/${it.id}`) }, 'Lihat pengumpulan'));
    else {
      const s = it.submission;
      if (s) body.push(h('div', { className: 'muted' }, s.grade == null ? 'Sudah dikirim, belum dinilai' : `Nilai: ${s.grade}/${it.max_grade}${s.feedback ? ` — ${s.feedback}` : ''}`));
      body.push(form([['text', 'Jawaban', 'textarea']], s ? 'Kirim ulang' : 'Kirim', async (v) => { await api('POST', `/items/${it.id}/submit`, v); reload(); }));
    }
  }
  if (it.type === 'quiz') {
    if (manage) body.push(h('ol', {}, it.questions.map((q) => h('li', {}, `${q.text} (kunci: ${q.options[q.correct]})`))));
    else {
      const answers = {};
      const result = h('div', { className: 'muted' }, it.best_score != null ? `Nilai terbaik: ${it.best_score}/${it.max_grade}` : '');
      body.push(h('div', {}, it.questions.map((q) => h('div', {}, h('strong', {}, q.text),
        q.options.map((o, i) => h('label', { style: 'display:block' }, h('input', { type: 'radio', name: `q${q.id}`, style: 'width:auto;margin-right:6px', onchange: () => (answers[q.id] = i) }), o)))),
        h('button', { onclick: async () => { const r = await api('POST', `/items/${it.id}/attempt`, { answers }); result.textContent = `Skor: ${r.score}/${r.max} (${r.correct}/${r.total} benar)`; } }, 'Kumpulkan'), result));
    }
  }
  return h('div', { className: 'card' },
    h('div', { className: 'row between' }, h('div', {}, h('span', { className: 'badge' }, it.type), ' ', h('strong', {}, it.title)),
      manage && h('button', { className: 'ghost', onclick: async () => { await api('DELETE', `/items/${it.id}`); reload(); } }, 'Hapus')),
    body);
}

function addItemForm(courseId) {
  const err = h('div', { className: 'error' });
  const type = h('select', {}, ['page', 'link', 'assignment', 'quiz'].map((t) => h('option', { value: t }, t)));
  const title = h('input', { placeholder: 'Judul' });
  const content = h('textarea', { rows: 3, placeholder: 'Isi / URL / instruksi tugas' });
  const max = h('input', { type: 'number', value: 100, placeholder: 'Nilai maks' });
  const qbox = h('textarea', { rows: 5, placeholder: 'Soal kuis, satu per baris:\nPertanyaan | opsi A | opsi B | opsi C | indeks-jawaban-benar (mulai 0)' });
  return h('div', { className: 'card' }, h('h3', {}, 'Tambah item'), type, title, content, max, qbox, err,
    h('button', {
      onclick: async () => {
        err.textContent = '';
        try {
          const questions = qbox.value.split('\n').filter(Boolean).map((l) => {
            const p = l.split('|').map((s) => s.trim());
            return { text: p[0], options: p.slice(1, -1), correct: Number(p.at(-1)) };
          });
          await api('POST', `/courses/${courseId}/items`, { type: type.value, title: title.value, content: content.value, max_grade: Number(max.value), ...(type.value === 'quiz' && { questions }) });
          route();
        } catch (e) { err.textContent = e.message; }
      },
    }, 'Tambah'));
}

async function submissionsView(itemId) {
  const subs = await api('GET', `/items/${itemId}/submissions`);
  show(h('a', { href: 'javascript:history.back()' }, '← Kembali'), h('h2', {}, 'Pengumpulan tugas'),
    subs.length ? subs.map((s) => {
      const grade = h('input', { type: 'number', value: s.grade ?? '', placeholder: 'Nilai' });
      const fb = h('input', { value: s.feedback ?? '', placeholder: 'Umpan balik' });
      const msg = h('span', { className: 'muted' });
      return h('div', { className: 'card' }, h('strong', {}, s.student), h('p', { style: 'white-space:pre-wrap' }, s.text), grade, fb,
        h('button', { onclick: async () => { try { await api('POST', `/submissions/${s.id}/grade`, { grade: Number(grade.value), feedback: fb.value }); msg.textContent = 'Tersimpan'; } catch (e) { msg.textContent = e.message; } } }, 'Simpan'), ' ', msg);
    }) : h('p', { className: 'muted' }, 'Belum ada pengumpulan.'));
}

async function gradesView(id) {
  const { items, rows } = await api('GET', `/courses/${id}/grades`);
  show(h('a', { href: `#/course/${id}` }, '← Kursus'), h('h2', {}, 'Buku nilai'),
    h('div', { className: 'scroll card' }, h('table', {},
      h('tr', {}, h('th', {}, 'Siswa'), items.map((i) => h('th', {}, `${i.title} /${i.max_grade}`)), h('th', {}, 'Total %')),
      rows.map((r) => h('tr', {}, h('td', {}, r.student), r.grades.map((g) => h('td', {}, g ?? '–')), h('td', {}, r.percent ?? '–'))))));
}

async function usersView() {
  const users = await api('GET', '/users');
  show(h('a', { href: '#/' }, '← Kembali'), h('h2', {}, 'Pengguna'), h('div', { className: 'card' }, h('table', {},
    users.map((u) => h('tr', {}, h('td', {}, u.name), h('td', {}, u.email),
      h('td', {}, h('select', { onchange: (e) => api('PUT', `/users/${u.id}/role`, { role: e.target.value }) },
        ['admin', 'teacher', 'student'].map((r) => h('option', { value: r, selected: r === u.role }, r))))))))); 
}

// ---------- router ----------
async function route() {
  if (!token) { me = null; renderNav(); return authView(); }
  try { me = await api('GET', '/me'); } catch { localStorage.removeItem('token'); token = me = null; renderNav(); return authView(); }
  renderNav();
  const [, page, arg] = location.hash.split('/');
  try {
    if (page === 'course') await courseView(arg);
    else if (page === 'submissions') await submissionsView(arg);
    else if (page === 'grades') await gradesView(arg);
    else if (page === 'users' && me.role === 'admin') await usersView();
    else await homeView();
  } catch (e) { show(h('div', { className: 'error' }, e.message)); }
}
addEventListener('hashchange', route);
route();
