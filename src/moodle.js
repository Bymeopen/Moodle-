import { lookup } from 'node:dns/promises';
import { BlockList, isIP } from 'node:net';

export class MoodleError extends Error {}

// ---------- keamanan URL (anti-SSRF) ----------
const PRIVATE = new BlockList();
for (const [net, bits] of [['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.168.0.0', 16]])
  PRIVATE.addSubnet(net, bits, 'ipv4');
// catatan: JANGAN tambah ::ffff:0:0/96 di sini; BlockList menganggap semua IPv4 sebagai IPv4-mapped sehingga memblokir semuanya
for (const [net, bits] of [['::1', 128], ['fc00::', 7], ['fe80::', 10]]) PRIVATE.addSubnet(net, bits, 'ipv6');

const isPrivateAddr = (address, family) => {
  // IPv6 bentuk ::ffff:a.b.c.d diperiksa sebagai IPv4-nya
  const mapped = /^::ffff:(\d+\.\d+\.\d+\.\d+)$/i.exec(address);
  if (mapped) return PRIVATE.check(mapped[1], 'ipv4');
  return PRIVATE.check(address, family === 6 ? 'ipv6' : 'ipv4');
};

/** Validasi alamat Moodle; kembalikan base URL tanpa slash akhir. MOODLE_ALLOW_PRIVATE=true untuk dev/test. */
export async function normalizeBaseUrl(raw) {
  let u;
  try { u = new URL(raw); } catch { throw new MoodleError('URL Moodle tidak valid'); }
  const allowPrivate = process.env.MOODLE_ALLOW_PRIVATE === 'true';
  if (u.protocol !== 'https:' && !(allowPrivate && u.protocol === 'http:')) throw new MoodleError('URL Moodle harus diawali https://');
  if (!allowPrivate) {
    const host = u.hostname.replace(/^\[|\]$/g, ''); // IPv6 literal datang dengan kurung siku
    const addrs = isIP(host)
      ? [{ address: host, family: isIP(host) }]
      : await lookup(host, { all: true }).catch(() => { throw new MoodleError('Host Moodle tidak ditemukan'); });
    if (addrs.some((a) => isPrivateAddr(a.address, a.family))) throw new MoodleError('Alamat jaringan internal tidak diizinkan');
  }
  return u.origin + u.pathname.replace(/\/+$/, '');
}

// ---------- klien WS ----------
async function post(url, form) {
  const res = await fetch(url, {
    method: 'POST',
    body: new URLSearchParams(form),
    redirect: 'manual', // jangan ikuti redirect (bisa mengarah ke host internal)
    signal: AbortSignal.timeout(30_000),
  }).catch((e) => { throw new MoodleError(`Tidak bisa menghubungi Moodle: ${e.cause?.code ?? e.message}`); });
  if (res.status >= 300 && res.status < 400) throw new MoodleError('Moodle mengalihkan permintaan; gunakan alamat akhir situs');
  if (!res.ok) throw new MoodleError(`Moodle membalas HTTP ${res.status}`);
  try { return await res.json(); } catch { throw new MoodleError('Balasan Moodle bukan JSON (cek alamat dan layanan web)'); }
}

/** Tukar username/password menjadi token layanan mobile. Password tidak disimpan. */
export async function requestToken(base, username, password) {
  const out = await post(`${base}/login/token.php`, { username, password, service: 'moodle_mobile_app' });
  if (out.error || !out.token) throw new MoodleError(out.error || 'Gagal login ke Moodle');
  return out.token;
}

const flatten = (params, prefix = '', out = {}) => {
  for (const [k, v] of Object.entries(params)) {
    const key = prefix ? `${prefix}[${k}]` : k;
    if (v && typeof v === 'object') flatten(v, key, out); else out[key] = String(v);
  }
  return out;
};

export class MoodleClient {
  constructor(base, token) { this.base = base; this.token = token; }
  async call(wsfunction, params = {}) {
    const out = await post(`${this.base}/webservice/rest/server.php`, {
      wstoken: this.token, wsfunction, moodlewsrestformat: 'json', ...flatten(params),
    });
    if (out?.exception || out?.errorcode) throw new MoodleError(out.message || out.errorcode);
    return out;
  }
}

