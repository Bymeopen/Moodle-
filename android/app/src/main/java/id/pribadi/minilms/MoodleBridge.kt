package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Jembatan **hanya-baca** dari sandbox Alpine ke Moodle. Sandbox tidak punya jaringan dan tidak pernah memegang token: ia menulis permintaan kecil
 * ke folder spool ([BridgeSpool]); aplikasi memvalidasi, memanggil Web Service Moodle sendiri, lalu menulis jawaban yang sudah disaring.
 *
 * Aturan keras (permintaan datang dari sandbox = tidak tepercaya, bisa saja dipicu teks Moodle):
 * - hanya operasi di [OPS] (daftar putih tetap, semuanya fungsi baca); nama fungsi WS tidak pernah berasal dari permintaan;
 * - argumen hanya bilangan bulat positif dengan nama yang dikenal; id pengguna selalu dari sesi aplikasi, bukan dari permintaan;
 * - jawaban dibuang field rahasianya (token, kunci akses pribadi, kunci autologin) dan dibatasi ukurannya;
 * - tidak ada operasi tulis, kirim tugas, tes, atau forum.
 */
/**
 * Sisi lokal jembatan (diisi ViewModel): data offline (snapshot) dan aksi kendali yang SAMA dengan alat agen ([LmsActions]):
 * sinkron, unduh, tanda selesai lokal, buka halaman, paket belajar. Bukan operasi tulis ke Moodle.
 */
interface BridgeLocal { fun snapshot(): Snapshot?; fun done(): Set<Long>; val actions: LmsActions }

object MoodleBridge {
    const val MAX_REQUEST_BYTES = 2048
    const val MAX_OUT_CHARS = 60_000
    private const val MAX_ID = 1_000_000_000_000L

    /**
     * Satu operasi. Jalur WS: [fn] + [args] (nama di permintaan -> nama parameter WS), [asList] parameter yang dibungkus daftar, [userKey] parameter id pengguna sesi.
     * Jalur lokal: [local] dijalankan aplikasi dengan argumen yang sudah divalidasi (tanpa jaringan Moodle kecuali `sync`). [optional] = argumen yang boleh kosong.
     */
    private class Op(val fn: String? = null, val args: Map<String, String> = emptyMap(), val asList: Set<String> = emptySet(), val userKey: String? = null,
                     val fixed: Map<String, Any> = emptyMap(), val optional: Set<String> = emptySet(), val about: String, val local: ((BridgeLocal, Map<String, Long>) -> Any)? = null)

