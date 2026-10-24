package id.pribadi.minilms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ChrootScriptTest {
    private val yaml = """
- 
  title: "Mini root filesystem"
  desc: |
    Minimal root filesystem.
  branch: v3.24
  arch: aarch64
  version: 3.24.3
  flavor: alpine-minirootfs
  file: alpine-minirootfs-3.24.3-aarch64.tar.gz
  size: 4029999
  sha256: 4d3f80afaebd447c5591a63a73bde380a149fe8b2d85488cafabb2ff97551f50
  sha512: abc
-
  title: "Netboot"
  flavor: alpine-netboot
  arch: aarch64
  file: alpine-netboot-3.24.3-aarch64.tar.gz
  sha256: 1111111111111111111111111111111111111111111111111111111111111111
"""

    @Test fun shQuoteRoundTripsThroughRealShell() {
        assumeTrue(File("/bin/sh").exists())
        listOf("halo dunia", "it's", "a'b'c", "\$(rm -rf /)", "`id`", "a;b|c&d", "baris\nbaru", "\"kutip\"", "\\\\", "*", "").forEach { s ->
            val p = ProcessBuilder("/bin/sh", "-c", "printf %s " + Chroot.shQuote(s)).redirectErrorStream(true).start()
            assertEquals("untuk: $s", s, p.inputStream.readBytes().decodeToString().also { p.waitFor() })
        }
    }

    @Test fun parseReleasePicksMinirootfsForArchAndValidates() {
        val r = Chroot.parseRelease(yaml, "aarch64")!!
        assertEquals("alpine-minirootfs-3.24.3-aarch64.tar.gz", r.file); assertEquals(4029999L, r.size)
        assertEquals("https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-3.24.3-aarch64.tar.gz", r.url)
        assertNull(Chroot.parseRelease(yaml, "x86_64"))                                    // arsitektur lain tidak ada
        assertNull(Chroot.parseRelease(yaml.replace("4d3f80af", "ZZZZ"), "aarch64"))        // checksum tidak sahih
        assertNull(Chroot.parseRelease(yaml.replace("alpine-minirootfs-3.24.3-aarch64.tar.gz", "../../evil.tar.gz"), "aarch64"))   // nama berkas disusupi
        assertNull(Chroot.parseRelease("", "aarch64"))
    }

    @Test fun abiMapping() {
        assertEquals("aarch64", Chroot.abiToAlpine(listOf("arm64-v8a", "armeabi-v7a"))); assertEquals("armv7", Chroot.abiToAlpine(listOf("armeabi-v7a")))
        assertEquals("x86_64", Chroot.abiToAlpine(listOf("x86_64", "x86"))); assertNull(Chroot.abiToAlpine(listOf("mips")))
    }

    @Test fun everyScriptGuardsRootPath() {
        listOf(Chroot.checkScript(), Chroot.installScript("/c/stage", "/c/modes.txt"), Chroot.mountScript("/a", "/b"), Chroot.unmountScript(), Chroot.removeScript(), Chroot.execScript("ls", "/a", "/b")).forEach {
            assertTrue(it.startsWith("R=${Chroot.ROOT}\n[ \"\$R\" = \"${Chroot.ROOT}\" ] || exit 9"))
        }
    }

    @Test fun removeRefusesWhileMountsRemainAndUnmountsInReverse() {
        val s = Chroot.removeScript()
        assertTrue(s.indexOf("umount") < s.indexOf("MASIH_ADA_MOUNT_DIBATALKAN"))
        assertTrue(s.indexOf("exit 3") < s.indexOf("rm -rf"))                              // rm hanya setelah pemeriksaan mount
        assertTrue(s.contains("mnt/library mnt/lms mnt/sdcard dev sys proc"))              // urutan terbalik dari mount
        assertEquals(1, Regex("rm -rf").findAll(s).count())
        assertFalse(Chroot.unmountScript().contains("rm -rf"))
    }

    @Test fun lmsAndLibraryMountedReadOnlyOrNotAtAll() {
        val s = Chroot.mountScript("/data/data/x/files/courses", "/data/data/x/files/library")
        assertTrue(s.contains("mount -o remount,ro,bind \$R/mnt/lms"))
        assertTrue(s.contains("umount \$R/mnt/lms; echo \"materi LMS tidak di-mount"))
        assertTrue(s.contains("mount --bind '/data/data/x/files/courses'"))
        assertTrue(Chroot.mountScript("/it's/odd", "/b").contains("'/it'\\''s/odd'"))      // path pun di-quote
    }

    @Test fun userCommandIsOnlyEverAQuotedArgument() {
        val evil = "echo ok'; rm -rf /; echo '"
        val s = Chroot.execScript(evil, "/a", "/b")
        assertTrue(s.contains("-c " + Chroot.shQuote(evil)))
        assertEquals(1, s.lines().count { it.startsWith("chroot ") })
        assertFalse(s.lines().any { it.startsWith("rm -rf /") })                           // tidak ada baris terpisah hasil injeksi
    }
}

