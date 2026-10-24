package id.pribadi.minilms

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream

/**
 * Ekstraktor tar.gz sendiri untuk rootfs Linux. `tar` bawaan Android (toybox) menolak symlink absolut seperti `bin/sh -> /bin/busybox`
 * karena memeriksa targetnya di sistem Android asli, bukan di dalam chroot. Di sini symlink dibuat apa adanya (targetnya baru bermakna
 * di dalam chroot), tetapi penulisan file tidak pernah boleh keluar dari folder tujuan: nama dengan `..`/absolut ditolak dan tidak ada
 * entri yang boleh ditulis melalui symlink yang dibuat sebelumnya. Gagal tertutup: arsip tidak aman = seluruh ekstraksi dibatalkan.
 */
object TarGz {
    const val MAX_TOTAL = 200L * 1024 * 1024
    const val MAX_ENTRIES = 20_000

    /** Hasil: daftar (path relatif, mode) untuk file dan folder, agar izin (bit eksekusi, sticky) diterapkan oleh root setelah dipindah. */
    fun extract(src: File, dest: File): List<Pair<String, Int>> {
        deleteTree(dest.toPath()); dest.mkdirs()
        val root = dest.toPath().toAbsolutePath().normalize()
        val modes = mutableListOf<Pair<String, Int>>()
        var total = 0L; var entries = 0
        GZIPInputStream(BufferedInputStream(src.inputStream())).use { input ->
            var longName: String? = null; var longLink: String? = null
            var paxPath: String? = null; var paxLink: String? = null; var paxSize: Long? = null
            val hdr = ByteArray(512); var zeroBlocks = 0
            while (true) {
                if (!readFully(input, hdr)) break
                if (hdr.all { it == 0.toByte() }) { if (++zeroBlocks >= 2) break else continue }
                zeroBlocks = 0
                if (!checksumOk(hdr)) throw ApiError("Arsip rootfs rusak (checksum header tar tidak cocok)")
                if (++entries > MAX_ENTRIES) throw ApiError("Arsip rootfs terlalu banyak entri")
                val type = hdr[156].toInt().toChar()
                var size = paxSize ?: parseNumber(hdr, 124, 12)
                var name = paxPath ?: longName ?: (str(hdr, 345, 155).let { p -> if (p.isNotEmpty() && str(hdr, 257, 5) == "ustar") "$p/" else "" } + str(hdr, 0, 100))
                val link = paxLink ?: longLink ?: str(hdr, 157, 100)
                val mode = parseNumber(hdr, 100, 8).toInt() and 0xFFF
                paxPath = null; paxLink = null; paxSize = null; longName = null; longLink = null
                if (size < 0 || size > MAX_TOTAL) throw ApiError("Ukuran entri tar tidak wajar")
                when (type) {
                    'x' -> { val rec = readBytes(input, size); skipPad(input, size); parsePax(String(rec, Charsets.UTF_8)).let { m -> paxPath = m["path"]; paxLink = m["linkpath"]; paxSize = m["size"]?.toLongOrNull() }; continue }
                    'g' -> { skip(input, size); skipPad(input, size); continue }
                    'L' -> { longName = String(readBytes(input, size)).trimEnd('\u0000', '\n'); skipPad(input, size); continue }
                    'K' -> { longLink = String(readBytes(input, size)).trimEnd('\u0000', '\n'); skipPad(input, size); continue }
                }
                val rel = cleanName(name)
                if (rel.isEmpty()) { skip(input, size); skipPad(input, size); continue }                  // entri "./" itu sendiri
                val target = safeResolve(root, rel)
                when (type) {
                    '0', '\u0000', '7' -> {
                        total += size; if (total > MAX_TOTAL) throw ApiError("Isi arsip rootfs melebihi batas")
                        Files.createDirectories(target.parent)
                        Files.deleteIfExists(target)
                        Files.newOutputStream(target).use { out -> copy(input, out, size) }
                        modes += rel to mode
                    }
                    '5' -> { Files.createDirectories(target); modes += rel to mode }
                    '2' -> {
                        if (link.isEmpty()) throw ApiError("Symlink tanpa target di arsip")
                        Files.createDirectories(target.parent); Files.deleteIfExists(target)
                        Files.createSymbolicLink(target, Paths.get(link))                              // target absolut dibiarkan: bermakna di dalam chroot
                    }
                    '1' -> {                                                                            // hardlink: salin isi dari berkas yang sudah ada di dalam folder tujuan
                        val from = safeResolve(root, cleanName(link))
                        if (Files.isRegularFile(from, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { Files.createDirectories(target.parent); Files.deleteIfExists(target); Files.copy(from, target); modes += rel to mode }
                    }
                    else -> { /* perangkat, fifo, dll.: dilewati */ }
                }
                if (type != '0' && type != '\u0000' && type != '7') { skip(input, size) }
                skipPad(input, size)
            }
        }
        return modes
    }

    /** Buang awalan `./` dan `/`; tolak komponen `..`. */
    fun cleanName(name: String): String {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw ApiError("Arsip tidak aman: jalur memuat '..' ($name)")
        return parts.joinToString("/")
    }

    /** Jalur tujuan di bawah [root]; tidak ada leluhur yang boleh berupa symlink (mencegah penulisan lewat symlink buatan arsip). */
    fun safeResolve(root: Path, rel: String): Path {
        var cur = root
        val parts = rel.split('/').filter { it.isNotEmpty() }
        for ((i, p) in parts.withIndex()) {
            cur = cur.resolve(p)
            if (i < parts.lastIndex && Files.isSymbolicLink(cur)) throw ApiError("Arsip tidak aman: penulisan melewati symlink ($rel)")
        }
        if (!cur.normalize().startsWith(root)) throw ApiError("Arsip tidak aman: jalur keluar dari folder tujuan ($rel)")
        return cur
    }

    /** Hapus pohon tanpa mengikuti symlink (symlink hasil ekstraksi menunjuk ke path absolut milik host). */
    fun deleteTree(p: Path) {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult { Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }

    /** Manifest izin untuk skrip root: hanya jalur berkarakter aman; format "mode_oktal path". */
    fun modeManifest(modes: List<Pair<String, Int>>): String = modes.filter { Regex("[A-Za-z0-9._+@/-]+").matches(it.first) }
        .joinToString("\n") { "%o %s".format(it.second, it.first) } + "\n"

    private fun str(b: ByteArray, off: Int, len: Int): String { var e = off; while (e < off + len && b[e] != 0.toByte()) e++; return String(b, off, e - off, Charsets.UTF_8) }

    private fun parseNumber(b: ByteArray, off: Int, len: Int): Long {
        if (b[off].toInt() and 0x80 != 0) {                                                          // base-256 (ukuran besar)
            var v = (b[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) { v = (v shl 8) or (b[off + i].toLong() and 0xFF); if (v > Long.MAX_VALUE / 512) return -1 }
            return v
        }
        val s = str(b, off, len).trim(); return if (s.isEmpty()) 0 else s.toLongOrNull(8) ?: -1
    }

    private fun checksumOk(h: ByteArray): Boolean {
        var sum = 0L; for (i in 0 until 512) sum += if (i in 148..155) 32 else (h[i].toLong() and 0xFF)
        return parseNumber(h, 148, 8) == sum
    }

    private fun parsePax(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>(); var i = 0
        while (i < s.length) {
            val sp = s.indexOf(' ', i); if (sp < 0) break
            val len = s.substring(i, sp).toIntOrNull() ?: break
            val rec = s.substring(sp + 1, minOf(s.length, i + len)).trimEnd('\n')
            rec.indexOf('=').takeIf { it > 0 }?.let { out[rec.substring(0, it)] = rec.substring(it + 1) }
            i += len.coerceAtLeast(1)
        }
        return out
    }

    private fun readFully(i: InputStream, b: ByteArray): Boolean { var n = 0; while (n < b.size) { val k = i.read(b, n, b.size - n); if (k < 0) return if (n == 0) false else throw EOFException("Arsip terpotong"); n += k }; return true }
    private fun readBytes(i: InputStream, size: Long): ByteArray { if (size > 1_000_000) throw ApiError("Header tar terlalu besar"); val b = ByteArray(size.toInt()); var n = 0; while (n < b.size) { val k = i.read(b, n, b.size - n); if (k < 0) throw EOFException("Arsip terpotong"); n += k }; return b }
    private fun skip(i: InputStream, size: Long) { var left = size; val buf = ByteArray(8192); while (left > 0) { val k = i.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (k < 0) throw EOFException("Arsip terpotong"); left -= k } }
    private fun skipPad(i: InputStream, size: Long) { val pad = (512 - size % 512) % 512; if (pad > 0) skip(i, pad) }
    private fun copy(i: InputStream, o: java.io.OutputStream, size: Long) { var left = size; val buf = ByteArray(64 * 1024); while (left > 0) { val k = i.read(buf, 0, minOf(left, buf.size.toLong()).toInt()); if (k < 0) throw EOFException("Arsip terpotong"); o.write(buf, 0, k); left -= k } }
}
