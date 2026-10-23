package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

class ApiError(message: String) : Exception(message)

/** Lapisan jaringan yang bisa diganti di tes. Selalu POST form-urlencoded; tidak mengikuti redirect. */
fun interface Transport {
    fun post(url: String, form: Map<String, String>): String
}

object HttpTransport : Transport {
    override fun post(url: String, form: Map<String, String>): String {
        val conn = open(url, form)
        try {
            val code = conn.responseCode
            if (code in 300..399) throw ApiError("Moodle mengalihkan permintaan; gunakan alamat akhir situs")
            if (code != 200) throw ApiError("Moodle membalas HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /** Buka koneksi POST; dipakai juga oleh pengunduh file (stream besar). */
    fun open(url: String, form: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("User-Agent", "MiniLMS/2.0")
        conn.outputStream.use { it.write(encode(form).toByteArray()) }
        return conn
    }

    private fun encode(form: Map<String, String>) = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

object Sso {
    const val SCHEME = "moodlemobile"

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Tautan resmi Moodle untuk login SSO; hasilnya dialihkan ke moodlemobile://token=...
     * [oauthId] = id penyedia identitas OAuth2 (mis. Google) agar langsung membuka login penyedia itu, seperti aplikasi resmi.
     */
    fun launchUrl(launchBase: String, passport: String, oauthId: Int? = null) =
        "$launchBase?service=moodle_mobile_app&passport=$passport&urlscheme=$SCHEME" + (oauthId?.let { "&oauthsso=$it" } ?: "")

    /**
     * Ambil token dari tautan callback `moodlemobile://token=BASE64`, isi BASE64 = "signature:::token[:::privatetoken]".
     * signature harus md5(urlSitus + passport): mengikat token ke permintaan yang kita mulai, sehingga tautan
     * moodlemobile:// sembarangan (dari aplikasi/halaman lain) tidak bisa menyuntikkan token.
     */
    data class Result(val token: String, val privateToken: String)

    fun parseCallback(callback: String, base: String, passport: String): String = parseCallbackFull(callback, base, passport).token

    /** Sama seperti [parseCallback] tetapi juga mengembalikan private token (bagian ke-3 callback) untuk login otomatis ke halaman Moodle. */
    fun parseCallbackFull(callback: String, base: String, passport: String): Result {
        val raw = callback.substringAfter("token=", "").substringBefore('&')
        // hanya %XX yang didekode; URLDecoder akan mengubah '+' (karakter sah base64) menjadi spasi dan merusak token
        val b64 = Regex("%([0-9A-Fa-f]{2})").replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        if (b64.isEmpty()) throw ApiError("Tautan login tidak berisi token")
        val parts = try { String(Base64.getMimeDecoder().decode(b64)).split(":::") } catch (e: IllegalArgumentException) { throw ApiError("Token login rusak") }
        if (parts.size < 2 || parts[1].isEmpty()) throw ApiError("Format token tidak dikenali")
        if (parts[0] != md5(base + passport)) throw ApiError("Tanda tangan login tidak cocok; ulangi login dari aplikasi ini")
        return Result(parts[1], parts.getOrNull(2).orEmpty())
    }
}

/** Penyedia identitas (mis. Login SSO Google) dari konfigurasi publik Moodle. */
data class Idp(val name: String, val id: Int)

/** Konfigurasi publik situs: tanpa login. [wwwroot] dipakai untuk memverifikasi tanda tangan callback SSO. */
data class PublicConfig(val wwwroot: String, val launchUrl: String, val typeOfLogin: Int, val providers: List<Idp>)

object MoodleApi {
    /** Pengganti di tes: (url, jsonBody) -> respons. */
    var jsonPost: (String, String) -> String = { url, body ->
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 30_000; c.doOutput = true; c.instanceFollowRedirects = false
            c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "MiniLMS/2.0")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw ApiError("Moodle membalas HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Ambil metode login yang tersedia (endpoint publik yang sama dengan aplikasi Moodle resmi). */
    fun publicConfig(base: String): PublicConfig {
        val resp = JSONArray(jsonPost("$base/lib/ajax/service-nologin.php?info=tool_mobile_get_public_config",
            """[{"index":0,"methodname":"tool_mobile_get_public_config","args":{}}]"""))
        val first = resp.getJSONObject(0)
        if (first.optBoolean("error")) throw ApiError(first.optJSONObject("exception")?.optString("message") ?: "Situs ini tidak mendukung aplikasi mobile")
        val d = first.getJSONObject("data")
        val idps = d.optJSONArray("identityproviders") ?: JSONArray()
        val providers = (0 until idps.length()).mapNotNull { i ->
            val o = idps.getJSONObject(i)
            // id penyedia ada di parameter ?id= pada URL login-nya
            Regex("[?&]id=(\\d+)").find(o.optString("url"))?.groupValues?.get(1)?.toIntOrNull()?.let { Idp(o.optString("name"), it) }
        }
        return PublicConfig(d.optString("wwwroot", base), d.optString("launchurl", "$base/admin/tool/mobile/launch.php"), d.optInt("typeoflogin", 1), providers)
    }

    var transport: Transport = HttpTransport
    /** HTML -> teks. Default memakai android.text.Html; tes menggantinya. */
    var htmlToText: (String) -> String = { html ->
        androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }

    /** Hanya https (campus memakai https). */
    fun normalizeBase(raw: String): String {
        val u = try { URL(raw.trim()) } catch (e: Exception) { throw ApiError("Alamat Moodle tidak valid") }
        if (u.protocol != "https" && !allowHttpForTests) throw ApiError("Alamat Moodle harus diawali https://")
        return (u.protocol + "://" + u.authority + u.path).trimEnd('/')
    }
    var allowHttpForTests = false

    fun requestToken(base: String, username: String, password: String): String = requestTokens(base, username, password).token

    fun requestTokens(base: String, username: String, password: String): Sso.Result {
        val out = JSONObject(transport.post("$base/login/token.php", mapOf("username" to username, "password" to password, "service" to "moodle_mobile_app")))
        if (out.has("token")) return Sso.Result(out.getString("token"), out.optString("privatetoken"))
        throw ApiError(out.optString("error", "Gagal login ke Moodle"))
    }

    private fun flatten(params: Map<String, Any>, prefix: String = "", out: MutableMap<String, String> = linkedMapOf()): Map<String, String> {
        for ((k, v) in params) {
            val key = if (prefix.isEmpty()) k else "$prefix[$k]"
            when (v) {
                is List<*> -> v.forEachIndexed { i, x -> out["$key[$i]"] = x.toString() }
                is Map<*, *> -> @Suppress("UNCHECKED_CAST") flatten(v as Map<String, Any>, key, out)
                else -> out[key] = v.toString()
            }
        }
        return out
    }

    /** Panggil satu fungsi WS. Token dikirim di body POST (bukan URL). Hasil: JSONObject atau JSONArray. */
    fun call(base: String, token: String, fn: String, params: Map<String, Any> = emptyMap()): Any {
        val text = transport.post("$base/webservice/rest/server.php",
            mapOf("wstoken" to token, "wsfunction" to fn, "moodlewsrestformat" to "json") + flatten(params))
        val v = JSONTokener(text).nextValue()
        if (v is JSONObject && (v.has("exception") || v.has("errorcode"))) throw ApiError(v.optString("message", v.optString("errorcode")))
        return v
    }

    fun siteInfo(base: String, token: String, privateToken: String = ""): Site {
        val i = call(base, token, "core_webservice_get_site_info") as JSONObject
        return Site(base, token, i.getLong("userid"), i.optString("fullname"), i.optString("sitename"), privateToken)
    }

    /**
     * URL yang membuka halaman Moodle [target] dalam keadaan sudah login (kunci sekali pakai dari tool_mobile_get_autologin_key, seperti aplikasi resmi).
     * Hanya untuk halaman di host Moodle yang sama; null bila tidak bisa (tanpa private token, host lain, kunci diminta terlalu sering) agar pemanggil memakai URL biasa.
     */
    fun autologinUrl(site: Site, target: String): String? {
        if (site.privateToken.isEmpty()) return null
        val t = try { java.net.URL(target) } catch (e: Exception) { return null }
        val s = java.net.URL(site.url)
        if (t.protocol != "https" || t.host != s.host || t.port != s.port) return null
        return try {
            val r = call(site.url, site.token, "tool_mobile_get_autologin_key", mapOf("privatetoken" to site.privateToken)) as JSONObject
            val key = r.optString("key").takeIf { it.isNotEmpty() } ?: return null
            val base = r.optString("autologinurl").ifEmpty { "${site.url}/admin/tool/mobile/autologin.php" }
            if (java.net.URL(base).host != s.host) null                       // jangan kirim kunci ke host lain
            else "$base?userid=${site.userId}&key=${java.net.URLEncoder.encode(key, "UTF-8")}&urlto=${java.net.URLEncoder.encode(target, "UTF-8")}"
        } catch (e: ApiError) { null } catch (e: org.json.JSONException) { null } catch (e: ClassCastException) { null }
    }

    private val EPOCH_NONE = 0L

    /** Jalankan [block]; kegagalan jaringan/format dicatat sebagai peringatan dan menghasilkan null (tidak pernah melempar). */
    private fun <T> attempt(warnings: MutableList<String>, label: String, block: () -> T): T? = try { block() }
        catch (e: ApiError) { warnings += "$label: ${e.message}"; null }
        catch (e: org.json.JSONException) { warnings += "$label: respons Moodle tidak dikenali"; null }
        catch (e: ClassCastException) { warnings += "$label: respons Moodle tidak dikenali"; null }

    /**
     * Tarik semua: kursus, materi, tugas+tenggat, nilai.
     * Kegagalan satu fungsi menjadi peringatan, bukan galat total, dan **tidak boleh merusak data offline**: bila konten/tugas/halaman
     * sebuah kursus gagal diambil, item kursus itu dari [previous] dipertahankan; bila nilai gagal, nilai lama dipertahankan; bila Moodle
     * mengembalikan daftar kursus kosong padahal sebelumnya ada, seluruh data lama dipertahankan.
     */
    private const val TEXT_CAP = 12_000

    /** Ambil soal+jawaban percobaan selesai sebagai teks; "" bila dosen menonaktifkan tinjauan atau gagal. */
    fun quizReview(site: Site, attemptId: Long): String = try {
        val qs = (call(site.url, site.token, "mod_quiz_get_attempt_review", mapOf("attemptid" to attemptId, "page" to -1)) as JSONObject).optJSONArray("questions") ?: JSONArray()
        (0 until qs.length()).mapNotNull { qs.optJSONObject(it) }.joinToString("\n\n") { q -> "Soal ${q.optInt("number", 0)}\n" + htmlToText(q.optString("html")) }.take(TEXT_CAP * 2)
    } catch (e: ApiError) { "" } catch (e: org.json.JSONException) { "" } catch (e: ClassCastException) { "" }

    /** Diskusi forum terbaru (maks 8, beserta balasan) sebagai teks bacaan offline; null bila gagal diambil. */
    fun forumText(site: Site, forumId: Long): String? = try {
        val ds = (call(site.url, site.token, "mod_forum_get_forum_discussions", mapOf("forumid" to forumId, "perpage" to 8)) as JSONObject).optJSONArray("discussions") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until minOf(ds.length(), 8)) {
            val d = ds.optJSONObject(i) ?: continue
            sb.append("• ").append(d.optString("name")).append(" — ").append(d.optString("userfullname")).append('\n').append(htmlToText(d.optString("message"))).append('\n')
            if (d.optInt("numreplies") > 0) try {
                val ps = (call(site.url, site.token, "mod_forum_get_discussion_posts", mapOf("discussionid" to d.optLong("discussion"))) as JSONObject).optJSONArray("posts") ?: JSONArray()
                for (j in 0 until ps.length()) ps.optJSONObject(j)?.takeIf { it.optLong("parentid", it.optLong("parent")) != 0L }?.let { p ->
                    sb.append("   ↳ ").append(p.optJSONObject("author")?.optString("fullname").orEmpty()).append(": ").append(htmlToText(p.optString("message"))).append('\n')
                }
            } catch (e: ApiError) {}
            sb.append('\n')
            if (sb.length > TEXT_CAP) break
        }
        sb.toString().trim().take(TEXT_CAP)
    } catch (e: ApiError) { null } catch (e: org.json.JSONException) { null } catch (e: ClassCastException) { null }

    /** Ubah respons mod_quiz_get_user_attempts jadi daftar percobaan; nilai = sumgrades / total skor tes * nilai maks tes. */
    fun quizAttempts(r: Any, quizSum: Double, max: Double): List<QuizAttempt> {
        val a = (r as? JSONObject)?.optJSONArray("attempts") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val state = o.optString("state")
            val sum = if (o.isNull("sumgrades")) null else o.optDouble("sumgrades")
            QuizAttempt(o.optInt("attempt"), state, if (state == "finished" && sum != null && quizSum > 0) sum / quizSum * max else null, max, o.optLong("timefinish").takeIf { it > 0 }, o.optLong("id"))
        }.sortedBy { it.number }
    }

    fun fetchAll(site: Site, now: Long = System.currentTimeMillis() / 1000, previous: Snapshot? = null): Snapshot {
        val warnings = mutableListOf<String>()
        fun call(fn: String, p: Map<String, Any> = emptyMap()) = call(site.url, site.token, fn, p)

        val enrolled = call("core_enrol_get_users_courses", mapOf("userid" to site.userId)) as? JSONArray ?: throw ApiError("Respons daftar kursus tidak dikenali")
        if (enrolled.length() == 0 && previous?.courses?.isNotEmpty() == true) {
            return previous.copy(warnings = listOf("Moodle mengembalikan daftar kursus kosong; data lama dipertahankan (keluar dari akun bila memang sudah tidak ada kursus)"))
        }
        val ids = (0 until enrolled.length()).map { enrolled.getJSONObject(it).getLong("id") }
        val assignByCm = hashMapOf<Long, JSONObject>()
        val pageByCm = hashMapOf<Long, String>()
        var assignFailed = false; var pagesFailed = false; var quizFailed = false
        val quizByCm = hashMapOf<Long, JSONObject>()
        val forumByCm = hashMapOf<Long, Long>()      // cmid -> id forum
        if (ids.isNotEmpty()) {
            assignFailed = attempt(warnings, "Tugas") {
                val cs = (call("mod_assign_get_assignments", mapOf("courseids" to ids)) as JSONObject).optJSONArray("courses") ?: JSONArray()
                for (i in 0 until cs.length()) {
                    val l = cs.getJSONObject(i).optJSONArray("assignments") ?: continue      // kursus tanpa tugas tidak punya kunci ini
                    for (j in 0 until l.length()) l.getJSONObject(j).let { assignByCm[it.getLong("cmid")] = it }
                }
            } == null
            quizFailed = attempt(warnings, "Tes") {
                val q = (call("mod_quiz_get_quizzes_by_courses", mapOf("courseids" to ids)) as JSONObject).optJSONArray("quizzes") ?: JSONArray()
                for (i in 0 until q.length()) q.getJSONObject(i).let { quizByCm[it.getLong("coursemodule")] = it }
            } == null
            // Forum bersifat tambahan: gagal (mis. fungsi dimatikan) tidak menandai sinkron rusak, forum jatuh kembali ke tautan.
            try { (call("mod_forum_get_forums_by_courses", mapOf("courseids" to ids)) as? JSONArray)?.let { f -> for (i in 0 until f.length()) f.optJSONObject(i)?.let { forumByCm[it.optLong("cmid")] = it.optLong("id") } } }
            catch (e: ApiError) {} catch (e: org.json.JSONException) {} catch (e: ClassCastException) {}
            pagesFailed = attempt(warnings, "Halaman") {
                val p = (call("mod_page_get_pages_by_courses", mapOf("courseids" to ids)) as JSONObject).optJSONArray("pages") ?: JSONArray()
                for (i in 0 until p.length()) p.getJSONObject(i).let { pageByCm[it.getLong("coursemodule")] = it.optString("content") }
            } == null
        }

        val courses = (0 until enrolled.length()).map { ci ->
            val c = enrolled.getJSONObject(ci)
            val cid = c.getLong("id")
            val short = c.optString("shortname", cid.toString())
            val prev = previous?.courses?.firstOrNull { it.id == cid }
            val items = mutableListOf<Item>()
            val sections = attempt(warnings, "Konten $short") { call("core_course_get_contents", mapOf("courseid" to cid)) as JSONArray }
            for (si in 0 until (sections?.length() ?: 0)) {
                val s = sections!!.optJSONObject(si) ?: continue
                val sname = s.optString("name").takeIf { it != "General" } ?: ""
                val mods = s.optJSONArray("modules") ?: continue
                for (mi in 0 until mods.length()) {
                    // satu modul rusak (tanpa id, tipe tak terduga) dilewati dengan peringatan; tidak boleh menggagalkan seluruh sinkron
                    val m = try { mods.getJSONObject(mi).also { it.getLong("id") } } catch (e: org.json.JSONException) { warnings += "Konten $short: modul ke-${mi + 1} tidak valid, dilewati"; continue }
                    if (m.has("uservisible") && !m.getBoolean("uservisible")) continue
                    val id = m.getLong("id"); val name = m.optString("name"); val mod = m.optString("modname")
                    val files = (m.optJSONArray("contents") ?: JSONArray()).let { arr ->
                        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "file" && it.has("fileurl") }
                            .map { FileRef(it.optString("filename"), it.optString("filepath", "/"), it.getString("fileurl"), it.optLong("filesize"), it.optString("mimetype").ifEmpty { null }, it.optLong("timemodified")) }
                    }
                    val pageUrl = m.optString("url")
                    when {
                        mod == "assign" && assignByCm.containsKey(id) -> {
                            val a = assignByCm.getValue(id)
                            items += Item(id, "assignment", name, sname, htmlToText(a.optString("intro")), a.optLong("duedate").takeIf { it > EPOCH_NONE },
                                a.optDouble("grade").takeIf { it > 0 } ?: 100.0, emptyList(), pageUrl)
                        }
                        mod == "quiz" && quizByCm.containsKey(id) -> {
                            val q = quizByCm.getValue(id)
                            val info = buildList {
                                q.optLong("timelimit").takeIf { it > 0 }?.let { add("Durasi ${it / 60} menit") }
                                add(q.optInt("attempts").let { if (it > 0) "Percobaan maks $it" else "Percobaan tak terbatas" })
                                q.optLong("timeopen").takeIf { it > 0 }?.let { add("Dibuka ${java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ')}") }
                            }.joinToString(" · ")
                            val intro = htmlToText(q.optString("intro"))
                            val quizMax = q.optDouble("grade").takeIf { it > 0 } ?: 100.0
                            // Riwayat percobaan; bila gagal diambil, pakai yang lama agar data offline tidak hilang.
                            val tries = attempt(warnings, "Percobaan tes $name") {
                                quizAttempts(call("mod_quiz_get_user_attempts", mapOf("quizid" to q.getLong("id"), "userid" to site.userId, "status" to "all")), q.optDouble("sumgrades"), quizMax)
                            } ?: prev?.items?.firstOrNull { it.id == id }?.attempts.orEmpty()
                            val oldTries = prev?.items?.firstOrNull { it.id == id }?.attempts.orEmpty().associateBy { it.number }
                            // Soal & jawaban percobaan selesai disimpan offline (hanya yang belum ada; gagal/ditutup dosen = dilewati).
                            val withReview = tries.map { a ->
                                if (a.state != "finished" || a.id == 0L) a
                                else a.copy(review = oldTries[a.number]?.review?.takeIf { it.isNotEmpty() } ?: quizReview(site, a.id))
                            }
                            items += Item(id, "quiz", name, sname, if (intro.isEmpty()) info else "$intro\n$info", q.optLong("timeclose").takeIf { it > EPOCH_NONE },
                                quizMax, emptyList(), pageUrl, withReview)
                        }
                        mod == "forum" && forumByCm.containsKey(id) -> {
                            val text = forumText(site, forumByCm.getValue(id)) ?: prev?.items?.firstOrNull { it.id == id }?.content.orEmpty()
                            items += Item(id, "forum", name, sname, text, null, null, emptyList(), pageUrl)
                        }
                        mod == "page" -> items += Item(id, "page", name, sname, htmlToText(pageByCm[id] ?: m.optString("description")), null, null, emptyList(), pageUrl)
                        mod == "label" -> htmlToText(m.optString("description")).takeIf { it.isNotEmpty() }?.let { t -> items += Item(id, "page", t.take(60), sname, t, null, null, emptyList()) }
                        else -> {
                            // resource/folder/url/forum/quiz/dll: tautan ke Moodle + file yang bisa diunduh
                            val link = if (mod == "url") m.optJSONArray("contents")?.optJSONObject(0)?.optString("fileurl") ?: m.optString("url") else m.optString("url")
                            if (!link.isNullOrEmpty() || files.isNotEmpty()) items += Item(id, "link", name, sname, link ?: "", null, null, files, pageUrl)
                        }
                    }
                }
            }
            // item gagal diambil sebagian -> pakai item lama kursus ini supaya materi/tenggat/file offline tidak hilang
            val degraded = sections == null || assignFailed || pagesFailed || quizFailed
            val keptItems = if (degraded && prev != null) prev.items else items

            val gradeData = attempt(warnings, "Nilai $short") { call("gradereport_user_get_grade_items", mapOf("courseid" to cid, "userid" to site.userId)) as JSONObject }
            val grades = if (gradeData == null && prev != null) prev.grades else {
                val gi = gradeData?.optJSONArray("usergrades")?.optJSONObject(0)?.optJSONArray("gradeitems") ?: JSONArray()
                (0 until gi.length()).map { gi.getJSONObject(it) }.mapNotNull { g ->
                    val n = g.optString("itemname").takeIf { !g.isNull("itemname") && it.isNotEmpty() } ?: if (g.optString("itemtype") == "course") "Total kursus" else return@mapNotNull null
                    Grade(n, if (g.isNull("graderaw")) null else g.optDouble("graderaw"), if (g.isNull("grademax")) null else g.optDouble("grademax"))
                }
            }
            Course(cid, c.optString("fullname", short), htmlToText(c.optString("summary")), keptItems, grades)
        }
        return Snapshot(now, courses, warnings)
    }
}

/**
 * Nama aman untuk disk: buang karakter berbahaya dan titik di awal. Nama panjang dipotong **dengan mempertahankan ekstensi**
 * dan diberi sufiks hash agar dua nama yang sama di awal tidak bertabrakan.
 */
fun safeName(n: String): String {
    val cleaned = n.replace(Regex("[^\\p{L}\\p{N}._ ()-]+"), "_").trim().trimStart('.')
    if (cleaned.isEmpty()) return "_"
    if (cleaned.length <= 120) return cleaned
    val dot = cleaned.lastIndexOf('.')
    val ext = if (dot > 0 && cleaned.length - dot <= 10) cleaned.substring(dot) else ""
    val hash = Integer.toHexString(n.hashCode())
    return cleaned.take(120 - ext.length - hash.length - 1) + "~" + hash + ext
}

/** Pastikan file berada di dalam [root] (anti path traversal); murni string, tanpa akses disk (dipanggil sangat sering). */
fun File.isInside(root: File): Boolean {
    val p = toPath().normalize(); val r = root.toPath().normalize()
    return p != r && p.startsWith(r)
}