    private fun iso(sec: Long) = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneId.systemDefault()))
    private fun course(l: BridgeLocal, id: Long) = l.snapshot()?.courses?.firstOrNull { it.id == id } ?: throw IllegalArgumentException("kursus tidak ditemukan; lihat `moodle offline`")
    private fun item(l: BridgeLocal, courseId: Long, itemId: Long) = course(l, courseId).items.firstOrNull { it.id == itemId } ?: throw IllegalArgumentException("item tidak ditemukan; lihat `moodle items courseid=$courseId`")
    private fun itemJson(i: Item, done: Set<Long>) = JSONObject().put("id", i.id).put("type", i.type).put("title", i.title).put("section", i.section).put("files", i.files.size).apply {
        i.dueAt?.let { put("due", iso(it)) }; if (i.isTask) put("done", i.id in done)
        if (i.type == "link" && i.content.startsWith("http")) put("link", i.content)
    }

    private val OPS: Map<String, Op> = linkedMapOf(
        // ---- lokal: data offline dari snapshot sinkron terakhir (tanpa jaringan) ----
        "offline" to Op(about = "ringkasan data offline: kursus, jumlah item/tugas, waktu sinkron terakhir", local = { l, _ ->
            val s = l.snapshot() ?: return@Op JSONObject().put("synced", false).put("hint", "belum ada data; jalankan `moodle sync`")
            JSONObject().put("synced", true).put("synced_at", iso(s.syncedAt)).put("warnings", JSONArray(s.warnings.take(5)))
                .put("courses", JSONArray(s.courses.map { c -> JSONObject().put("id", c.id).put("name", c.name).put("items", c.items.size).put("tasks", c.items.count { it.isTask })
                    .put("grade_percent", Logic.gradeSummary(c).percent?.let { Math.round(it) } ?: JSONObject.NULL) }))
        }),
        "items" to Op(args = mapOf("courseid" to "courseid"), about = "daftar item satu kursus dari data offline (courseid)", local = { l, a ->
            val c = course(l, a.getValue("courseid")); val d = l.done()
            JSONObject().put("id", c.id).put("name", c.name).put("items", JSONArray(c.items.map { itemJson(it, d) }))
        }),
        "deadlines" to Op(args = mapOf("days" to "days"), optional = setOf("days"), about = "tugas/tes bertenggat dari data offline (days, default 14)", local = { l, a ->
            val now = System.currentTimeMillis() / 1000; val days = (a["days"] ?: 14L).coerceIn(1, 120); val d = l.done()
            JSONArray(l.snapshot()?.courses.orEmpty().flatMap { c -> c.items.filter { it.isTask && it.dueAt != null && it.dueAt!! in (now - 7 * 86400L)..(now + days * 86400L) }.map { c to it } }
                .sortedBy { it.second.dueAt }.take(40).map { (c, i) -> itemJson(i, d).put("course_id", c.id).put("course", c.name).put("hours_left", (i.dueAt!! - now) / 3600) })
        }),
        // ---- lokal: kendali aplikasi, sama dengan alat agen (bukan operasi tulis Moodle) ----
        "sync" to Op(about = "sinkronkan data dari Moodle sekarang (memakai jaringan aplikasi)", local = { l, _ -> l.actions.sync() }),
        "download" to Op(args = mapOf("courseid" to "courseid"), about = "unduh semua file kursus untuk offline (courseid)", local = { l, a -> l.actions.downloadCourse(course(l, a.getValue("courseid")).id) }),
        "done" to Op(args = mapOf("itemid" to "itemid"), about = "tandai tugas/tes selesai (penanda lokal, tidak mengirim ke Moodle) (itemid)", local = { l, a -> markLocal(l, a.getValue("itemid"), true) }),
        "undone" to Op(args = mapOf("itemid" to "itemid"), about = "batalkan tanda selesai lokal (itemid)", local = { l, a -> markLocal(l, a.getValue("itemid"), false) }),
        "open" to Op(args = mapOf("courseid" to "courseid", "itemid" to "itemid"), about = "buka halaman Moodle item di aplikasi untuk dikerjakan pengguna (courseid, itemid)", local = { l, a ->
            val i = item(l, a.getValue("courseid"), a.getValue("itemid")); if (i.url.isEmpty()) "Item ini tidak punya halaman Moodle." else l.actions.openPage(i.url)
        }),
        "pack" to Op(args = mapOf("courseid" to "courseid"), about = "siapkan paket belajar kursus dan buka menu bagikan (courseid)", local = { l, a -> l.actions.exportPack(course(l, a.getValue("courseid")).id) }),
        // ---- Web Service Moodle, baca-saja, dipanggil aplikasi dengan tokennya sendiri ----
        "site" to Op("core_webservice_get_site_info", about = "info situs dan pengguna"),
        "courses" to Op("core_enrol_get_users_courses", userKey = "userid", about = "kursus yang diikuti"),
        "contents" to Op("core_course_get_contents", mapOf("courseid" to "courseid"), about = "isi kursus (courseid)"),
        "assignments" to Op("mod_assign_get_assignments", mapOf("courseid" to "courseids"), setOf("courseids"), about = "daftar tugas kursus (courseid)"),
        "quizzes" to Op("mod_quiz_get_quizzes_by_courses", mapOf("courseid" to "courseids"), setOf("courseids"), about = "daftar tes kursus, tanpa mengerjakan (courseid)"),
        "grades" to Op("gradereport_user_get_grade_items", mapOf("courseid" to "courseid"), userKey = "userid", about = "nilai pengguna di kursus (courseid)"),
        "forums" to Op("mod_forum_get_forums_by_courses", mapOf("courseid" to "courseids"), setOf("courseids"), about = "daftar forum kursus (courseid)"),
        "discussions" to Op("mod_forum_get_forum_discussions", mapOf("forumid" to "forumid"), fixed = mapOf("perpage" to 20), about = "diskusi sebuah forum (forumid)"),
        "posts" to Op("mod_forum_get_discussion_posts", mapOf("discussionid" to "discussionid"), about = "isi diskusi (discussionid)"),
        "calendar" to Op("core_calendar_get_calendar_upcoming_view", about = "acara kalender mendatang"),
    )

    private fun markLocal(l: BridgeLocal, itemId: Long, done: Boolean): String {
        val i = l.snapshot()?.courses.orEmpty().flatMap { it.items }.firstOrNull { it.id == itemId && it.isTask } ?: throw IllegalArgumentException("tugas/tes dengan id itu tidak ada")
        l.actions.markDone(itemId, done); return "OK: \"${i.title}\" ditandai ${if (done) "selesai" else "belum selesai"} (lokal)"
    }

    /** Nama operasi yang diizinkan (untuk bantuan dan tes). */
    val operations: List<String> get() = OPS.keys.toList()
    /** Operasi lokal (tanpa fungsi WS). */
    val localOperations: List<String> get() = OPS.filterValues { it.local != null }.keys.toList()
    /** Fungsi WS yang bisa tercapai lewat jembatan (untuk pagar tes: semuanya harus fungsi baca `*_get_*`). */
    val functions: List<String> get() = OPS.values.mapNotNull { it.fn }
    fun help(): String = OPS.entries.joinToString("\n") { (k, o) -> "moodle $k${o.args.keys.joinToString("") { if (it in o.optional) " [$it=ANGKA]" else " $it=ANGKA" }}  # ${o.about}" } +
        "\n\nKeluaran JSON: {\"ok\":true,\"data\":...} atau {\"ok\":false,\"error\":...}. Python: import moodle; moodle.call(\"contents\", courseid=5). Pipa ke jq bila terpasang."

    /** Nama field yang tidak boleh keluar ke sandbox, di tingkat mana pun. */
    private fun secret(key: String): Boolean {
        val k = key.lowercase()
        return "token" in k || "accesskey" in k || "privatekey" in k || "autologin" in k || k == "password" || k == "wstoken"
    }

    fun sanitize(v: Any?): Any? = when (v) {
        is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> if (!secret(k)) o.put(k, sanitize(v.opt(k))) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(sanitize(v.opt(i))) }
        else -> v
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg.take(200)).toString()

    /**
     * Proses satu permintaan JSON dan kembalikan jawaban JSON (`ok`, `data` | `error`). Tidak pernah melempar.
     * [call] memanggil fungsi WS dengan token milik aplikasi (token tidak pernah masuk ke sini); [userId] = pengguna sesi.
     */
    /** Nama operasi dari permintaan mentah (untuk jejak); "?" bila tidak terbaca. */
    fun opOf(request: String): String = try { JSONObject(request).optString("op").takeIf { it.isNotEmpty() && it.length <= 20 && Regex("[a-z_]+").matches(it) } ?: "?" } catch (e: Exception) { "?" }

    fun handle(request: String, userId: Long, call: (fn: String, params: Map<String, Any>) -> Any, local: BridgeLocal? = null): String {
        if (request.length > MAX_REQUEST_BYTES) return err("permintaan terlalu besar")
        val req = try { JSONObject(request) } catch (e: Exception) { return err("permintaan bukan JSON yang sah") }
        val name = req.optString("op")
        val op = OPS[name] ?: return err("operasi tidak dikenal; yang ada: ${OPS.keys.joinToString(", ")}")
        val unknown = req.keys().asSequence().filter { it != "op" && it !in op.args }.toList()
        if (unknown.isNotEmpty()) return err("argumen tidak dikenal: ${unknown.joinToString(",") { it.take(20) }}")
        val params = linkedMapOf<String, Any>(); val nums = linkedMapOf<String, Long>()
        for ((reqKey, wsKey) in op.args) {
            val raw = req.opt(reqKey)
            if (raw == null && reqKey in op.optional) continue
            val n = (raw as? Number)?.toLong()?.takeIf { (raw is Int || raw is Long) && it in 1..MAX_ID } ?: return err("argumen $reqKey wajib berupa bilangan bulat positif")
            nums[reqKey] = n; params[wsKey] = if (wsKey in op.asList) listOf(n) else n
        }
        op.userKey?.let { params[it] = userId }
        params.putAll(op.fixed)
        return try {
            val data = if (op.local != null) {
                val l = local ?: return err("operasi lokal tidak tersedia di sini")
                sanitize(op.local.invoke(l, nums))
            } else sanitize(call(op.fn!!, params))
            val text = JSONObject().put("ok", true).put("data", data).toString()
            if (text.length <= MAX_OUT_CHARS) text else JSONObject().put("ok", true).put("truncated", true).put("data", (data?.toString() ?: "").take(MAX_OUT_CHARS - 200)).toString()
        } catch (e: ApiError) { err("Moodle: ${e.message ?: "galat"}") }
        catch (e: IllegalArgumentException) { err(e.message ?: "argumen tidak valid") }
        catch (e: Exception) { err("gagal memanggil Moodle: ${e.javaClass.simpleName}") }
    }

    /**
     * Modul Python `moodle` di sandbox (`/usr/local/lib/moodle.py`, lewat PYTHONPATH): `moodle.call("contents", courseid=5)` -> data (dict/list/str),
     * `BridgeError` bila ditolak. Validasi yang sama dengan skrip shell: op `[a-z_]`, argumen nama huruf kecil = bilangan bulat positif.
     */
    fun pythonModule(): String = """
'''Jembatan Moodle baca-saja dari sandbox Mini LMS (tanpa jaringan, tanpa token).

    import moodle
    kursus = moodle.call("courses")                 # Web Service (dipanggil aplikasi)
    isi    = moodle.call("contents", courseid=5)
    off    = moodle.call("offline")                 # data offline aplikasi
    moodle.call("sync")                             # kendali aplikasi (bukan tulis ke Moodle)
    moodle.ops()                                    # daftar operasi
'''
import json, os, time

BRIDGE = "/home/ai/bridge"
OPS_FILE = "/usr/local/share/moodle-ops.txt"


class BridgeError(Exception):
    '''Permintaan ditolak aplikasi atau jembatan tidak aktif.'''


def call(op, **args):
    if not os.path.isdir(BRIDGE):
        raise BridgeError("jembatan Moodle tidak aktif (hanya ada saat dijalankan lewat alat linux_exec)")
    if not isinstance(op, str) or not op or op != op.lower() or not op.replace("_", "").isalpha():
        raise ValueError("nama operasi tidak valid")
    for k, v in args.items():
        if not k.isalpha() or k != k.lower() or isinstance(v, bool) or not isinstance(v, int) or v <= 0:
            raise ValueError("argumen harus nama huruf kecil dengan nilai bilangan bulat positif")
    rid = os.urandom(8).hex()
    tmp = os.path.join(BRIDGE, "tmp-" + rid)
    req = os.path.join(BRIDGE, "req-%s.json" % rid)
    res = os.path.join(BRIDGE, "res-%s.json" % rid)
    with open(tmp, "w") as f:
        json.dump(dict(op=op, **args), f)
    os.rename(tmp, req)
    for _ in range(150):
        if os.path.exists(res):
            with open(res) as f:
                data = json.load(f)
            os.remove(res)
            if not data.get("ok"):
                raise BridgeError(data.get("error", "galat"))
            return data.get("data")
        time.sleep(0.2)
    try:
        os.remove(req)
    except OSError:
        pass
    raise BridgeError("waktu habis menunggu aplikasi")


def ops():
    with open(OPS_FILE) as f:
        print(f.read())


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2 or sys.argv[1] == "help":
        ops()
    else:
        kw = {k: int(v) for k, v in (a.split("=", 1) for a in sys.argv[2:])}
        print(json.dumps(call(sys.argv[1], **kw), ensure_ascii=False, indent=1))
"""

    /** Skrip `moodle` di dalam sandbox: hanya menerima nama operasi `[a-z_]` dan argumen `nama=angka`, lalu bertukar berkas dengan aplikasi. */
    fun cliScript(): String = """#!/bin/sh
# moodle <op> [nama=angka ...] : jembatan baca-saja ke Moodle lewat aplikasi (tanpa jaringan, tanpa token)
B=/home/ai/bridge
[ -d "¤B" ] || { echo "Jembatan Moodle tidak aktif (hanya ada saat dijalankan lewat alat linux_exec)"; exit 2; }
OP="¤1"
if [ -z "¤OP" ] || [ "¤OP" = help ]; then echo "operasi:"; cat /usr/local/share/moodle-ops.txt 2>/dev/null; exit 0; fi
case "¤OP" in *[!a-z_]*) echo "nama operasi tidak valid"; exit 2;; esac
shift
J="{\"op\":\"¤OP\""
for a in "¤@"; do
  k="¤{a%%=*}"; v="¤{a#*=}"
  case "¤k" in ""|*[!a-z]*) echo "nama argumen tidak valid"; exit 2;; esac
  case "¤v" in ""|*[!0-9]*) echo "nilai argumen harus angka"; exit 2;; esac
  J="¤J,\"¤k\":¤v"
done
J="¤J}"
ID="¤(head -c 8 /dev/urandom | od -An -tx1 | tr -d ' \n')"
printf '%s' "¤J" > "¤B/tmp-¤ID" && mv "¤B/tmp-¤ID" "¤B/req-¤ID.json" || { echo "gagal menulis permintaan"; exit 3; }
i=0
while [ ¤i -lt 150 ]; do
  if [ -f "¤B/res-¤ID.json" ]; then cat "¤B/res-¤ID.json"; echo; rm -f "¤B/res-¤ID.json"; exit 0; fi
  sleep 0.2; i=¤((i+1))
done
rm -f "¤B/req-¤ID.json"; echo "waktu habis menunggu aplikasi"; exit 3
""".replace('¤', '$')
}

