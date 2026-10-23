import { test } from 'node:test';
import assert from 'node:assert/strict';
import { review, addDays, toDay } from '../src/srs.js';
import { createApp } from '../src/app.js';
import { openDb } from '../src/db.js';

test('SM-2: interval 1 -> 6 -> x ease; lupa mengulang hari ini', () => {
  const t = '2026-01-01';
  let c = { ease: 2.5, interval: 0, reps: 0 };
  c = review(c, 'good', t);
  assert.deepEqual([c.interval, c.reps, c.due_at], [1, 1, '2026-01-02']);
  c = review(c, 'good', t);
  assert.equal(c.interval, 6);
  c = review(c, 'good', t);
  assert.equal(c.interval, 15); // round(6 * 2.5)
  const lapsed = review(c, 'again', t);
  assert.deepEqual([lapsed.interval, lapsed.reps, lapsed.due_at], [0, 0, t]);
  assert.ok(lapsed.ease >= 1.3 && lapsed.ease < c.ease);
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
});

const server = createApp(openDb(':memory:'), { ai: { json: async () => ({ cards: [{ front: 'F', back: 'B' }, { front: '', back: 'x' }] }), text: async () => '' } }).listen(0);
const base = `http://localhost:${server.address().port}`;
test.after(() => server.close());
const call = async (method, path, body, token) => {
  const res = await fetch(base + path, { method, headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }) }, body: body && JSON.stringify(body) });
  return { status: res.status, body: await res.json() };
};

test('belajar mandiri: progres, kartu, statistik', async () => {
  const me = (await call('POST', '/api/auth/register', { name: 'Aku', email: 'a@x.id', password: 'password123' })).body.token;
  const { id: cid } = (await call('POST', '/api/courses', { title: 'Belajar Go' }, me)).body;
  const { id: p1 } = (await call('POST', `/api/courses/${cid}/items`, { type: 'page', title: 'Dasar', content: 'Go itu bahasa.' }, me)).body;
  await call('POST', `/api/courses/${cid}/items`, { type: 'page', title: 'Lanjut', content: 'x' }, me);

  // progres
  await call('POST', `/api/items/${p1}/complete`, { done: true }, me);
  let course = (await call('GET', `/api/courses/${cid}`, null, me)).body;
  assert.deepEqual(course.progress, { done: 1, total: 2 });
  await call('POST', `/api/items/${p1}/complete`, { done: false }, me);
  course = (await call('GET', `/api/courses/${cid}`, null, me)).body;
  assert.equal(course.progress.done, 0);

  // kartu manual + draf AI (kartu invalid disaring)
  assert.equal((await call('POST', `/api/courses/${cid}/cards`, { front: 'Apa itu Go?', back: 'Bahasa' }, me)).status, 201);
  assert.equal((await call('POST', `/api/courses/${cid}/cards`, { front: '', back: 'x' }, me)).status, 400);
  const draft = (await call('POST', `/api/courses/${cid}/ai/cards`, {}, me)).body.cards;
  assert.equal(draft.length, 1);

  // review: good -> jatuh tempo besok, hilang dari antrean
  let due = (await call('GET', '/api/cards/due', null, me)).body;
  assert.equal(due.length, 1);
  const r = await call('POST', `/api/cards/${due[0].id}/review`, { rating: 'good' }, me);
  assert.equal(r.body.due_at, addDays(toDay(), 1));
  assert.equal((await call('GET', '/api/cards/due', null, me)).body.length, 0);
  assert.equal((await call('POST', `/api/cards/${due[0].id}/review`, { rating: 'x' }, me)).status, 400);

  const stats = (await call('GET', '/api/study/stats', null, me)).body;
  assert.deepEqual(stats, { due: 0, reviewed_today: 1, streak: 1 });

  // kartu milik orang lain tidak bisa disentuh
  const other = (await call('POST', '/api/auth/register', { name: 'B', email: 'b@x.id', password: 'password123' })).body.token;
  assert.equal((await call('POST', `/api/cards/${due[0].id}/review`, { rating: 'good' }, other)).status, 404);
});