class LinuxControllerTest {
    private val yaml = """-
  arch: aarch64
  flavor: alpine-minirootfs
  file: alpine-minirootfs-3.24.3-aarch64.tar.gz
  size: 5
  sha256: %SHA%
"""
    private val payload = "HELLO".toByteArray()
    private val goodSha = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private class FakeRoot(var uid: String = "0", var installedAfterTar: Boolean = true) : RootRunner {
        val scripts = mutableListOf<String>(); var installed = false
        override fun run(script: String, timeoutSec: Long): ShResult {
            scripts += script
            return when {
                script.contains("echo \"UID=") -> ShResult(0, "UID=$uid\nINSTALLED=${if (installed) 1 else 0}\nMOUNTED=${if (installed) 1 else 0}\n" + if (installed) "VER=3.24.3\n" else "")
                script.contains("STAGE_HILANG") -> { installed = installedAfterTar; ShResult(0, "OK\n") }
                script.contains("chroot \$R") -> ShResult(0, "hasil perintah\n")
                else -> ShResult(0, "")
            }
        }
    }

    private fun controller(root: FakeRoot, sha: String = goodSha, abis: List<String> = listOf("arm64-v8a"), onDownload: () -> Unit = {}): LinuxController {
        val dir = kotlin.io.path.createTempDirectory("lx").toFile()
        return LinuxController(CoroutineScope(SupervisorJob() + Dispatchers.Default), dir, File(dir, "courses"), File(dir, "library"), root,
            fetch = { yaml.replace("%SHA%", sha) }, download = { _, dest, _, onBytes -> onDownload(); dest.writeBytes(payload); onBytes(5) }, abis = { abis },
            extract = { _, stage -> stage.mkdirs(); listOf("bin" to 493, "bin/sh" to 493) })
    }

    private fun LinuxController.wait(j: kotlinx.coroutines.Job?) { if (j != null) runBlocking { j.join() } }

    @Test fun installHappyPathVerifiesChecksumBeforeRootExtract() {
        val root = FakeRoot(); val c = controller(root)
        c.wait(c.install())
        val s = c.state.value
        assertTrue(s.installed && s.rootOk == true && !s.busy); assertEquals("3.24.3", s.version)
        val tarIdx = root.scripts.indexOfFirst { it.contains("STAGE_HILANG") }
        assertTrue(tarIdx > 0)                                                            // setelah pemeriksaan root
        assertTrue(root.scripts.drop(tarIdx + 1).any { it.contains("mount -t proc") })     // lalu mount
        assertTrue(s.log.any { it.contains("Checksum cocok") })
    }

    @Test fun checksumMismatchNeverReachesRoot() {
        val root = FakeRoot(); val c = controller(root, sha = "0".repeat(64))
        c.wait(c.install())
        assertFalse(root.scripts.any { it.contains("STAGE_HILANG") })                          // tidak diekstrak
        assertFalse(c.state.value.installed)
        assertTrue(c.state.value.log.any { it.contains("Checksum SHA-256 tidak cocok") })
    }

