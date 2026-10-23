import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

process.env.MOODLE_ALLOW_PRIVATE = 'true'; // fake Moodle berjalan di localhost
const { createApp } = await import('../src/app.js');
const { openDb } = await import('../src/db.js');
const { htmlToText, normalizeBaseUrl } = await import('../src/moodle.js');

// ---- Moodle palsu: meniru endpoint WS yang dipakai ----
const future = Math.floor(Date.now() / 1000) + 86400 * 3;
const calls = [];
const WS = {
  core_webservice_get_site_info: () => ({ userid: 7, fullname: 'Budi', sitename: 'Kampus X' }),
  core_enrol_get_users_courses: () => [{ id: 11, fullname: 'Algoritma', shortname: 'ALGO', summary: '<p>Dasar &amp; lanjut</p>' }],
  mod_assign_get_assignments: () => ({ courses: [{ id: 11, assignments: [{ cmid: 3, name: 'PR1', intro: '<p>Kerjakan</p>', duedate: future, grade: 80 }] }] }),
  mod_page_get_pages_by_courses: () => ({ pages: [{ coursemodule: 2, content: '<b>Isi</b> halaman' }] }),
  core_course_get_contents: () => [{ name: 'Minggu 1', modules: [
    { id: 2, modname: 'page', name: 'Intro' },
    { id: 3, modname: 'assign', name: 'PR1' },
    { id: 4, modname: 'resource', name: 'Slide', url: 'https://kampus.example/mod/resource/view.php?id=4' },
    { id: 5, modname: 'forum', name: 'Rahasia', url: 'x', uservisible: false },
  ] }],
  gradereport_user_get_grade_items: () => ({ usergrades: [{ gradeitems: [{ itemname: 'PR1', graderaw: 70, grademax: 80 }, { itemtype: 'course', itemname: null, graderaw: 70, grademax: 80 }] }] }),
};
const moodle = http.createServer((req, res) => {
  let body = ''; req.on('data', (d) => (body += d)).on('end', () => {
    const f = new URLSearchParams(body);
    calls.push({ url: req.url, form: Object.fromEntries(f) });
    res.setHeader('content-type', 'application/json');
    if (req.url === '/login/token.php') return res.end(JSON.stringify(f.get('password') === 'benar' ? { token: 'TOK123' } : { error: 'Invalid login', errorcode: 'invalidlogin' }));
    if (f.get('wstoken') !== 'TOK123') return res.end(JSON.stringify({ exception: 'x', errorcode: 'invalidtoken', message: 'Invalid token' }));
    res.end(JSON.stringify(WS[f.get('wsfunction')]()));
  });
}).listen(0);
const murl = `http://localhost:${moodle.address().port}`;

const server = createApp(openDb(':memory:'), { ai: null }).listen(0);
test.after(() => { server.close(); moodle.close(); });
const base = `http://localhost:${server.address().port}`;
const call = async (method, path, body, token) => {
  const res = await fetch(base + path, { method, headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }) }, body: body && JSON.stringify(body) });
  return { status: res.status, body: await res.json() };
};

test('htmlToText & penolakan URL tidak aman', async () => {
  assert.equal(htmlToText('<p>A &amp; B</p><script>x()</script><br>C'), 'A & B\n\nC');
  process.env.MOODLE_ALLOW_PRIVATE = 'false';
  try {
    await assert.rejects(normalizeBaseUrl('http://kampus.example'), /https/);
    await assert.rejects(normalizeBaseUrl('https://127.0.0.1'), /internal/);
    await assert.rejects(normalizeBaseUrl('https://[::1]'), /internal/);
    await assert.rejects(normalizeBaseUrl('https://169.254.169.254/latest'), /internal/); // metadata cloud
    await assert.rejects(normalizeBaseUrl('https://[::ffff:127.0.0.1]'), /internal/);
    // alamat publik harus lolos (regresi: aturan IPv6 pernah memblokir semua IPv4)
    assert.equal(await normalizeBaseUrl('https://143.198.212.12/'), 'https://143.198.212.12');
    assert.equal(await normalizeBaseUrl('https://8.8.8.8/moodle/'), 'https://8.8.8.8/moodle');
  } finally { process.env.MOODLE_ALLOW_PRIVATE = 'true'; }
});

