package id.pribadi.minilms

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL

/** Satu file yang menunggu diunduh, beserta konteksnya. */
data class Pending(val course: Course, val item: Item, val file: FileRef)

/**
 * Penyimpanan lokal: sesi login, snapshot kursus (JSON), dan file unduhan di filesDir/courses/.
 * Semua data ada di penyimpanan privat aplikasi; hilang saat aplikasi di-uninstall.
 */
class Repo private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val prefs = app.getSharedPreferences("minilms", Context.MODE_PRIVATE)
    private val snapFile = File(app.filesDir, "snapshot.json")
    val root = File(app.filesDir, "courses")

    private val _site = MutableStateFlow(prefs.getString("site", null)?.let { runCatching { JSONObject(it).toSite() }.getOrNull() })
    val site: StateFlow<Site?> = _site
    private val _snapshot = MutableStateFlow(loadSnapshot())
    val snapshot: StateFlow<Snapshot?> = _snapshot
    /** Naik setiap kali status unduhan berubah, agar UI menghitung ulang. */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick
    fun bump() { _tick.value++ }

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(v) = prefs.edit().putBoolean("wifi_only", v).apply()
    var reminders: Boolean
        get() = prefs.getBoolean("reminders", true)
        set(v) = prefs.edit().putBoolean("reminders", v).apply()

    // ---------- sesi ----------
    fun saveSite(s: Site) { prefs.edit().putString("site", s.toJson().toString()).apply(); _site.value = s }

    /** Keluar: hapus token, snapshot, dan semua file unduhan. */
    fun logout() {
        prefs.edit().clear().apply()
        snapFile.delete(); root.deleteRecursively()
        _site.value = null; _snapshot.value = null; bump()
    }

    // ---------- SSO yang sedang berjalan ----------
    fun beginSso(base: String, passport: String) = prefs.edit().putString("sso_base", base).putString("sso_passport", passport).apply()
    fun pendingSso(): Pair<String, String>? = prefs.getString("sso_base", null)?.let { b -> prefs.getString("sso_passport", null)?.let { b to it } }
    fun clearSso() = prefs.edit().remove("sso_base").remove("sso_passport").apply()

    // ---------- snapshot ----------
    private fun loadSnapshot(): Snapshot? = runCatching { JSONObject(snapFile.readText()).toSnapshot() }.getOrNull()

    /** Tulis atomik supaya snapshot lama tidak rusak bila proses mati di tengah. */
    fun saveSnapshot(s: Snapshot) {
        val tmp = File(snapFile.path + ".tmp")
        tmp.writeText(s.toJson().toString())
        if (!tmp.renameTo(snapFile)) throw IOException("Gagal menyimpan data kursus")
        _snapshot.value = s
        // file yang berubah di Moodle (modified baru) dianggap belum diunduh lagi; dihitung di isDownloaded()
        bump()
    }

    /** Sinkron dari Moodle. Bila gagal, snapshot lama tetap dipakai. */
    fun sync(): Snapshot {
        val site = _site.value ?: throw ApiError("Belum login")
        val snap = MoodleApi.fetchAll(site)
        saveSnapshot(snap)
        return snap
    }

    // ---------- file ----------
    fun fileOf(courseId: Long, itemId: Long, f: FileRef): File {
        val parts = f.path.split('/').filter { it.isNotEmpty() }.map(::safeName) + safeName(f.name)
        val dest = parts.fold(File(root, "$courseId/$itemId")) { dir, p -> File(dir, p) }
        require(dest.isInside(root)) { "Path file tidak aman" }
        return dest
    }

    private fun key(courseId: Long, itemId: Long, f: FileRef) = "dl:$courseId/$itemId/${f.url.hashCode()}"

    fun isDownloaded(courseId: Long, itemId: Long, f: FileRef): Boolean {
        val file = fileOf(courseId, itemId, f)
        return file.isFile && file.length() > 0 && prefs.getLong(key(courseId, itemId, f), -1L) == f.modified
    }

    fun pending(courseId: Long? = null): List<Pending> =
        (_snapshot.value?.courses ?: emptyList()).filter { courseId == null || it.id == courseId }.flatMap { c ->
            c.items.flatMap { i -> i.files.filter { !isDownloaded(c.id, i.id, it) }.map { Pending(c, i, it) } }
        }

    data class CourseStats(val files: Int, val downloaded: Int, val bytes: Long, val downloadedBytes: Long)

    fun stats(c: Course): CourseStats {
        var n = 0; var d = 0; var b = 0L; var db = 0L
        for (i in c.items) for (f in i.files) { n++; b += f.size; if (isDownloaded(c.id, i.id, f)) { d++; db += f.size } }
        return CourseStats(n, d, b, db)
    }

    fun storageUsed(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun deleteDownloads(courseId: Long? = null) {
        val target = if (courseId == null) root else File(root, courseId.toString())
        target.deleteRecursively()
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("dl:" + (courseId?.let { "$it/" } ?: "")) }.forEach { e.remove(it) }
        e.apply(); bump()
    }

    /**
     * Unduh satu file ke penyimpanan privat. Token dikirim lewat POST body ke host Moodle yang sama saja.
     * [shouldStop] dicek tiap potongan agar jeda/batal responsif; file parsial dihapus.
     */
    fun download(site: Site, p: Pending, shouldStop: () -> Boolean, onBytes: (Long) -> Unit = {}) {
        val f = p.file
        val a = URL(f.url); val b = URL(site.url)
        if (a.protocol != "https" || a.host != b.host || a.port != b.port) throw ApiError("File berasal dari host lain; dilewati")
        val dest = fileOf(p.course.id, p.item.id, f)
        dest.parentFile!!.mkdirs()
        if (app.filesDir.usableSpace < f.size + 50L * 1024 * 1024) throw ApiError("Ruang penyimpanan tidak cukup")
        val part = File(dest.path + ".part")
        val conn = HttpTransport.open(f.url, mapOf("token" to site.token))
        try {
            if (conn.responseCode != 200) throw ApiError("HTTP ${conn.responseCode}")
            if (conn.contentType?.contains("json") == true) throw ApiError("Moodle menolak akses file (token/izin)")
            conn.inputStream.use { input -> part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (shouldStop()) { part.delete(); throw InterruptedException("dihentikan") }
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); onBytes(n.toLong())
                }
            } }
            if (!part.renameTo(dest)) { dest.delete(); if (!part.renameTo(dest)) throw IOException("Gagal menyimpan file") }
            prefs.edit().putLong(key(p.course.id, p.item.id, f), f.modified).apply()
        } catch (e: Exception) { part.delete(); throw e }
        finally { conn.disconnect() }
        bump()
    }

    companion object {
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) { inst ?: Repo(ctx).also { inst = it } }
    }
}
