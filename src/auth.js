import { scryptSync, randomBytes, timingSafeEqual, createHmac } from 'node:crypto';

const SECRET = process.env.SECRET || randomBytes(32).toString('hex'); // set SECRET agar token bertahan antar restart
const TTL_MS = 12 * 3600 * 1000;

export function hashPassword(pw) {
  const salt = randomBytes(16).toString('hex');
  return `${salt}:${scryptSync(pw, salt, 64).toString('hex')}`;
}

export function verifyPassword(pw, stored) {
  const [salt, hash] = stored.split(':');
  const a = Buffer.from(hash, 'hex');
  const b = scryptSync(pw, salt, 64);
  return a.length === b.length && timingSafeEqual(a, b);
}

const sign = (data) => createHmac('sha256', SECRET).update(data).digest('base64url');

export function issueToken(userId) {
  const body = Buffer.from(JSON.stringify({ uid: userId, exp: Date.now() + TTL_MS })).toString('base64url');
  return `${body}.${sign(body)}`;
}

export function readToken(token = '') {
  const [body, sig] = token.split('.');
  if (!body || !sig) return null;
  const expected = Buffer.from(sign(body));
  const given = Buffer.from(sig);
  if (expected.length !== given.length || !timingSafeEqual(expected, given)) return null;
  const { uid, exp } = JSON.parse(Buffer.from(body, 'base64url').toString());
  return exp > Date.now() ? uid : null;
}
