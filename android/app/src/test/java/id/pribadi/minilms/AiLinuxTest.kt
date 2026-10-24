package id.pribadi.minilms

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AiSandboxScriptTest {
    @Test fun aiCommandNeverRunsAsRoot() {
        val s = Chroot.aiExecScript("ls /home/ai/work")
        val runLine = s.lines().first { it.startsWith("chroot ") && it.contains("/bin/su") }
        assertTrue(runLine.contains("/bin/su -s /bin/sh -c ") && runLine.trim().endsWith(" ai 2>&1"))        // diturunkan ke pengguna `ai`
        assertFalse(s.lines().any { it.startsWith("chroot ") && it.contains("/bin/sh -c") && !it.contains("/bin/su") })   // tidak ada chroot ... sh -c langsung (itu root)
        assertTrue(s.contains("unshare -n"))                                                                  // jaringan diputus bila kernel mendukung
        assertFalse(s.contains("/sdcard") || s.contains("mnt/lms") || s.contains("--bind /dev \$R/dev"))      // tanpa penyimpanan ponsel, tanpa data aplikasi, tanpa /dev penuh
        assertTrue(s.startsWith("R=${Chroot.ROOT}\n[ \"\$R\" = \"${Chroot.ROOT}\" ] || exit 9"))
    }

    @Test fun nestedQuotingRunsExactlyTheGivenCommandThroughRealShell() {
        assumeTrue(File("/bin/sh").exists() && File("/usr/bin/timeout").exists())
        val work = java.nio.file.Files.createTempDirectory("aiwork").toFile()
        listOf("echo halo dunia", "echo 'a b' \"c d\"", "printf %s \"\$HOME\"", "echo a; echo b", "echo \$(echo dalam)", "echo it's").forEach { cmd ->
            val inner = Chroot.aiInner(cmd).replace(Chroot.AI_WORK, work.absolutePath)
            val expected = ProcessBuilder("/bin/sh", "-c", cmd).redirectErrorStream(true).start().let { p -> p.inputStream.readBytes().decodeToString().also { p.waitFor() } }
            // lapis kedua: seperti `su -c <inner>`: inner di-quote lagi lalu dieksekusi shell
            val viaSu = ProcessBuilder("/bin/sh", "-c", "/bin/sh -c " + Chroot.shQuote(inner)).redirectErrorStream(true).start().let { p -> p.inputStream.readBytes().decodeToString().also { p.waitFor() } }
            assertEquals("untuk: $cmd", expected, viaSu)
        }
    }

    @Test fun aiInnerEnforcesTimeoutAndClampsIt() {
        assertTrue(Chroot.aiInner("sleep 1").contains("timeout -s KILL 50 "))
        assertTrue(Chroot.aiInner("x", 9999).contains("timeout -s KILL 120 ")); assertTrue(Chroot.aiInner("x", 0).contains("timeout -s KILL 1 "))
        assertTrue(Chroot.aiInner("x").startsWith("cd ${Chroot.AI_WORK} && exec "))
    }

    @Test fun setupCreatesUnprivilegedUserAndCopiesMaterialsReadOnlyOwnedByRoot() {
        val s = Chroot.aiSetupScript("/data/data/x/files/courses", true)
        assertTrue(s.contains("ai:x:${Chroot.AI_UID}:${Chroot.AI_UID}:"))
        assertTrue(Chroot.AI_UID >= 5000)                                                                      // di luar uid root/system
        assertTrue(s.contains("chown -hR 0:0 \$R/home/ai/lms") && s.contains("chmod -R a+rX,go-w \$R/home/ai/lms"))   // salinan milik root, ai tidak bisa mengubah
        assertTrue(s.contains("cp -R '/data/data/x/files/courses'/.") && s.contains("MATERI_TERLALU_BESAR"))
        assertFalse(Chroot.aiSetupScript("/c", false).contains("cp -R"))
        assertTrue(s.indexOf("rm -rf \$R/home/ai/lms") > s.indexOf("[ \"\$R\" = "))                              // rm hanya di bawah ROOT yang diperiksa
    }

    @Test fun devNodesAreUnmountedBeforeFullDevBindAndOnRemove() {
        val m = Chroot.mountScript("/c", "/l")
        assertTrue(m.indexOf("umount \$R/dev/\$n") < m.indexOf("mount --bind /dev \$R/dev"))
        assertTrue(Chroot.removeScript().contains("dev dev/null dev/zero dev/urandom dev/random sys proc"))
    }
}