// ---------- normalisasi ----------
const ENTITIES = { '&amp;': '&', '&lt;': '<', '&gt;': '>', '&quot;': '"', '&#39;': "'", '&nbsp;': ' ' };
/** HTML Moodle -> teks polos (konten dirender via textContent di UI, jadi tag memang dibuang). */
export const htmlToText = (html = '') => String(html)
  .replace(/<(script|style)[\s\S]*?<\/\1>/gi, '')
  .replace(/<\s*(br|\/p|\/div|\/li|\/h\d)\s*\/?>/gi, '\n')
  .replace(/<[^>]+>/g, '')
  .replace(/&(amp|lt|gt|quot|#39|nbsp);/g, (m) => ENTITIES[m])
  .replace(/\n{3,}/g, '\n\n').trim();

const isoOrNull = (unix) => (unix > 0 ? new Date(unix * 1000).toISOString() : null);

/**
 * Tarik semua data pengguna: mata kuliah, materi, tugas (+tenggat), dan nilai.
 * Kegagalan satu fungsi (mis. nilai dimatikan kampus) dicatat sebagai warning, tidak menggagalkan sisanya.
 */
export async function fetchAll(client) {
  const warnings = [];
  const safe = async (label, fn, fallback) => {
    try { return await fn(); } catch (e) { warnings.push(`${label}: ${e.message}`); return fallback; }
  };

  const site = await client.call('core_webservice_get_site_info');
  const enrolled = await client.call('core_enrol_get_users_courses', { userid: site.userid });
  const ids = enrolled.map((c) => c.id);
  const assignData = ids.length ? await safe('Tugas', () => client.call('mod_assign_get_assignments', { courseids: ids }), { courses: [] }) : { courses: [] };
  const pageData = ids.length ? await safe('Halaman', () => client.call('mod_page_get_pages_by_courses', { courseids: ids }), { pages: [] }) : { pages: [] };
  const assignsByCm = new Map(assignData.courses.flatMap((c) => c.assignments.map((a) => [a.cmid, a])));
  const pagesByCm = new Map((pageData.pages ?? []).map((p) => [p.coursemodule, p]));

  const courses = [];
  for (const c of enrolled) {
    const sections = await safe(`Konten ${c.shortname}`, () => client.call('core_course_get_contents', { courseid: c.id }), []);
    const items = [];
    for (const s of sections) {
      const prefix = s.name && s.name !== 'General' ? `${s.name} › ` : '';
      for (const m of s.modules ?? []) {
        if (m.uservisible === false) continue;
        const ext = `cm${m.id}`;
        if (m.modname === 'assign' && assignsByCm.has(m.id)) {
          const a = assignsByCm.get(m.id);
          items.push({ ext, type: 'assignment', title: prefix + m.name, content: htmlToText(a.intro), due_at: isoOrNull(a.duedate), max_grade: a.grade > 0 ? a.grade : 100 });
        } else if (m.modname === 'page') {
          items.push({ ext, type: 'page', title: prefix + m.name, content: htmlToText(pagesByCm.get(m.id)?.content ?? m.description) });
        } else if (m.modname === 'label') {
          const text = htmlToText(m.description);
          if (text) items.push({ ext, type: 'page', title: prefix + text.slice(0, 60), content: text });
        } else {
          // url/resource/folder/forum/quiz/dll: tautan kembali ke Moodle (file butuh sesi login kampus)
          const link = m.modname === 'url' ? m.contents?.[0]?.fileurl ?? m.url : m.url;
          const files = (m.contents ?? []).filter((f) => f.type === 'file' && f.fileurl)
            .map((f) => ({ name: f.filename, path: f.filepath ?? '/', url: f.fileurl, size: f.filesize ?? null, mime: f.mimetype ?? null, modified: f.timemodified ?? null }));
          if (link) items.push({ ext, type: 'link', title: prefix + m.name, content: link, files });
        }
      }
    }
    const gradeData = await safe(`Nilai ${c.shortname}`, () => client.call('gradereport_user_get_grade_items', { courseid: c.id, userid: site.userid }), { usergrades: [] });
    const grades = (gradeData.usergrades?.[0]?.gradeitems ?? [])
      .map((g) => ({ name: g.itemname || (g.itemtype === 'course' ? 'Total kursus' : ''), grade: g.graderaw ?? null, max: g.grademax ?? null }))
      .filter((g) => g.name);
    courses.push({ ext: String(c.id), title: c.fullname, summary: htmlToText(c.summary), items, grades });
  }
  return { site: { userid: site.userid, name: site.fullname, sitename: site.sitename }, courses, warnings };
}

// ---------- unduh file ----------
/** Nama aman untuk disk: buang karakter berbahaya, titik di awal, dan panjang berlebih. */
export const safeName = (n) => String(n).normalize('NFC').replace(/[^\p{L}\p{N}._ ()-]+/gu, '_').replace(/^\.+/, '_').trim().slice(0, 120) || '_';

/**
 * Unduh satu file Moodle ke `dest`. Token dikirim lewat POST body (bukan URL, agar tidak masuk log)
 * dan HANYA ke origin Moodle yang sama. Batas ukuran ditegakkan saat streaming.
 */
export async function downloadFile(base, token, fileUrl, dest, maxBytes) {
  const { createWriteStream } = await import('node:fs');
  const { mkdir, rm } = await import('node:fs/promises');
  const { dirname } = await import('node:path');
  const { Readable } = await import('node:stream');
  const { pipeline } = await import('node:stream/promises');
  if (new URL(fileUrl).origin !== new URL(base).origin) throw new MoodleError('File berasal dari host lain; dilewati');
  const res = await fetch(fileUrl, { method: 'POST', body: new URLSearchParams({ token }), redirect: 'manual', signal: AbortSignal.timeout(120_000) })
    .catch((e) => { throw new MoodleError(`Gagal menghubungi Moodle: ${e.cause?.code ?? e.message}`); });
  if (!res.ok || !res.body) throw new MoodleError(`HTTP ${res.status}`);
  if (/application\/json/.test(res.headers.get('content-type') ?? '')) throw new MoodleError('Moodle menolak akses file (token/izin)');
  const declared = Number(res.headers.get('content-length'));
  if (declared > maxBytes) throw new MoodleError(`File ${Math.round(declared / 1e6)} MB melebihi batas`);
  await mkdir(dirname(dest), { recursive: true });
  let seen = 0;
  try {
    await pipeline(Readable.fromWeb(res.body), async function* (src) {
      for await (const chunk of src) {
        seen += chunk.length;
        if (seen > maxBytes) throw new MoodleError('File melebihi batas ukuran');
        yield chunk;
      }
    }, createWriteStream(dest));
  } catch (e) { await rm(dest, { force: true }); throw e; }
  return seen;
}
