package id.pribadi.minilms

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class TarGzTest {
    /** Penulis tar minimal untuk membuat arsip (termasuk yang berbahaya) di tes. */
    private class TarBuilder {
        val out = ByteArrayOutputStream()
        fun entry(name: String, type: Char = '0', content: ByteArray = ByteArray(0), link: String = "", mode: Int = 420, prefix: String = ""): TarBuilder {
            val h = ByteArray(512)
            fun put(off: Int, s: String, len: Int) { s.toByteArray().copyInto(h, off, 0, minOf(len, s.toByteArray().size)) }
            put(0, name, 100); put(100, "%07o".format(mode), 8); put(108, "0000000", 8); put(116, "0000000", 8)
            put(124, "%011o".format(content.size), 12); put(136, "00000000000", 12); put(156, type.toString(), 1); put(157, link, 100)
            put(257, "ustar", 6); put(263, "00", 2); put(345, prefix, 155)
            for (i in 148..155) h[i] = 32
            put(148, "%06o".format(h.sumOf { it.toInt() and 0xFF }), 7); h[154] = 0; h[155] = 32
            out.write(h); out.write(content); out.write(ByteArray((512 - content.size % 512) % 512)); return this
        }
        fun pax(path: String): TarBuilder {
            val rec = " path=$path\n"; var len = rec.length + 1; while ("$len$rec".length != len) len = "$len$rec".length
            return entry("PaxHeader", 'x', "$len$rec".toByteArray())
        }
        fun build(): File { out.write(ByteArray(1024)); val f = File.createTempFile("tar", ".tar.gz"); GZIPOutputStream(f.outputStream()).use { it.write(out.toByteArray()) }; return f }
    }

    private fun dest() = Files.createTempDirectory("stage").toFile()

    @Test fun extractsFilesDirsAbsoluteSymlinksAndModes() {
        val tar = TarBuilder().entry("./", '5', mode = 493).entry("./bin/", '5', mode = 493).entry("./bin/busybox", content = "ELF".toByteArray(), mode = 493)
            .entry("./bin/sh", '2', link = "/bin/busybox", mode = 511).entry("./tmp/", '5', mode = 1023).entry("./etc/motd", content = "halo".toByteArray()).build()
        val d = dest(); val modes = TarGz.extract(tar, d).toMap()
        assertEquals("ELF", File(d, "bin/busybox").readText()); assertEquals("halo", File(d, "etc/motd").readText())
        val link = File(d, "bin/sh").toPath()
        assertTrue(Files.isSymbolicLink(link)); assertEquals("/bin/busybox", Files.readSymbolicLink(link).toString())     // absolut dipertahankan
        assertEquals(493, modes["bin/busybox"]); assertEquals(1023, modes["tmp"])                                        // 01777 (sticky) tercatat
        assertFalse(modes.containsKey("bin/sh"))                                                                          // symlink tidak punya mode
        assertEquals("493".let { "755" }, Integer.toOctalString(modes["bin/busybox"]!!))
        TarGz.deleteTree(d.toPath()); assertFalse(d.exists())
    }

    @Test fun refusesDotDotAndAbsoluteEscape() {
        assertThrows(ApiError::class.java) { TarGz.extract(TarBuilder().entry("../evil", content = "x".toByteArray()).build(), dest()) }
        assertThrows(ApiError::class.java) { TarGz.extract(TarBuilder().entry("a/../../evil", content = "x".toByteArray()).build(), dest()) }
        val d = dest()                                                                                                    // "/etc/x" absolut: dipetakan ke dalam folder tujuan, bukan ke host
        TarGz.extract(TarBuilder().entry("/abs/file", content = "x".toByteArray()).build(), d)
        assertEquals("x", File(d, "abs/file").readText())
    }

    @Test fun refusesWritingThroughArchiveSymlink() {
        val victim = Files.createTempDirectory("victim").toFile()
        val tar = TarBuilder().entry("link", '2', link = victim.absolutePath).entry("link/pwned.txt", content = "x".toByteArray()).build()
        assertThrows(ApiError::class.java) { TarGz.extract(tar, dest()) }
        assertFalse(File(victim, "pwned.txt").exists())                                                                   // tidak ada yang tertulis di luar
        val tar2 = TarBuilder().entry("d", '2', link = "/").entry("d/etc/passwd", content = "x".toByteArray()).build()
        assertThrows(ApiError::class.java) { TarGz.extract(tar2, dest()) }
    }

    @Test fun hardlinkCopiesWithinDestOnlyAndLongNames() {
        val d = dest()
        val long = "dir/" + "n".repeat(120)
        TarGz.extract(TarBuilder().entry("orig", content = "isi".toByteArray(), mode = 493).entry("copy", '1', link = "orig").entry("ghost", '1', link = "tidak-ada")
            .pax(long).entry("dipotong", content = "L".toByteArray()).build(), d)
        assertEquals("isi", File(d, "copy").readText()); assertFalse(File(d, "ghost").exists())
        assertEquals("L", File(d, long).readText())                                                                       // nama panjang lewat header pax
        assertThrows(ApiError::class.java) { TarGz.extract(TarBuilder().entry("h", '1', link = "../../etc/passwd").build(), dest()) }
    }

    @Test fun usesUstarPrefixAndSkipsDevices() {
        val d = dest()
        TarGz.extract(TarBuilder().entry("file.txt", content = "p".toByteArray(), prefix = "usr/share").entry("null", '3').entry("pipe", '6').build(), d)
        assertEquals("p", File(d, "usr/share/file.txt").readText()); assertFalse(File(d, "null").exists() || File(d, "pipe").exists())
    }

    @Test fun corruptAndTruncatedArchivesFailClosed() {
        val good = TarBuilder().entry("a", content = "x".toByteArray()).build()
        val raw = java.util.zip.GZIPInputStream(good.inputStream()).readBytes()
        val bad = raw.copyOf(); bad[10] = (bad[10] + 1).toByte()                                                          // ubah nama: checksum header tidak cocok
        val f = File.createTempFile("bad", ".tar.gz"); GZIPOutputStream(f.outputStream()).use { it.write(bad) }
        assertThrows(ApiError::class.java) { TarGz.extract(f, dest()) }
        val t = File.createTempFile("cut", ".tar.gz"); GZIPOutputStream(t.outputStream()).use { it.write(raw, 0, 600) }     // tubuh file terpotong
        assertThrows(java.io.IOException::class.java) { TarGz.extract(t, dest()) }
    }

    @Test fun modeManifestOnlySafePaths() {
        val m = TarGz.modeManifest(listOf("bin/busybox" to 493, "tmp" to 1023, "a b" to 420, "x;rm -rf /" to 493, "\$(id)" to 493))
        assertEquals("755 bin/busybox\n1777 tmp\n", m)
    }

    @Test fun realRootfsIfCachedExtractsAllSymlinks() {
        val f = File("/tmp/rootfs_check/r.tar.gz"); assumeTrue(f.exists())
        val d = dest(); val modes = TarGz.extract(f, d)
        assertTrue(modes.size > 150)
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(d, "bin/sh").toPath()).toString())
        assertEquals(1023, modes.toMap()["tmp"]); assertEquals(448, modes.toMap()["root"])
        TarGz.deleteTree(d.toPath())
    }
}
