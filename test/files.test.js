import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

process.env.MOODLE_ALLOW_PRIVATE = 'true';
const FILES = mkdtempSync(join(tmpdir(), 'lms-files-'));
process.env.FILES_DIR = FILES;
process.env.MOODLE_MAX_FILE_MB = '1';
const { createApp } = await import('../src/app.js');
const { openDb } = await import('../src/db.js');
const { safeName } = await import('../src/moodle.js');

const fileReqs = [];
let origin;
const WS = {
  core_webservice_get_site_info: () => ({ userid: 7, fullname: 'Budi', sitename: 'Kampus X' }),
  core_enrol_get_users_courses: () => [{ id: 11, fullname: 'Algoritma', shortname: 'A', summary: '' }],
  mod_assign_get_assignments: () => ({ courses: [] }),
  mod_page_get_pages_by_courses: () => ({ pages: [] }),
  gradereport_user_get_grade_items: () => ({ usergrades: [] }),
  core_course_get_contents: () => [{ name: 'Minggu 1', modules: [{ id: 4, modname: 'resource', name: 'Slide', url: `${origin}/mod/resource/view.php?id=4`, contents: [
    { type: 'file', filename: 'slide.pdf', filepath: '/', filesize: 5, fileurl: `${origin}/webservice/pluginfile.php/1/slide.pdf`, mimetype: 'application/pdf', timemodified: 100 },
    { type: 'file', filename: '../../evil.txt', filepath: '/../x/', filesize: 3, fileurl: `${origin}/webservice/pluginfile.php/1/evil`, timemodified: 100 },
    { type: 'file', filename: 'besar.bin', filepath: '/', filesize: 5_000_000, fileurl: `${origin}/webservice/pluginfile.php/1/besar`, timemodified: 100 },
    { type: 'file', filename: 'luar.pdf', filepath: '/', filesize: 3, fileurl: 'http://127.0.0.2:1/webservice/pluginfile.php/1/luar', timemodified: 100 },
  ] }] }],
};
const moodle = http.createServer((req, res) => {
  let body = ''; req.on('data', (d) => (body += d)).on('end', () => {
    const f = new URLSearchParams(body);
    if (req.url.startsWith('/webservice/pluginfile.php')) {
      fileReqs.push({ url: req.url, method: req.method, token: f.get('token') });
      if (f.get('token') !== 'TOK') { res.setHeader('content-type', 'application/json'); return res.end('{"error":"x"}'); }
      return res.end(req.url.endsWith('slide.pdf') ? '%PDF-' : 'abc');
    }
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify(WS[f.get('wsfunction')]()));
  });
}).listen(0);
origin = `http://localhost:${moodle.address().port}`;

const server = createApp(openDb(':memory:'), { ai: null }).listen(0);
test.after(() => { server.close(); moodle.close(); });
const base = `http://localhost:${server.address().port}`;
const call = async (method, path, body, token) => {
  const res = await fetch(base + path, { method, headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }) }, body: body && JSON.stringify(body) });
  return { status: res.status, res, body: res.headers.get('content-type')?.includes('json') ? await res.json() : null };
};

test('safeName menetralkan path traversal', () => {
  assert.equal(safeName('../../evil.txt').includes('/'), false);
  assert.equal(safeName('../../evil.txt').startsWith('.'), false);
  assert.equal(safeName('../x').includes('/'), false);
  assert.equal(safeName('.bashrc').startsWith('.'), false);
  assert.equal(safeName('Tugas 1 (final).pdf'), 'Tugas 1 (final).pdf');
});

test('sinkron mencatat file; unduh aman; sajikan ke pemilik saja', async () => {
  const t = (await call('POST', '/api/auth/register', { name: 'B', email: 'b@x.id', password: 'password123' })).body.token;
  await call('POST', '/api/moodle/connect', { url: origin, token: 'TOK' }, t);
  const sync = (await call('POST', '/api/moodle/sync', null, t)).body;
  assert.equal(sync.files, 4);

  const dl = (await call('POST', '/api/moodle/download', {}, t)).body;
  assert.equal(dl.downloaded, 2); // slide.pdf + evil.txt (disanitasi)
  assert.equal(dl.skipped, 1);    // besar.bin > 1 MB
  assert.equal(dl.failed, 1);     // luar.pdf: host lain
  // token tidak dikirim ke host lain, dan dikirim via POST body (bukan URL)
  assert.equal(fileReqs.every((r) => r.method === 'POST' && !r.url.includes('TOK')), true);
  assert.equal(fileReqs.some((r) => r.url.includes('luar')), false);

  // semua file mendarat di dalam FILES_DIR (tidak ada yang lolos lewat ../)
  const course = (await call('GET', `/api/courses/${(await call('GET', '/api/courses', null, t)).body[0].id}`, null, t)).body;
  const files = course.items[0].files;
  assert.equal(files.length, 4);
  assert.deepEqual(files.map((f) => f.downloaded), [true, true, false, false]);
  const pdf = await call('GET', `/api/files/${files[0].id}`, null, t);
  assert.equal(pdf.status, 200);
  assert.equal(await pdf.res.text(), '%PDF-');
  assert.equal(existsSync(join(FILES, '..', 'evil.txt')), false);

  // unduh ulang: tidak mengunduh lagi file yang sudah ada
  const before = fileReqs.length;
  const again = (await call('POST', '/api/moodle/download', {}, t)).body;
  assert.equal(again.downloaded, 0);
  // file yang sudah diunduh tidak diminta lagi; yang dilewati/gagal ditolak sebelum ada request
  assert.equal(fileReqs.length - before, 0);
  // orang lain tidak bisa mengambil file
  const other = (await call('POST', '/api/auth/register', { name: 'C', email: 'c@x.id', password: 'password123' })).body.token;
  assert.equal((await call('GET', `/api/files/${files[0].id}`, null, other)).status, 404);
  assert.equal((await call('GET', `/api/files/${files[0].id}`)).status, 401);
});
