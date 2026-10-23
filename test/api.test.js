import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { openDb } from '../src/db.js';

const server = createApp(openDb(':memory:')).listen(0);
const base = `http://localhost:${server.address().port}`;
test.after(() => server.close());

async function call(method, path, body, token) {
  const res = await fetch(base + path, {
    method,
    headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }) },
    body: body && JSON.stringify(body),
  });
  return { status: res.status, body: await res.json() };
}
const register = async (name, email) =>
  (await call('POST', '/api/auth/register', { name, email, password: 'password123' })).body.token;

test('alur lengkap: kursus, tugas, kuis, nilai', async () => {
  const admin = await register('Admin', 'admin@x.id'); // pengguna pertama = admin
  const student = await register('Budi', 'budi@x.id');
  assert.equal((await call('GET', '/api/me', null, admin)).body.role, 'admin');
  assert.equal((await call('GET', '/api/me', null, student)).body.role, 'student');

  // siswa tidak boleh membuat kursus
  assert.equal((await call('POST', '/api/courses', { title: 'X' }, student)).status, 403);

  const { id: cid } = (await call('POST', '/api/courses', { title: 'Matematika' }, admin)).body;
  // belum enroll -> ditolak
  assert.equal((await call('GET', `/api/courses/${cid}`, null, student)).status, 403);
  await call('POST', `/api/courses/${cid}/enroll`, null, student);

  const { id: aid } = (await call('POST', `/api/courses/${cid}/items`,
    { type: 'assignment', title: 'PR 1', max_grade: 50 }, admin)).body;
  const { id: qid } = (await call('POST', `/api/courses/${cid}/items`, {
    type: 'quiz', title: 'Kuis 1', max_grade: 10,
    questions: [
      { text: '1+1?', options: ['1', '2'], correct: 1 },
      { text: '2+2?', options: ['3', '4'], correct: 1 },
    ],
  }, admin)).body;

  // siswa tidak melihat kunci jawaban
  const view = (await call('GET', `/api/courses/${cid}`, null, student)).body;
  assert.equal(view.can_manage, false);
  assert.equal('correct' in view.items.find((i) => i.id === qid).questions[0], false);

  // kuis: 1 dari 2 benar -> 5/10
  const qs = view.items.find((i) => i.id === qid).questions;
  const att = await call('POST', `/api/items/${qid}/attempt`, { answers: { [qs[0].id]: 1, [qs[1].id]: 0 } }, student);
  assert.equal(att.body.score, 5);

  // tugas: kirim lalu dinilai
  assert.equal((await call('POST', `/api/items/${aid}/submit`, { text: 'jawaban' }, student)).status, 200);
  const subs = (await call('GET', `/api/items/${aid}/submissions`, null, admin)).body;
  assert.equal((await call('GET', `/api/items/${aid}/submissions`, null, student)).status, 403);
  assert.equal((await call('POST', `/api/submissions/${subs[0].id}/grade`, { grade: 60 }, admin)).status, 400); // > max
  assert.equal((await call('POST', `/api/submissions/${subs[0].id}/grade`, { grade: 40, feedback: 'ok' }, admin)).status, 200);

  // buku nilai: (40 + 5) / (50 + 10) = 75%
  const g = (await call('GET', `/api/courses/${cid}/grades`, null, student)).body;
  assert.deepEqual(g.rows[0].grades, [40, 5]);
  assert.equal(g.rows[0].percent, 75);
});

test('validasi auth', async () => {
  assert.equal((await call('GET', '/api/courses')).status, 401);
  assert.equal((await call('POST', '/api/auth/login', { email: 'nope@x.id', password: 'x' })).status, 401);
  assert.equal((await call('POST', '/api/auth/register', { name: 'a', email: 'b@x.id', password: 'short' })).status, 400);
  assert.equal((await call('POST', '/api/auth/register', { name: 'a', email: 'budi@x.id', password: 'password123' })).status, 409);
});
