package id.pribadi.minilms

import java.io.File
import java.util.zip.ZipFile

/**
 * Ekstraksi teks dari file materi tanpa dependensi: txt/md/csv, docx, pptx (keduanya ZIP berisi XML).
 * PDF tidak diekstrak di sini; PDF dikirim langsung ke Claude sebagai dokumen (lihat [Doc]).
 */
object FileText {
    private const val MAX_XML = 20L * 1024 * 1024      // batas ukuran terurai per bagian (anti zip-bomb)
    private const val MAX_PLAIN = 2L * 1024 * 1024

    private fun unescape(s: String) = Regex("&(amp|lt|gt|quot|apos|#\\d+);").replace(s) { m ->
        when (val e = m.groupValues[1]) { "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; else -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
    }

    private fun readEntry(zip: ZipFile, name: String): String? {
        val e = zip.getEntry(name) ?: return null
        if (e.size > MAX_XML) throw ApiError("Bagian file terlalu besar: $name")
        zip.getInputStream(e).use { input ->
            val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(32 * 1024); var total = 0L
            while (true) { val n = input.read(chunk); if (n < 0) break; total += n; if (total > MAX_XML) throw ApiError("Bagian file terlalu besar: $name"); buf.write(chunk, 0, n) }
            return buf.toString("UTF-8")
        }
    }

    private fun tidy(s: String) = s.lines().map { it.trimEnd() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    fun docx(file: File): String = ZipFile(file).use { z ->
        val xml = readEntry(z, "word/document.xml") ?: throw ApiError("Bukan file docx yang valid")
        tidy(unescape(xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab\\s*/>"), "\t").replace(Regex("<w:br\\s*/>"), "\n").replace(Regex("<[^>]+>"), "")))
    }

    fun pptx(file: File): String = ZipFile(file).use { z ->
        val slides = z.entries().asSequence().mapNotNull { e -> Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(e.name)?.let { it.groupValues[1].toInt() to e.name } }.sortedBy { it.first }.toList()
        if (slides.isEmpty()) throw ApiError("Bukan file pptx yang valid")
        slides.joinToString("\n\n") { (n, name) ->
            val xml = readEntry(z, name) ?: ""
            "[Slide $n]\n" + tidy(unescape(xml.replace(Regex("</a:p>"), "\n").replace(Regex("<[^>]+>"), "")))
        }
    }

    /** Teks file atau null bila tipenya tidak didukung / gagal dibaca. */
    fun extract(file: File, name: String): String? = try {
        when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "md", "csv" -> if (file.length() <= MAX_PLAIN) file.readText() else null
            "docx" -> docx(file)
            "pptx" -> pptx(file)
            else -> null
        }?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { null }

    fun isPdf(name: String, mime: String?) = mime == "application/pdf" || name.lowercase().endsWith(".pdf")
}
