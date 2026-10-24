package id.pribadi.minilms

import java.time.Instant
import java.time.ZoneId

/** Satu sumber teks dalam paket belajar (berkas .md). */
data class PackText(val name: String, val content: String)

/**
 * Paket belajar: materi kursus diolah jadi berkas yang rapi untuk diproses di alat lain (NotebookLM untuk podcast/Audio Overview, panduan belajar,
 * bahan ajar; atau Gemini/Claude/ChatGPT). Teks dipecah per bagian kursus agar tiap sumber tidak melewati batas panjang, tautan (YouTube dll.)
 * dikumpulkan di satu berkas, dan PDF yang sudah diunduh ikut dikirim apa adanya. Murni dan dites.
 */
object StudyPack {
    /** Batas karakter per sumber teks (batas NotebookLM jauh lebih besar; ini menjaga berkas tetap nyaman diproses). */
    const val MAX_CHARS = 150_000
    /** Batas jumlah sumber per notebook NotebookLM (versi gratis). */
    const val MAX_SOURCES = 50

    private fun due(sec: Long) = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ')

    /** Blok teks tiap item (judul, tenggat, isi, tinjauan tes, isi file yang bisa diekstrak); item tautan murni dilewati (masuk berkas tautan). */
    private fun block(i: Item, fileText: (Item, FileRef) -> String?): String? {
        val sb = StringBuilder()
        val files = i.files.mapNotNull { f -> fileText(i, f)?.let { f to it } }
        val body = if (i.type == "link") "" else i.content.trim()
        val review = i.attempts.filter { it.review.isNotBlank() }
        if (body.isEmpty() && files.isEmpty() && review.isEmpty() && i.type == "link") return null
        sb.append("### ").append(i.title).append("  (").append(when (i.type) { "assignment" -> "tugas"; "quiz" -> "tes"; "forum" -> "forum"; "page" -> "halaman"; else -> "materi" }).append(")\n")
        i.dueAt?.let { sb.append("Tenggat: ").append(due(it)).append('\n') }
        if (body.isNotEmpty()) sb.append('\n').append(body).append('\n')
        for ((f, t) in files) sb.append("\n#### Isi file ").append(f.name).append('\n').append(t.trim()).append('\n')
        for (a in review) sb.append("\n#### Soal dan jawaban percobaan ").append(a.number).append('\n').append(a.review.trim()).append('\n')
        return sb.toString()
    }

    /** Sumber teks per bagian kursus, digabung berurutan sampai [max] karakter; blok yang melebihi [max] dipotong. */
    fun texts(c: Course, fileText: (Item, FileRef) -> String? = { _, _ -> null }, max: Int = MAX_CHARS): List<PackText> {
        val blocks = mutableListOf<String>()
        for ((section, items) in c.items.groupBy { it.section }) {
            val bs = items.mapNotNull { block(it, fileText) }
            if (bs.isEmpty()) continue
            val header = if (section.isBlank()) "" else "## $section\n\n"
            blocks += (header + bs.first()); blocks.addAll(bs.drop(1))
        }
        val parts = mutableListOf<StringBuilder>(StringBuilder())
        for (b in blocks.flatMap { if (it.length > max) it.chunked(max) else listOf(it) }) {
            if (parts.last().isNotEmpty() && parts.last().length + b.length + 2 > max) parts += StringBuilder()
            parts.last().append(b).append('\n')
        }
        val real = parts.filter { it.isNotBlank() }
        val base = safeName(c.name)
        return real.mapIndexed { n, sb ->
            PackText("$base - bagian ${n + 1}.md", "# ${c.name} — bagian ${n + 1} dari ${real.size}\n\n" + (if (n == 0 && c.summary.isNotBlank()) c.summary.trim() + "\n\n" else "") + sb.toString().trimEnd() + "\n")
        }
    }

    /** Daftar tautan luar (YouTube, rekaman, situs) per bagian; null bila tidak ada. NotebookLM bisa menambah tautan situs/YouTube sebagai sumber. */
    fun links(c: Course): PackText? {
        val lines = c.items.filter { it.type == "link" && it.content.startsWith("http") && it.content != it.url }
        if (lines.isEmpty()) return null
        val sb = StringBuilder("# Tautan — ${c.name}\n\nSalin tautan di bawah sebagai sumber (situs web atau YouTube) di alat pengolah materi Anda.\n")
        for ((section, items) in lines.groupBy { it.section }) {
            sb.append("\n## ").append(section.ifBlank { "Umum" }).append('\n')
            for (i in items) sb.append("- ").append(i.title).append(": ").append(i.content).append('\n')
        }
        return PackText("Tautan - ${safeName(c.name)}.md", sb.toString())
    }

    /** Pilih sumber yang dikirim: teks dulu, lalu tautan, lalu PDF, dipotong ke [limit]. Mengembalikan (yang dikirim, jumlah yang tidak muat). */
    fun <T> limit(texts: List<T>, links: List<T>, pdfs: List<T>, limit: Int = MAX_SOURCES): Pair<List<T>, Int> {
        val all = texts + links + pdfs
        return all.take(limit) to maxOf(0, all.size - limit)
    }
}
