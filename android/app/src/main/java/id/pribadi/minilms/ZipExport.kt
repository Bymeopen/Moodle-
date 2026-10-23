package id.pribadi.minilms

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ekspor satu kursus menjadi ZIP: ringkasan teks + file unduhan dalam folder per bagian/item. */
object ZipExport {
    /** Nama entri unik: bila bentrok, tambahkan " (2)", " (3)", ... sebelum ekstensi. */
    fun unique(used: MutableSet<String>, path: String): String {
        var p = path; var n = 2
        val dot = path.lastIndexOf('.').takeIf { it > path.lastIndexOf('/') } ?: path.length
        while (!used.add(p)) { p = path.substring(0, dot) + " ($n)" + path.substring(dot); n++ }
        return p
    }

    /** Entri = (jalur di dalam ZIP, file sumber). Teks tambahan lewat [texts]. Jalur disanitasi per segmen. */
    fun write(out: OutputStream, entries: List<Pair<String, File>>, texts: Map<String, String> = emptyMap()) {
        val used = mutableSetOf<String>()
        ZipOutputStream(out).use { z ->
            for ((path, text) in texts) { z.putNextEntry(ZipEntry(unique(used, clean(path)))); z.write(text.toByteArray()); z.closeEntry() }
            for ((path, file) in entries) {
                z.putNextEntry(ZipEntry(unique(used, clean(path))))
                file.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun clean(path: String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { safeName(it) }
}