/**
 * Folder spool tempat sandbox dan aplikasi bertukar berkas. Folder ini hanya di-mount ke chroot selama satu perintah AI ([Chroot.aiExecScript]);
 * [serve] hanya melayani selama perintah itu berjalan, membatasi jumlah permintaan, dan membersihkan sisa setelahnya.
 *
 * Isi folder berasal dari pengguna `ai` (tidak tepercaya): hanya berkas biasa bernama `req-<hex>.json` yang dibaca (tanpa mengikuti symlink),
 * ukuran dibatasi, dan jawaban ditulis ke berkas sementara baru lalu di-rename di atas nama tujuan (rename mengganti symlink, bukan menulis lewat symlink).
 */
class BridgeSpool(val dir: File, private val maxRequests: Int = 40, private val pollMs: Long = 100, private val handler: (String) -> String) {
    companion object { val REQ = Regex("req-([0-9a-f]{8,32})\\.json") }
    /** Nama operasi yang sudah dilayani sejak [prepare] (jejak untuk log Lab Linux dan footer keluaran). */
    val served = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Siapkan folder (dibuat oleh aplikasi; bisa ditulis pengguna `ai` yang uid-nya lain). */
    fun prepare() {
        dir.mkdirs()
        dir.setReadable(true, false); dir.setWritable(true, false); dir.setExecutable(true, false)
        served.clear(); clean()
    }

