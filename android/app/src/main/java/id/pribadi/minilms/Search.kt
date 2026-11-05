package id.pribadi.minilms

import java.text.Normalizer

enum class HitKind(val label: String) { MATERI("Materi"), TUGAS("Tugas"), TES("Tes"), FORUM("Forum"), FILE("File"), CATATAN("Catatan"), KARTU("Kartu") }

data class SearchHit(val kind: HitKind, val courseId: Long, val itemId: Long, val title: String, val course: String, val snippet: String, val score: Int)

/**
 * Pencarian offline di semua materi yang tersimpan: teks halaman, tugas, forum, tinjauan tes, nama dan isi file yang sudah diunduh,
 * catatan, dan kartu hafalan. Murni (tanpa Android) agar mudah dites. Semua kata kunci harus ada (AND); huruf besar/kecil dan aksen diabaikan.
 */
object Search {
    const val MIN_QUERY = 2
    private const val SNIPPET = 70

    /** Huruf kecil tanpa aksen, **panjang tetap sama** dengan aslinya (tiap karakter dipetakan satu-satu) supaya posisi bisa dipakai di teks asli. */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val lower = Character.toLowerCase(ch)
            val base = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD).firstOrNull() ?: lower
            sb.append(if (base.isWhitespace()) ' ' else base)
        }
        return sb.toString()
    }

    fun terms(q: String): List<String> = fold(q).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 || (it.length == 1 && it[0].isDigit()) }.distinct().take(8)

    /** Potongan teks asli di sekitar kecocokan pertama; spasi/baris baru dirapatkan. */
    fun snippet(text: String, folded: String, terms: List<String>): String {
        val at = terms.map { folded.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = maxOf(0, at - SNIPPET / 3).let { s -> if (s > 0) text.indexOf(' ', s).let { sp -> if (sp in s..(s + 12)) sp + 1 else s } else 0 }
        val to = minOf(text.length, from + SNIPPET * 2)
        val clip = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        return (if (from > 0) "…" else "") + clip + (if (to < text.length) "…" else "")
    }

    private class Field(val text: String, val weight: Int) { val folded = fold(text) }

    private fun score(fields: List<Field>, terms: List<String>): Int {
        var total = 0
        for (t in terms) {
            var s = 0
            for (f in fields) {
                if (f.folded.isEmpty()) continue
                val first = f.folded.indexOf(t)
                if (first < 0) continue
                val extra = minOf(2, Regex.escape(t).toRegex().findAll(f.folded).count() - 1)
                s += f.weight + extra
            }
            if (s == 0) return 0         // satu kata kunci hilang: bukan hasil
            total += s
        }
        return total
    }

    /**
     * [fileText] mengembalikan teks file terunduh (docx/pptx/txt) atau null; dipanggil hanya untuk file yang ada di item.
     * Hasil diurutkan dari skor tertinggi, dibatasi [limit].
     */
    fun search(courses: List<Course>, notes: List<Note>, cards: List<Card>, query: String, limit: Int = 60,
               fileText: (Course, Item, FileRef) -> String? = { _, _, _ -> null }): List<SearchHit> {
        val ts = terms(query)
        if (query.trim().length < MIN_QUERY || ts.isEmpty()) return emptyList()
        val hits = mutableListOf<SearchHit>()
        val byId = courses.associateBy { it.id }
        for (c in courses) for (i in c.items) {
            val review = i.attempts.joinToString("\n") { it.review }
            val fields = listOf(Field(i.title, 10), Field(i.section, 3), Field(i.content, 2), Field(review, 1), Field(i.files.joinToString(" ") { it.name }, 6))
            val s = score(fields, ts)
            if (s > 0) {
                val body = listOf(fields[2], fields[3], fields[4], fields[1], fields[0]).firstOrNull { f -> f.folded.isNotEmpty() && ts.any { it in f.folded } } ?: fields[0]
                val kind = when (i.type) { "assignment" -> HitKind.TUGAS; "quiz" -> HitKind.TES; "forum" -> HitKind.FORUM; else -> HitKind.MATERI }
                hits += SearchHit(kind, c.id, i.id, i.title, c.name, snippet(body.text, body.folded, ts), s)
            }
            for (f in i.files) {
                val text = fileText(c, i, f) ?: continue
                val fs = Field(text, 2)
                val fsScore = score(listOf(Field(f.name, 6), fs), ts)
                if (fsScore > 0 && ts.all { it in fs.folded }) hits += SearchHit(HitKind.FILE, c.id, i.id, f.name, c.name, snippet(text, fs.folded, ts), fsScore)
            }
        }
        for (n in notes) {
            val s = score(listOf(Field(n.text, 4), Field(n.quote, 3)), ts)
            if (s > 0) {
                val c = byId[n.courseId]
                val title = c?.items?.firstOrNull { it.id == n.itemId }?.title ?: "Catatan"
                val body = if (n.text.isNotEmpty()) n.text else n.quote
                hits += SearchHit(HitKind.CATATAN, n.courseId, n.itemId, title, c?.name.orEmpty(), snippet(body, fold(body), ts), s)
            }
        }
        for (k in cards) {
            val s = score(listOf(Field(k.front, 4), Field(k.back, 2)), ts)
            if (s > 0) hits += SearchHit(HitKind.KARTU, k.courseId, 0, k.front.take(80), byId[k.courseId]?.name.orEmpty(), snippet(k.back, fold(k.back), ts), s)
        }
        return hits.sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.title }).take(limit)
    }
}