class CommandGateTest {
    @Test fun offDeniesAutoAllowsWithoutBlocking() {
        val g = CommandGate()
        assertFalse(g.request("ls", LinuxAiMode.OFF)); assertTrue(g.request("ls", LinuxAiMode.AUTO)); assertNull(g.pending.value)
    }

    @Test fun askBlocksUntilUserAnswers() {
        val g = CommandGate(); var result: Boolean? = null; val started = CountDownLatch(1)
        val t = thread { started.countDown(); result = g.request("python3 -V", LinuxAiMode.ASK, 5000) }
        started.await(); while (g.pending.value == null) Thread.sleep(5)
        assertEquals("python3 -V", g.pending.value); assertNull(result)
        g.answer(true); t.join(3000)
        assertEquals(true, result); assertNull(g.pending.value)
    }

    @Test fun denyCancelAndTimeoutAreAllRefusals() {
        val g = CommandGate()
        fun ask(after: (CommandGate) -> Unit, timeout: Long = 5000): Boolean? {
            var r: Boolean? = null; val t = thread { r = g.request("x", LinuxAiMode.ASK, timeout) }
            while (g.pending.value == null && t.isAlive) Thread.sleep(2); after(g); t.join(4000); return r
        }
        assertEquals(false, ask({ it.answer(false) })); assertEquals(false, ask({ it.cancel() })); assertEquals(false, ask({}, timeout = 60))
        assertNull(g.pending.value)
    }

    @Test fun newRequestRefusesTheOlderPendingOne() {
        val g = CommandGate(); var first: Boolean? = null
        val t1 = thread { first = g.request("pertama", LinuxAiMode.ASK, 5000) }
        while (g.pending.value != "pertama") Thread.sleep(2)
        val t2 = thread { g.request("kedua", LinuxAiMode.ASK, 5000) }
        t1.join(3000); assertEquals(false, first)                       // perintah lama tidak boleh ikut disetujui oleh jawaban untuk yang baru
        while (g.pending.value != "kedua") Thread.sleep(2)
        g.answer(false); t2.join(3000)
    }
}

class AiLinuxToolTest {
    private class NoActs : LmsActions {
        override fun sync() = ""; override fun downloadCourse(courseId: Long) = ""; override fun markDone(itemId: Long, done: Boolean) {}
        override fun openPage(url: String) = ""; override fun addCards(courseId: Long, cards: List<Draft>) = ""; override fun exportPack(courseId: Long) = ""
        override fun librarySearch(source: LibSource, query: String) = emptyList<LibItem>()
    }
    private class Lx(var on: Boolean = true) : LinuxAccess { val ran = mutableListOf<String>(); override fun ready() = on; override fun run(command: String) = "keluaran:$command".also { ran += command } }
    private fun tools(l: LinuxAccess?) = LmsTools({ Snapshot(1, emptyList(), emptyList()) }, { emptySet() }, { _, _, _ -> null }, { _, _, _ -> false }, NoActs(), { 1 }, l)

    @Test fun toolOnlyVisibleWhenAccessReady() {
        assertFalse(tools(null).specs.any { it.name == "linux_exec" })
        val l = Lx(on = false); val t = tools(l)
        assertFalse(t.specs.any { it.name == "linux_exec" }); l.on = true
        assertTrue(t.specs.any { it.name == "linux_exec" })                                               // muncul dinamis saat diaktifkan
        assertTrue(t.execute("linux_exec", JSONObject("""{"command":"ls"}""")).contains("keluaran:ls"))
    }

    @Test fun refusedWhenDisabledOrEmptyAndNeverReachesBackend() {
        val l = Lx(on = false); val t = tools(l)
        assertTrue(t.execute("linux_exec", JSONObject("""{"command":"ls"}""")).startsWith("Galat"))
        assertTrue(tools(null).execute("linux_exec", JSONObject("""{"command":"ls"}""")).startsWith("Galat"))
        l.on = true; assertTrue(t.execute("linux_exec", JSONObject("""{"command":"  "}""")).startsWith("Galat")); assertTrue(l.ran.isEmpty())
    }