    fun clean() { dir.listFiles()?.forEach { f -> runCatching { Files.deleteIfExists(f.toPath()) } } }

    /** Jalankan [block] sambil melayani permintaan dari sandbox. */
    fun <T> serve(block: () -> T): T {
        prepare()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val served = java.util.concurrent.atomic.AtomicInteger(0)
        val worker = thread(isDaemon = true, name = "moodle-bridge") {
            while (running.get()) {
                served.addAndGet(pumpOnce(maxRequests - served.get()))
                try { Thread.sleep(pollMs) } catch (e: InterruptedException) { break }
            }
        }
        try { return block() } finally {
            running.set(false); worker.interrupt(); worker.join(2000)
            clean()
        }
    }

    /** Layani permintaan yang menunggu (maksimal [budget]); mengembalikan jumlah yang dilayani. Dipisah agar bisa dites tanpa thread. */
    fun pumpOnce(budget: Int = maxRequests): Int {
        var n = 0
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            if (n >= budget) break
            val m = REQ.matchEntire(f.name) ?: continue
            val id = m.groupValues[1]
            val req = readSmall(f) ?: continue
            served += MoodleBridge.opOf(req)
            val reply = try { handler(req) } catch (e: Exception) { JSONObject().put("ok", false).put("error", "galat internal").toString() }
            runCatching { Files.deleteIfExists(f.toPath()) }
            writeReply(id, reply); n++
        }
        return n
    }

    /** Baca berkas biasa tanpa mengikuti symlink; null bila bukan berkas biasa, terlalu besar, atau gagal dibaca (permintaan dibuang). */
    private fun readSmall(f: File): String? {
        val p = f.toPath()
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) { Files.deleteIfExists(p); return null }
            Files.newByteChannel(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                if (ch.size() > MoodleBridge.MAX_REQUEST_BYTES) { Files.deleteIfExists(p); return "x".repeat(MoodleBridge.MAX_REQUEST_BYTES + 1) }   // diteruskan ke handler agar dijawab "terlalu besar"
                val buf = java.nio.ByteBuffer.allocate(ch.size().toInt()); while (buf.hasRemaining() && ch.read(buf) > 0) {}
                return String(buf.array(), 0, buf.position(), Charsets.UTF_8)
            }
        } catch (e: IOException) { runCatching { Files.deleteIfExists(p) }; return null }
    }

    private fun writeReply(id: String, text: String) {
        val tmp = File(dir, "app-${UUID.randomUUID()}.tmp").toPath()
        val dest = File(dir, "res-$id.json").toPath()
        try {
            Files.newByteChannel(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))) }
            File(tmp.toString()).setReadable(true, false)
            try { Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE) } catch (e: AtomicMoveNotSupportedException) { Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING) }
        } catch (e: IOException) { runCatching { Files.deleteIfExists(tmp) } }
    }
}
