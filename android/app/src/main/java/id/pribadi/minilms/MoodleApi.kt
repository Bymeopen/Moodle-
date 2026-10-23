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
    fun parseCallback(callback: String, base: String, passport: String): String {
        val raw = callback.substringAfter("token=", "").substringBefore('&')
        val b64 = try { java.net.URLDecoder.decode(raw, "UTF-8") } catch (e: IllegalArgumentException) { throw ApiError("Tautan login rusak") }
        if (b64.isEmpty()) throw ApiError("Tautan login tidak berisi token")
        val parts = try { String(Base64.getMimeDecoder().decode(b64)).split(":::") } catch (e: IllegalArgumentException) { throw ApiError("Token login rusak") }
        if (parts.size < 2 || parts[1].isEmpty()) throw ApiError("Format token tidak dikenali")
        if (parts[0] != md5(base + passport)) throw ApiError("Tanda tangan login tidak cocok; ulangi login dari aplikasi ini")
        return parts[1]
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

    fun requestToken(base: String, username: String, password: String): String {
        val out = JSONObject(transport.post("$base/login/token.php", mapOf("username" to username, "password" to password, "service" to "moodle_mobile_app")))
        if (out.has("token")) return out.getString("token")
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

    fun siteInfo(base: String, token: String): Site {
        val i = call(base, token, "core_webservice_get_site_info") as JSONObject
        return Site(base, token, i.getLong("userid"), i.optString("fullname"), i.optString("sitename"))
    }

    private val EPOCH_NONE = 0L

    /** Tarik semua: kursus, materi, tugas+tenggat, nilai. Kegagalan satu fungsi menjadi peringatan, bukan galat total. */
    fun fetchAll(site: Site, now: Long = System.currentTimeMillis() / 1000): Snapshot {
        val warnings = mutableListOf<String>()
        fun <T> safe(label: String, fallback: T, block: () -> T): T = try { block() } catch (e: ApiError) { warnings += "$label: ${e.message}"; fallback }
        fun call(fn: String, p: Map<String, Any> = emptyMap()) = call(site.url, site.token, fn, p)

        val enrolled = call("core_enrol_get_users_courses", mapOf("userid" to site.userId)) as JSONArray
        val ids = (0 until enrolled.length()).map { enrolled.getJSONObject(it).getLong("id") }
        val assignByCm = hashMapOf<Long, JSONObject>()
        val pageByCm = hashMapOf<Long, String>()
        if (ids.isNotEmpty()) {
            safe("Tugas", Unit) {
                val a = call("mod_assign_get_assignments", mapOf("courseids" to ids)) as JSONObject
                val cs = a.getJSONArray("courses")
                for (i in 0 until cs.length()) { val l = cs.getJSONObject(i).getJSONArray("assignments"); for (j in 0 until l.length()) l.getJSONObject(j).let { assignByCm[it.getLong("cmid")] = it } }
            }
            safe("Halaman", Unit) {
                val p = (call("mod_page_get_pages_by_courses", mapOf("courseids" to ids)) as JSONObject).optJSONArray("pages") ?: JSONArray()
                for (i in 0 until p.length()) p.getJSONObject(i).let { pageByCm[it.getLong("coursemodule")] = it.optString("content") }
            }
        }

        val courses = (0 until enrolled.length()).map { ci ->
            val c = enrolled.getJSONObject(ci)
            val cid = c.getLong("id")
            val short = c.optString("shortname", cid.toString())
            val items = mutableListOf<Item>()
            val sections = safe("Konten $short", JSONArray()) { call("core_course_get_contents", mapOf("courseid" to cid)) as JSONArray }
            for (si in 0 until sections.length()) {
                val s = sections.getJSONObject(si)
                val sname = s.optString("name").takeIf { it != "General" } ?: ""
                val mods = s.optJSONArray("modules") ?: continue
                for (mi in 0 until mods.length()) {
                    val m = mods.getJSONObject(mi)
                    if (m.has("uservisible") && !m.getBoolean("uservisible")) continue
                    val id = m.getLong("id"); val name = m.optString("name"); val mod = m.optString("modname")
                    val files = (m.optJSONArray("contents") ?: JSONArray()).let { arr ->
                        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "file" && it.has("fileurl") }
                            .map { FileRef(it.optString("filename"), it.optString("filepath", "/"), it.getString("fileurl"), it.optLong("filesize"), it.optString("mimetype").ifEmpty { null }, it.optLong("timemodified")) }
                    }
                    when {
                        mod == "assign" && assignByCm.containsKey(id) -> {
                            val a = assignByCm.getValue(id)
                            items += Item(id, "assignment", name, sname, htmlToText(a.optString("intro")), a.optLong("duedate").takeIf { it > EPOCH_NONE },
                                a.optDouble("grade").takeIf { it > 0 } ?: 100.0, emptyList())
                        }
                        mod == "page" -> items += Item(id, "page", name, sname, htmlToText(pageByCm[id] ?: m.optString("description")), null, null, emptyList())
                        mod == "label" -> htmlToText(m.optString("description")).takeIf { it.isNotEmpty() }?.let { t -> items += Item(id, "page", t.take(60), sname, t, null, null, emptyList()) }
                        else -> {
                            // resource/folder/url/forum/quiz/...: tautan ke Moodle + file yang bisa diunduh
                            val link = if (mod == "url") m.optJSONArray("contents")?.optJSONObject(0)?.optString("fileurl") ?: m.optString("url") else m.optString("url")
                            if (!link.isNullOrEmpty() || files.isNotEmpty()) items += Item(id, "link", name, sname, link ?: "", null, null, files)
                        }
                    }
                }
            }
            val gradeData = safe("Nilai $short", JSONObject()) { call("gradereport_user_get_grade_items", mapOf("courseid" to cid, "userid" to site.userId)) as JSONObject }
            val gi = gradeData.optJSONArray("usergrades")?.optJSONObject(0)?.optJSONArray("gradeitems") ?: JSONArray()
            val grades = (0 until gi.length()).map { gi.getJSONObject(it) }.mapNotNull { g ->
                val n = g.optString("itemname").takeIf { !g.isNull("itemname") && it.isNotEmpty() } ?: if (g.optString("itemtype") == "course") "Total kursus" else return@mapNotNull null
                Grade(n, if (g.isNull("graderaw")) null else g.optDouble("graderaw"), if (g.isNull("grademax")) null else g.optDouble("grademax"))
            }
            Course(cid, c.optString("fullname", short), htmlToText(c.optString("summary")), items, grades)
        }
        return Snapshot(now, courses, warnings)
    }
}

/** Nama aman untuk disk: buang karakter berbahaya dan titik di awal. */
fun safeName(n: String): String = n.replace(Regex("[^\\p{L}\\p{N}._ ()-]+"), "_").trim().trimStart('.').take(120).ifEmpty { "_" }

/** Pastikan [child] berada di dalam [root] (anti path traversal). */
fun File.isInside(root: File) = canonicalPath.startsWith(root.canonicalPath + File.separator)
