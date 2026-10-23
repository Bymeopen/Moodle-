package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Riwayat chat per kursus (kunci "umum" untuk tanpa kursus). Lampiran PDF tidak disimpan. */
class ChatStore(private val dir: File) {
    private fun file(key: String) = File(dir, "${safeName(key)}.json")

    fun load(key: String): List<ChatMsg> = try {
        val a = JSONObject(file(key).readText()).getJSONArray("msgs")
        (0 until a.length()).map { a.getJSONObject(it).let { m -> ChatMsg(m.getString("role"), m.getString("text")) } }
    } catch (e: Exception) { emptyList() }

    fun save(key: String, msgs: List<ChatMsg>) {
        val clean = msgs.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) { file(key).delete(); return }
        dir.mkdirs()
        val tmp = File(file(key).path + ".tmp")
        tmp.writeText(JSONObject().put("msgs", JSONArray(clean.map { JSONObject().put("role", it.role).put("text", it.text) })).toString())
        if (!tmp.renameTo(file(key))) throw IOException("Gagal menyimpan percakapan")
    }

    fun delete(key: String) { file(key).delete() }
}