test('connect (password), sync semua data, sinkron ulang idempoten', async () => {
  const t = (await call('POST', '/api/auth/register', { name: 'Budi', email: 'b@x.id', password: 'password123' })).body.token;

  assert.equal((await call('POST', '/api/moodle/connect', { url: murl, username: 'budi', password: 'salah' }, t)).status, 502);
  assert.equal((await call('POST', '/api/moodle/sync', null, t)).status, 400); // belum terhubung

  const c = await call('POST', '/api/moodle/connect', { url: murl, username: 'budi', password: 'benar' }, t);
  assert.equal(c.body.site_name, 'Kampus X');
  const st = (await call('GET', '/api/moodle', null, t)).body;
  assert.equal(st.connected, true);
  assert.equal('token' in st, false); // token tidak bocor ke klien

  const s1 = (await call('POST', '/api/moodle/sync', null, t)).body;
  assert.deepEqual({ courses: s1.courses, items: s1.items, grades: s1.grades }, { courses: 1, items: 3, grades: 2 });

  const list = (await call('GET', '/api/courses', null, t)).body;
  assert.equal(list.length, 1);
  const course = (await call('GET', `/api/courses/${list[0].id}`, null, t)).body;
  assert.equal(course.title, 'Algoritma');
  assert.equal(course.description, 'Dasar & lanjut');
  const byTitle = Object.fromEntries(course.items.map((i) => [i.title, i]));
  assert.equal(byTitle['Minggu 1 › Intro'].content, 'Isi halaman');
  assert.equal(byTitle['Minggu 1 › PR1'].max_grade, 80);
  assert.equal(byTitle['Minggu 1 › Slide'].type, 'link');
  assert.equal(course.items.some((i) => i.title.includes('Rahasia')), false); // modul tersembunyi dilewati
  assert.equal(course.items.every((i) => i.external && !('external_id' in i)), true);

  // tenggat & nilai
  const dl = (await call('GET', '/api/deadlines', null, t)).body;
  assert.equal(dl.length, 1);
  assert.equal(dl[0].course, 'Algoritma');
  assert.deepEqual((await call('GET', `/api/courses/${course.id}/external-grades`, null, t)).body.map((g) => g.name), ['PR1', 'Total kursus']);

  // tugas dari Moodle tidak bisa dikumpulkan lokal
  assert.equal((await call('POST', `/api/items/${byTitle['Minggu 1 › PR1'].id}/submit`, { text: 'x' }, t)).status, 400);

  // sinkron ulang: tidak menggandakan data; penanda selesai bertahan
  await call('POST', `/api/items/${byTitle['Minggu 1 › Intro'].id}/complete`, { done: true }, t);
  await call('POST', '/api/moodle/sync', null, t);
  assert.equal((await call('GET', '/api/courses', null, t)).body.length, 1);
  const again = (await call('GET', `/api/courses/${course.id}`, null, t)).body;
  assert.equal(again.items.length, 3);
  assert.deepEqual(again.progress, { done: 1, total: 3 });

  // password tidak pernah dikirim ke endpoint WS (hanya ke login/token.php), token tidak ada di URL
  assert.equal(calls.filter((x) => x.url !== '/login/token.php').some((x) => 'password' in x.form || x.url.includes('TOK123')), false);

  // putuskan: data impor tetap
  await call('DELETE', '/api/moodle', null, t);
  assert.equal((await call('GET', '/api/moodle', null, t)).body.connected, false);
  assert.equal((await call('GET', '/api/courses', null, t)).body.length, 1);
});