    @Test fun describeShowsCommand() {
        assertEquals("Menjalankan di Linux: ls -la", tools(Lx()).describe(ToolCall("1", "linux_exec", JSONObject("""{"command":"ls -la"}"""))))
    }

    /** Pagar kode: jalur AI tidak boleh memanggil eksekusi root (shell root, atau chroot sebagai root). */
    @Test fun aiCodePathsNeverReferenceRootExec() {
        val dir = File("src/main/java/id/pribadi/minilms"); assumeTrue(dir.exists())
        for (name in listOf("Agent.kt", "AiController.kt")) {
            val src = File(dir, name).readText()
            assertFalse("$name memanggil execScript (root)", src.contains("execScript"))
            assertFalse("$name memanggil linux.exec (root)", Regex("linux\\.exec\\(").containsMatchIn(src))
            assertFalse("$name memakai SuRunner langsung", src.contains("SuRunner"))
        }
        val vm = File(dir, "MainViewModel.kt").readText()
        assertTrue(vm.contains("override fun exec(cmd: String) = linux.runForAi(cmd)"))                    // satu-satunya jembatan: runForAi (pengguna terbatas)
    }
}

class RunForAiTest {
    private class Rec(val installed: Boolean = true, val uid: String = "0", val outFor: (String) -> ShResult = { ShResult(0, "ok\n") }) : RootRunner {
        val scripts = mutableListOf<String>()
        override fun run(script: String, timeoutSec: Long): ShResult { scripts += script
            return when {
                script.contains("echo \"UID=") -> ShResult(0, "UID=$uid\nINSTALLED=${if (installed) 1 else 0}\nMOUNTED=0\n")
                script.contains("AI_SIAP") -> ShResult(0, "MATERI_DISALIN\nAI_SIAP\n")
                else -> outFor(script) } }
    }
    private fun ctl(r: RootRunner): LinuxController { val d = kotlin.io.path.createTempDirectory("ai").toFile(); return LinuxController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default), d, File(d, "courses"), File(d, "library"), r, abis = { listOf("arm64-v8a") }) }

    @Test fun runsAsUnprivilegedUserAfterOneTimeSetupAndLogsIt() {
        val r = Rec(); val c = ctl(r)
        assertEquals("ok", c.runForAi("echo hi")); c.runForAi("echo lagi")
        assertEquals(1, r.scripts.count { it.contains("AI_SIAP") })                                         // setup sekali dalam 30 menit
        assertEquals(2, r.scripts.count { it.contains("/bin/su -s /bin/sh -c") })
        assertTrue(r.scripts.none { it.contains("execScript") || (it.contains("chroot \$R /usr/bin/env") && !it.contains("/bin/su")) })    // tidak pernah chroot langsung sebagai root
        assertTrue(c.state.value.log.contains("[AI] \$ echo hi")); assertFalse(c.state.value.busy)
    }

    @Test fun refusesWithoutRootOrLinuxAndOnBadInput() {
        assertTrue(ctl(Rec(uid = "2000")).runForAi("ls").contains("belum terpasang atau root"))
        assertTrue(ctl(Rec(installed = false)).runForAi("ls").contains("belum terpasang atau root"))
        val r = Rec(); val c = ctl(r)
        assertTrue(c.runForAi("   ").contains("kosong")); assertTrue(c.runForAi("x".repeat(2001)).contains("2000"))
        assertTrue(r.scripts.none { it.contains("su -s") })
    }

    @Test fun outputIsCappedAndExitCodeReported() {
        val c = ctl(Rec(outFor = { ShResult(3, "y".repeat(50_000)) }))
        val out = c.runForAi("cat besar"); assertTrue(out.length < 6100 && out.endsWith("(kode keluar 3)"))
    }

    @Test fun busyControllerRejectsSecondCommand() {
        val gate = CountDownLatch(1)
        val slow = Rec(outFor = { gate.await(5, TimeUnit.SECONDS); ShResult(0, "selesai") })
        val c = ctl(slow); var first = ""
        val t = thread { first = c.runForAi("lambat") }
        while (!c.state.value.busy) Thread.sleep(2)
        assertTrue(c.runForAi("kedua").contains("sedang dipakai")); gate.countDown(); t.join(4000); assertEquals("selesai", first)
    }
}
