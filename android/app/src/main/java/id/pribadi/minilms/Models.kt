package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject

/** Satu file materi di Moodle. [modified] dipakai untuk mendeteksi file yang berubah. */
data class FileRef(val name: String, val path: String, val url: String, val size: Long, val mime: String?, val modified: Long)

data class Item(
    val id: Long,            // course module id
    val type: String,        // page | link | assignment
    val title: String,
    val section: String,
    val content: String,     // teks halaman/instruksi tugas, atau URL tautan
    val dueAt: Long?,        // epoch detik
    val maxGrade: Double?,
    val files: List<FileRef>,
    val url: String = "",        // halaman modul di Moodle (untuk "Buka di Moodle"/auto-login)
) {
    /** Tugas atau tes: punya tenggat dan dihitung di Tenggat/pengingat. */
    val isTask get() = type == "assignment" || type == "quiz"
}

data class Grade(val name: String, val grade: Double?, val max: Double?)

data class Course(val id: Long, val name: String, val summary: String, val items: List<Item>, val grades: List<Grade>)

/** Hasil satu kali sinkron; disimpan sebagai JSON agar bisa dibuka offline. */
data class Snapshot(val syncedAt: Long, val courses: List<Course>, val warnings: List<String>)

data class Site(val url: String, val token: String, val userId: Long, val fullName: String, val siteName: String, val privateToken: String = "")

// ---------- JSON ----------
private fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)
private fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k) || !has(k)) null else getLong(k)
private inline fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

fun FileRef.toJson() = JSONObject().put("name", name).put("path", path).put("url", url).put("size", size).put("mime", mime ?: JSONObject.NULL).put("modified", modified)
fun JSONObject.toFileRef() = FileRef(getString("name"), getString("path"), getString("url"), getLong("size"), optString("mime").ifEmpty { null }.takeIf { !isNull("mime") }, getLong("modified"))

fun Item.toJson() = JSONObject().put("id", id).put("type", type).put("title", title).put("section", section).put("content", content)
    .put("dueAt", dueAt ?: JSONObject.NULL).put("maxGrade", maxGrade ?: JSONObject.NULL).put("files", JSONArray(files.map { it.toJson() })).put("url", url)
fun JSONObject.toItem() = Item(getLong("id"), getString("type"), getString("title"), getString("section"), getString("content"),
    optLongOrNull("dueAt"), optDoubleOrNull("maxGrade"), getJSONArray("files").mapObjects { it.toFileRef() }, optString("url"))

fun Grade.toJson() = JSONObject().put("name", name).put("grade", grade ?: JSONObject.NULL).put("max", max ?: JSONObject.NULL)
fun JSONObject.toGrade() = Grade(getString("name"), optDoubleOrNull("grade"), optDoubleOrNull("max"))

fun Course.toJson() = JSONObject().put("id", id).put("name", name).put("summary", summary)
    .put("items", JSONArray(items.map { it.toJson() })).put("grades", JSONArray(grades.map { it.toJson() }))
fun JSONObject.toCourse() = Course(getLong("id"), getString("name"), getString("summary"),
    getJSONArray("items").mapObjects { it.toItem() }, getJSONArray("grades").mapObjects { it.toGrade() })

fun Snapshot.toJson() = JSONObject().put("syncedAt", syncedAt).put("courses", JSONArray(courses.map { it.toJson() })).put("warnings", JSONArray(warnings))
fun JSONObject.toSnapshot() = Snapshot(getLong("syncedAt"), getJSONArray("courses").mapObjects { it.toCourse() },
    getJSONArray("warnings").let { a -> (0 until a.length()).map { a.getString(it) } })

fun Site.toJson() = JSONObject().put("url", url).put("token", token).put("userId", userId).put("fullName", fullName).put("siteName", siteName).put("privateToken", privateToken)
fun JSONObject.toSite() = Site(getString("url"), getString("token"), getLong("userId"), getString("fullName"), getString("siteName"), optString("privateToken"))
