// SPA kecil tanpa dependensi. Semua teks dari pengguna dirender via textContent (anti-XSS).
const $app = document.getElementById('app');
const $nav = document.getElementById('nav');
let token = localStorage.getItem('token');
let me = null;
let aiEnabled = false;

// ---------- util ----------
function h(tag, attrs = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (v !== false && v != null) el[k in el ? k : 'className'] = v;
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid);
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
const show = (...nodes) => $app.replaceChildren(...nodes.flat().filter((n) => n != null && n !== false));

// ---------- nav & auth ----------
function renderNav() {
  $nav.replaceChildren(...(me ? [
    h('span', { className: 'muted' }, `${me.name} (${me.role})`),
    h('a', { href: '#/moodle' }, 'Moodle kampus'),
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
  const st = await api('GET', '/study/stats');
  const deadlines = (await api('GET', '/deadlines')).filter((d) => !d.completed);
  show(
    h('div', { className: 'card row between' },
      h('div', {}, h('strong', {}, `🔥 ${st.streak} hari beruntun`), h('div', { className: 'muted' }, `${st.reviewed_today} kartu direview hari ini`)),
      st.due ? h('button', { onclick: () => (location.hash = '#/review') }, `Review ${st.due} kartu`) : h('span', { className: 'muted' }, 'Tidak ada kartu jatuh tempo ✓')),
    deadlines.length > 0 && h('div', { className: 'card' }, h('strong', {}, 'Tenggat mendatang'),
      deadlines.slice(0, 8).map((d) => h('div', { className: 'row between' },
        h('a', { href: `#/course/${d.course_id}` }, `${d.title} — ${d.course}`),
        h('span', { className: 'muted' }, new Date(d.due_at).toLocaleString('id-ID', { dateStyle: 'medium', timeStyle: 'short' }))))),
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
    h('div', { className: 'card' }, h('div', { className: 'muted' }, `Progres: ${course.progress.done}/${course.progress.total} item selesai`),
      h('progress', { value: course.progress.done, max: Math.max(course.progress.total, 1), style: 'width:100%' })),
    externalGradesPanel(id),
    cardsPanel(id),
    aiEnabled && tutorPanel(id),
    course.items.map((it) => itemCard(it, m)),
    m && aiEnabled && aiQuizPanel(id),
    m && addItemForm(id));
}

function itemCard(it, manage) {
  const body = [];
  const reload = () => route();
  if (it.type === 'page') body.push(h('p', { style: 'white-space:pre-wrap' }, it.content));
  if (it.type === 'link') body.push(h('a', { href: /^https?:\/\//.test(it.content) ? it.content : '#', target: '_blank', rel: 'noopener' }, it.content));
  if (it.type === 'assignment') {
    body.push(h('p', { style: 'white-space:pre-wrap' }, it.content), h('div', { className: 'muted' }, `Nilai maks ${it.max_grade}${it.due_at ? ` · tenggat ${it.due_at}` : ''}`));
    if (it.external) body.push(h('div', { className: 'muted' }, 'Tugas dari Moodle kampus: kumpulkan dan lihat nilainya di sana.'));
    else if (manage) body.push(h('button', { className: 'ghost', onclick: () => (location.hash = `#/submissions/${it.id}`) }, 'Lihat pengumpulan'));
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
      h('div', { className: 'row' },
        h('label', { className: 'muted', style: 'white-space:nowrap' }, h('input', { type: 'checkbox', checked: it.completed, style: 'width:auto;margin:0 4px 0 0',
          onchange: async (e) => { await api('POST', `/items/${it.id}/complete`, { done: e.target.checked }); reload(); } }), 'Selesai'),
        manage && h('button', { className: 'ghost', onclick: async () => { await api('DELETE', `/items/${it.id}`); reload(); } }, 'Hapus'))),
    body);
}

function externalGradesPanel(courseId) {
  const box = h('div');
  api('GET', `/courses/${courseId}/external-grades`).then((rows) => {
    if (!rows.length) return;
    box.className = 'card';
    box.append(h('strong', {}, 'Nilai dari Moodle kampus'), h('table', {}, rows.map((g) =>
      h('tr', {}, h('td', {}, g.name), h('td', {}, g.grade == null ? '–' : `${g.grade}${g.max ? ` / ${g.max}` : ''}`)))));
  }).catch(() => {});
  return box;
}

function cardsPanel(courseId) {
  const front = h('input', { placeholder: 'Depan (pertanyaan)' });
  const back = h('input', { placeholder: 'Belakang (jawaban)' });
  const list = h('div');
  const draft = h('div');
  const load = async () => {
    const cards = await api('GET', `/courses/${courseId}/cards`);
    list.replaceChildren(h('div', { className: 'muted' }, `${cards.length} kartu`), ...cards.map((c) => h('div', { className: 'row between' },
      h('span', {}, `${c.front} → ${c.back}`), h('button', { className: 'ghost', onclick: async () => { await api('DELETE', `/cards/${c.id}`); load(); } }, '×'))));
  };
  load();
  const aiBtn = aiEnabled && h('button', { className: 'ghost', onclick: async () => {
    draft.replaceChildren(h('span', { className: 'muted' }, 'Membuat kartu…'));
    try {
      const { cards } = await api('POST', `/courses/${courseId}/ai/cards`, { count: 10 });
      draft.replaceChildren(h('ol', {}, cards.map((c) => h('li', {}, `${c.front} → ${c.back}`))),
        h('button', { onclick: async () => { await api('POST', `/courses/${courseId}/cards`, { cards }); draft.replaceChildren(); load(); } }, 'Simpan semua'),
        h('span', { className: 'muted' }, ' Tinjau dulu; belum tersimpan.'));
    } catch (e) { draft.replaceChildren(h('div', { className: 'error' }, e.message)); }
  } }, 'Buat kartu dengan AI');
  return h('div', { className: 'card' }, h('div', { className: 'row between' }, h('strong', {}, 'Kartu hafalan'),
    h('button', { className: 'ghost', onclick: () => (location.hash = `#/review/${courseId}`) }, 'Mulai review')),
    front, back, h('div', { className: 'row' }, h('button', { onclick: async () => {
      if (!front.value.trim() || !back.value.trim()) return;
      await api('POST', `/courses/${courseId}/cards`, { front: front.value, back: back.value }); front.value = back.value = ''; load();
    } }, 'Tambah kartu'), aiBtn), draft, list);
}

async function reviewView(courseId) {
  const queue = await api('GET', `/cards/due${courseId ? `?course=${courseId}` : ''}`);
  const back = h('a', { href: courseId ? `#/course/${courseId}` : '#/' }, '← Kembali');
  let done = 0;
  const next = () => {
    if (!queue.length) return show(back, h('div', { className: 'card' }, h('h2', {}, 'Selesai 🎉'), h('p', {}, `${done} kartu direview. Sampai jumpa besok!`)));
    const card = queue[0];
    const answer = h('div', { style: 'display:none' });
    const rate = (rating, label) => h('button', { className: rating === 'again' ? 'danger' : '', onclick: async () => {
      const r = await api('POST', `/cards/${card.id}/review`, { rating });
      queue.shift(); done += 1;
      if (r.interval === 0) queue.push(card); // lupa: muncul lagi di sesi ini
      next();
    } }, label);
    answer.append(h('hr'), h('p', { style: 'white-space:pre-wrap' }, card.back), h('div', { className: 'row' },
      rate('again', 'Lupa'), rate('hard', 'Sulit'), rate('good', 'Bisa'), rate('easy', 'Mudah')));
    const reveal = h('button', { onclick: () => { answer.style.display = 'block'; reveal.style.display = 'none'; } }, 'Tampilkan jawaban');
    show(back, h('div', { className: 'muted' }, `Sisa ${queue.length} · ${card.course}`),
      h('div', { className: 'card' }, h('h3', { style: 'white-space:pre-wrap' }, card.front), reveal, answer));
  };
  next();
}

function tutorPanel(courseId) {
  const q = h('input', { placeholder: 'Tanya tutor AI tentang materi kursus ini…' });
  const out = h('p', { style: 'white-space:pre-wrap' });
  const btn = h('button', { onclick: async () => {
    out.textContent = 'Memproses…'; btn.disabled = true;
    try { out.textContent = (await api('POST', `/courses/${courseId}/ai/ask`, { question: q.value })).answer; }
    catch (e) { out.textContent = e.message; }
    btn.disabled = false;
  } }, 'Tanya');
  return h('div', { className: 'card' }, h('strong', {}, 'Tutor AI'), q, btn, out);
}

function aiQuizPanel(courseId) {
  const count = h('input', { type: 'number', value: 5, min: 1, max: 20 });
  const title = h('input', { placeholder: 'Judul kuis' , value: 'Kuis (AI)' });
  const out = h('div');
  const btn = h('button', { onclick: async () => {
    out.replaceChildren(h('span', { className: 'muted' }, 'Membuat soal…')); btn.disabled = true;
    try {
      const { questions } = await api('POST', `/courses/${courseId}/ai/quiz`, { count: Number(count.value) });
      out.replaceChildren(h('ol', {}, questions.map((q) => h('li', {}, `${q.text} — kunci: ${q.options[q.correct]}`))),
        h('button', { onclick: async () => { await api('POST', `/courses/${courseId}/items`, { type: 'quiz', title: title.value, max_grade: 100, questions }); route(); } }, 'Simpan sebagai kuis'),
        h('span', { className: 'muted' }, ' Tinjau dulu; soal tidak otomatis tersimpan.'));
    } catch (e) { out.replaceChildren(h('div', { className: 'error' }, e.message)); }
    btn.disabled = false;
  } }, 'Buat draf soal');
  return h('div', { className: 'card' }, h('strong', {}, 'Buat kuis dari materi dengan AI'), h('label', {}, 'Jumlah soal', count), title, btn, out);
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
      const suggest = aiEnabled && h('button', { className: 'ghost', onclick: async () => {
        msg.textContent = 'AI menilai…';
        try { const r = await api('POST', `/submissions/${s.id}/ai/suggest`); grade.value = r.grade; fb.value = r.feedback; msg.textContent = 'Saran AI terisi; periksa lalu simpan'; }
        catch (e) { msg.textContent = e.message; }
      } }, 'Saran nilai AI');
      return h('div', { className: 'card' }, h('strong', {}, s.student), h('p', { style: 'white-space:pre-wrap' }, s.text), grade, fb, suggest, ' ',
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

async function moodleView() {
  const st = await api('GET', '/moodle');
  const msg = h('div', { className: 'muted' });
  const err = h('div', { className: 'error' });
  const guard = (fn) => async () => { err.textContent = ''; try { await fn(); } catch (e) { err.textContent = e.message; } };

  if (st.connected) {
    return show(h('a', { href: '#/' }, '← Kembali'), h('h2', {}, 'Moodle kampus'),
      h('div', { className: 'card' },
        h('p', {}, `Terhubung ke ${st.site_name || st.url}`), h('div', { className: 'muted' }, st.url),
        h('div', { className: 'muted' }, st.last_sync ? `Sinkron terakhir: ${new Date(st.last_sync).toLocaleString('id-ID')}` : 'Belum pernah sinkron'),
        h('div', { className: 'row' },
          h('button', { onclick: guard(async () => {
            msg.textContent = 'Menarik mata kuliah, materi, tugas, dan nilai…';
            const r = await api('POST', '/moodle/sync');
            msg.textContent = `Selesai: ${r.courses} kursus, ${r.items} item, ${r.grades} nilai.${r.warnings.length ? ` Peringatan: ${r.warnings.join('; ')}` : ''}`;
          }) }, 'Sinkron sekarang'),
          h('button', { className: 'ghost', onclick: guard(async () => { await api('DELETE', '/moodle'); route(); }) }, 'Putuskan')),
        msg, err),
      h('p', { className: 'muted' }, 'Data hasil impor bersifat baca-saja. Materi bisa dipakai untuk kartu hafalan dan tutor AI; tugas dikumpulkan di Moodle kampus.'));
  }
  const url = h('input', { placeholder: 'https://lms.kampus.ac.id', type: 'url' });
  const user = h('input', { placeholder: 'Username kampus', autocomplete: 'username' });
  const pass = h('input', { type: 'password', placeholder: 'Password kampus', autocomplete: 'current-password' });
  const token = h('input', { placeholder: 'Token (opsional; ganti username+password)', autocomplete: 'off' });
  show(h('a', { href: '#/' }, '← Kembali'), h('h2', {}, 'Hubungkan Moodle kampus'),
    h('div', { className: 'card' },
      h('p', { className: 'muted' }, 'Password hanya dipakai sekali untuk meminta token resmi dari Moodle dan tidak disimpan. Hanya token yang disimpan di server ini. Alternatif: tempel token dari Moodle (Preferensi → Kunci keamanan) dan kosongkan username/password.'),
      url, user, pass, token, h('button', { onclick: guard(async () => {
        const body = token.value.trim() ? { url: url.value, token: token.value } : { url: url.value, username: user.value, password: pass.value };
        await api('POST', '/moodle/connect', body); route();
      }) }, 'Hubungkan'), err));
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
  try { me = await api('GET', '/me'); aiEnabled = (await api('GET', '/ai/status')).enabled; } catch { localStorage.removeItem('token'); token = me = null; renderNav(); return authView(); }
  renderNav();
  const [, page, arg] = location.hash.split('/');
  try {
    if (page === 'course') await courseView(arg);
    else if (page === 'submissions') await submissionsView(arg);
    else if (page === 'grades') await gradesView(arg);
    else if (page === 'review') await reviewView(arg);
    else if (page === 'moodle') await moodleView();
    else if (page === 'users' && me.role === 'admin') await usersView();
    else await homeView();
  } catch (e) { show(h('div', { className: 'error' }, e.message)); }
}
addEventListener('hashchange', route);
route();
