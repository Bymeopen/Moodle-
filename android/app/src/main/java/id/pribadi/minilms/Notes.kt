package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject

/**
 * Catatan pribadi per item kursus. [quote] = bagian materi yang disorot (boleh kosong), [text] = catatan pengguna.
 * Hanya disimpan lokal; tidak pernah dikirim ke Moodle.
 */
data class Note(val id: String, val courseId: Long, val itemId: Long, val quote: String, val text: String, val createdAt: Long)

object Notes {
    const val MAX_QUOTE = 600
    const val MAX_TEXT = 4000

    /** Bersihkan masukan pengguna; null bila kutipan dan catatan sama-sama kosong. */
    fun make(id: String, courseId: Long, itemId: Long, quote: String, text: String, now: Long): Note? {
        val q = quote.trim().replace(Regex("\\s+"), " ").take(MAX_QUOTE)
        val t = text.trim().take(MAX_TEXT)
        return if (q.isEmpty() && t.isEmpty()) null else Note(id, courseId, itemId, q, t, now)
    }

    fun forItem(all: List<Note>, itemId: Long) = all.filter { it.itemId == itemId }.sortedBy { it.createdAt }

    /**
     * Rentang teks [content] yang cocok dengan salah satu kutipan (tanpa peduli huruf besar/kecil dan spasi ganda); rentang yang
     * bertumpuk digabung. Dipakai untuk menggambar sorotan di materi.
     */
    fun ranges(content: String, quotes: List<String>): List<IntRange> {
        if (content.isEmpty()) return emptyList()
        val hay = Search.fold(content)
        val found = mutableListOf<IntRange>()
        for (q in quotes) {
            val needle = Search.fold(q.trim())
            if (needle.length < 2) continue
            // kutipan sudah dinormalkan spasinya; cocokkan juga terhadap teks dengan spasi/baris baru yang berbeda
            val pattern = Regex(needle.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) })
            pattern.findAll(hay).forEach { found += it.range }
        }
        val merged = mutableListOf<IntRange>()
        for (r in found.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, r.last) else merged += r
        }
        return merged
    }

    /** Catatan jadi kartu hafalan: depan = kutipan (atau judul item), belakang = catatan. Null bila tidak ada isi untuk dua sisi. */
    fun toDraft(n: Note, itemTitle: String): Draft? {
        val front = n.quote.ifEmpty { itemTitle }.trim()
        val back = (if (n.quote.isEmpty()) n.text else n.text.ifEmpty { itemTitle }).trim()
        return if (front.isEmpty() || back.isEmpty() || front == back) null else Draft(front.take(300), back.take(1200))
    }

    /** Semua catatan satu kursus sebagai Markdown (untuk dibagikan atau dimasukkan ke paket belajar). */
    fun markdown(course: Course, notes: List<Note>): String {
        val mine = notes.filter { it.courseId == course.id }
        if (mine.isEmpty()) return ""
        val titles = course.items.associate { it.id to it.title }
        val sb = StringBuilder("# Catatan saya — ${course.name}\n")
        for ((itemId, list) in mine.groupBy { it.itemId }.toList().sortedBy { (id, _) -> course.items.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it } }) {
            sb.append("\n## ").append(titles[itemId] ?: "Item lain").append('\n')
            for (n in list.sortedBy { it.createdAt }) {
                if (n.quote.isNotEmpty()) sb.append("\n> ").append(n.quote.replace("\n", " ")).append('\n')
                if (n.text.isNotEmpty()) sb.append('\n').append(n.text).append('\n')
            }
        }
        return sb.toString()
    }
}

fun Note.toJson() = JSONObject().put("id", id).put("courseId", courseId).put("itemId", itemId).put("quote", quote).put("text", text).put("createdAt", createdAt)
fun JSONObject.toNote() = Note(getString("id"), getLong("courseId"), getLong("itemId"), optStr("quote"), optStr("text"), getLong("createdAt"))
fun List<Note>.toJsonArray() = JSONArray(map { it.toJson() })