    @Test fun notRootStopsEarlyWithoutDownload() {
        var downloaded = false
        val root = FakeRoot(uid = "2000"); val c = controller(root, onDownload = { downloaded = true })
        c.wait(c.install())
        assertFalse(downloaded); assertEquals(false, c.state.value.rootOk); assertFalse(root.scripts.any { it.contains("STAGE_HILANG") })
    }

    @Test fun unsupportedCpuIsRejected() {
        val root = FakeRoot(); val c = controller(root, abis = listOf("mips"))
        c.wait(c.install()); assertTrue(c.state.value.log.any { it.contains("tidak didukung") }); assertFalse(root.scripts.any { it.contains("STAGE_HILANG") })
    }

    @Test fun execRunsOnlyWhenInstalledAndLogsOutput() {
        val root = FakeRoot(); val c = controller(root)
        c.wait(c.refresh()); c.wait(c.exec("ls /mnt/lms"))
        assertTrue(c.state.value.log.any { it.contains("belum terpasang") }); assertFalse(root.scripts.any { it.contains("chroot \$R") })
        c.wait(c.install()); c.wait(c.exec("ls /mnt/lms"))
        assertTrue(c.state.value.log.contains("\$ ls /mnt/lms") && c.state.value.log.contains("hasil perintah"))
        assertNull(c.exec("   ")); assertNull(c.exec("x".repeat(5000)))
    }

    @Test fun onlyOneTaskAtATime() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val slow = object : RootRunner { override fun run(script: String, timeoutSec: Long): ShResult { gate.await(5, java.util.concurrent.TimeUnit.SECONDS); return ShResult(0, "UID=0\nINSTALLED=0\nMOUNTED=0\n") } }
        val dir = kotlin.io.path.createTempDirectory("lx").toFile()
        val c = LinuxController(CoroutineScope(SupervisorJob() + Dispatchers.Default), dir, dir, dir, slow, abis = { listOf("arm64-v8a") })
        val first = c.refresh(); assertNotNull(first)
        assertNull(c.refresh())                                                           // kedua ditolak selagi sibuk
        gate.countDown(); c.wait(first); assertFalse(c.state.value.busy)
    }

    @Test fun removeReportsRefusal() {
        val root = object : RootRunner { override fun run(script: String, timeoutSec: Long) = if (script.contains("rm -rf")) ShResult(3, "MASIH_ADA_MOUNT_DIBATALKAN\n") else ShResult(0, "UID=0\nINSTALLED=1\nMOUNTED=1\n") }
        val dir = kotlin.io.path.createTempDirectory("lx").toFile()
        val c = LinuxController(CoroutineScope(SupervisorJob() + Dispatchers.Default), dir, dir, dir, root, abis = { listOf("arm64-v8a") })
        c.wait(c.remove()); assertTrue(c.state.value.log.any { it.startsWith("Dibatalkan") })
    }
}

class ChrootInstallScriptTest {
    @Test fun installUsesMoveThenRootOwnershipWithoutHostTar() {
        val s = Chroot.installScript("/data/data/x/cache/alpine-stage", "/data/data/x/cache/alpine-modes.txt")
        assertFalse(s.contains("tar "))                                            // tar toybox tidak dipakai (menolak symlink absolut)
        assertTrue(s.contains("chown -hR 0:0 \$R"))                               // -h: symlink absolut tidak diikuti ke sistem Android asli
        assertTrue(s.indexOf("ROOT_SUDAH_ADA") < s.indexOf("mv "))                // mv tidak boleh memasukkan staging ke ROOT yang sudah ada
        assertTrue(s.contains("while read -r m p; do chmod \$m \"\$R/\$p\"; done < '/data/data/x/cache/alpine-modes.txt'"))
        assertTrue(s.contains("cp -dpR"))                                         // cadangan bila mv lintas filesystem gagal
    }
}
