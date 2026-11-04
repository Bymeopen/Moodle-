package id.pribadi.minilms

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MoodleBridgeTest {
    private class Ws(val reply: Any = org.json.JSONArray("[{\"id\":1}]")) { val calls = mutableListOf<Pair<String, Map<String, Any>>>(); fun call(fn: String, p: Map<String, Any>): Any { calls += fn to p; return reply } }
    private fun h(req: String, ws: Ws = Ws(), uid: Long = 42) = JSONObject(MoodleBridge.handle(req, uid, ws::call))

    @Test fun onlyWhitelistedReadOperationsExist() {
        assertTrue(MoodleBridge.functions.all { Regex("(core|mod|gradereport)_[a-z_]*_get_[a-z_]+").matches(it) })      // semua fungsi baca
        assertTrue(MoodleBridge.functions.none { Regex("(^|_)(submit|save|add|create|delete|update|start|finish|process|post)(_|\$)").containsMatchIn(it) })
        assertTrue(MoodleBridge.functions.none { it.contains("attempt") })                                               // tidak menyentuh pengerjaan tes
        assertEquals(listOf("site", "courses", "contents", "assignments", "quizzes", "grades", "forums", "discussions", "posts", "calendar"), MoodleBridge.operations)
    }

    @Test fun rejectsUnknownOpUnknownArgsAndNonIntegers() {
        val ws = Ws()
        assertFalse(h("""{"op":"core_enrol_get_users_courses"}""", ws).getBoolean("ok"))                                // nama fungsi WS tidak bisa dipanggil langsung
        assertFalse(h("""{"op":"courses","userid":7}""", ws).getBoolean("ok"))                                          // userid tidak boleh dari permintaan
        for (bad in listOf("\"5\"", "-1", "0", "1.5", "null", "[5]", "99999999999999")) assertFalse("courseid=$bad", h("""{"op":"contents","courseid":$bad}""", ws).getBoolean("ok"))
        assertFalse(h("""{"op":"contents"}""", ws).getBoolean("ok"))
        assertFalse(h("bukan json", ws).getBoolean("ok")); assertFalse(h("""{"op":"x"}""" + " ".repeat(MoodleBridge.MAX_REQUEST_BYTES), ws).getBoolean("ok"))
        assertTrue(ws.calls.isEmpty())                                                                                  // tidak satu pun sampai ke Moodle
    }

    @Test fun userIdAlwaysFromSessionAndParamsAreMapped() {
        val ws = Ws()
        assertTrue(h("""{"op":"courses"}""", ws, uid = 42).getBoolean("ok"))
        assertEquals("core_enrol_get_users_courses" to mapOf<String, Any>("userid" to 42L), ws.calls.last())
        h("""{"op":"grades","courseid":9}""", ws, uid = 42)
        assertEquals(mapOf<String, Any>("courseid" to 9L, "userid" to 42L), ws.calls.last().second)
        h("""{"op":"assignments","courseid":9}""", ws); assertEquals(mapOf<String, Any>("courseids" to listOf(9L)), ws.calls.last().second)
    }

    @Test fun secretsAreStrippedAtAnyDepth() {
        val ws = Ws(JSONObject("""{"fullname":"A","userprivateaccesskey":"RAHASIA1","a":{"token":"RAHASIA2","ok":1},"l":[{"privatetoken":"RAHASIA3","n":2}],"autologinkey":"RAHASIA4"}"""))
        val out = MoodleBridge.handle("""{"op":"site"}""", 1, ws::call)
        assertTrue(out.contains("\"fullname\"") && out.contains("\"n\":2") && out.contains("\"ok\":1"))
        assertFalse(out.contains("RAHASIA"))
    }

    @Test fun errorsAndHugeOutputAreContained() {
        val boom = MoodleBridge.handle("""{"op":"site"}""", 1) { _, _ -> throw ApiError("token wstoken=ABC salah") }
        assertFalse(JSONObject(boom).getBoolean("ok"))
        val crash = MoodleBridge.handle("""{"op":"site"}""", 1) { _, _ -> throw IllegalStateException("x") }
        assertFalse(JSONObject(crash).getBoolean("ok"))
        val big = JSONObject(MoodleBridge.handle("""{"op":"site"}""", 1) { _, _ -> JSONObject().put("t", "x".repeat(200_000)) })
        assertTrue(big.getBoolean("truncated")); assertTrue(big.toString().length <= MoodleBridge.MAX_OUT_CHARS + 100)
    }

    private fun spool(handler: (String) -> String = { """{"ok":true,"echo":$it}""" }) = BridgeSpool(Files.createTempDirectory("spool").toFile(), handler = handler)

    @Test fun spoolAnswersValidRequestsAndIgnoresOthers() {
        val s = spool(); s.prepare()
        File(s.dir, "req-0123456789abcdef.json").writeText("""{"op":"site"}""")
        File(s.dir, "req-zz.json").writeText("""{"op":"site"}"""); File(s.dir, "lain.txt").writeText("x"); File(s.dir, "req-0123456789abcdef.json.bak").writeText("x")
        assertEquals(1, s.pumpOnce())
        assertEquals("""{"ok":true,"echo":{"op":"site"}}""", File(s.dir, "res-0123456789abcdef.json").readText())
        assertFalse(File(s.dir, "req-0123456789abcdef.json").exists()); assertFalse(s.dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun symlinkedRequestIsNeverFollowed() {
        assumeTrue(File("/bin/sh").exists())
        val s = spool { it }; s.prepare(); val secret = File(Files.createTempDirectory("app").toFile(), "site.json"); secret.writeText("""{"op":"RAHASIA-TOKEN"}""")
        Files.createSymbolicLink(File(s.dir, "req-aaaaaaaaaaaaaaaa.json").toPath(), secret.toPath())
        assertEquals(0, s.pumpOnce()); assertFalse(File(s.dir, "res-aaaaaaaaaaaaaaaa.json").exists())                      // dibuang tanpa dibaca
        assertEquals("""{"op":"RAHASIA-TOKEN"}""", secret.readText())
    }

    @Test fun replyNeverWritesThroughPlantedSymlink() {
        assumeTrue(File("/bin/sh").exists())
        val s = spool(); s.prepare(); val victim = File(Files.createTempDirectory("app").toFile(), "prefs.xml"); victim.writeText("ASLI")
        Files.createSymbolicLink(File(s.dir, "res-bbbbbbbbbbbbbbbb.json").toPath(), victim.toPath())                        // sandbox menanam symlink di nama jawaban
        File(s.dir, "req-bbbbbbbbbbbbbbbb.json").writeText("""{"op":"site"}""")
        assertEquals(1, s.pumpOnce())
        assertEquals("ASLI", victim.readText())                                                                          // berkas korban utuh
        assertFalse(Files.isSymbolicLink(File(s.dir, "res-bbbbbbbbbbbbbbbb.json").toPath()))                               // symlink diganti berkas biasa
    }

    @Test fun oversizeRequestAndBudgetAreEnforced() {
        val seen = mutableListOf<String>(); val s = BridgeSpool(Files.createTempDirectory("spool").toFile(), handler = { seen += it; MoodleBridge.handle(it, 1) { _, _ -> "x" } }); s.prepare()
        File(s.dir, "req-cccccccccccccccc.json").writeText("""{"op":"site","pad":"${"a".repeat(5000)}"}""")
        assertEquals(1, s.pumpOnce()); assertTrue(File(s.dir, "res-cccccccccccccccc.json").readText().contains("terlalu besar"))
        for (i in 0 until 5) File(s.dir, "req-d${i}ddddddddddddddd.json").writeText("""{"op":"site"}""")
        assertEquals(3, s.pumpOnce(budget = 3))
    }

    @Test fun serveHandlesRequestsDuringBlockAndCleansUp() {
        val s = BridgeSpool(Files.createTempDirectory("spool").toFile(), pollMs = 10) { "{\"ok\":true}" }
        val got = s.serve {
            File(s.dir, "req-eeeeeeeeeeeeeeee.json").writeText("""{"op":"site"}""")
            val end = System.currentTimeMillis() + 3000; val res = File(s.dir, "res-eeeeeeeeeeeeeeee.json")
            while (!res.exists() && System.currentTimeMillis() < end) Thread.sleep(10)
            res.readText()
        }
        assertEquals("{\"ok\":true}", got); assertEquals(0, s.dir.listFiles()!!.size)                                   // spool dibersihkan setelah perintah selesai
    }

    @Test fun cliScriptRunsEndToEndAgainstSpoolAndRejectsBadInput() {
        assumeTrue(File("/bin/sh").exists() && File("/usr/bin/od").exists() || File("/bin/od").exists())
        val dir = Files.createTempDirectory("spool").toFile()
        val script = File(Files.createTempDirectory("bin").toFile(), "moodle").also { it.writeText(MoodleBridge.cliScript().replace("/home/ai/bridge", dir.absolutePath)) }
        fun run(vararg a: String) = ProcessBuilder(listOf("/bin/sh", script.absolutePath) + a).redirectErrorStream(true).start().let { p -> val o = p.inputStream.bufferedReader().readText(); p.waitFor() to o.trim() }
        for (bad in listOf(arrayOf("Courses"), arrayOf("a;rm"), arrayOf("contents", "courseid=5;id"), arrayOf("contents", "courseid="), arrayOf("contents", "Course=1"), arrayOf("contents", "\$(id)=1")))
            assertEquals(bad.joinToString(" "), 2, run(*bad).first)                                                      // ditolak di sisi sandbox sebelum menulis apa pun
        assertEquals(0, dir.listFiles()!!.size)
        val s = BridgeSpool(dir, pollMs = 10) { req -> """{"ok":true,"saw":$req}""" }
        val (code, out) = s.serve { run("contents", "courseid=5") }
        assertEquals(0, code); assertEquals("""{"ok":true,"saw":{"op":"contents","courseid":5}}""", out)
        assertEquals(0, run("help").first)
    }

    @Test fun aiExecMountsBridgeOnlyWhenGivenAndUnmountsAfterwards() {
        val on = Chroot.aiExecScript("ls", bridgeDir = "/data/data/x/files/bridge"); val off = Chroot.aiExecScript("ls")
        assertFalse(off.contains("bridge")); assertTrue(on.contains("mount --bind '/data/data/x/files/bridge' \$R/home/ai/bridge"))
        assertTrue(on.indexOf("umount \$R/home/ai/bridge") > on.indexOf("/bin/su -s /bin/sh -c")); assertTrue(on.contains("exit \$RC"))
        assertTrue(on.contains("unshare -n"))                                                                          // isolasi jaringan tidak berubah
        assertTrue(Chroot.unmountScript().indexOf("home/ai/bridge") in 0 until Chroot.unmountScript().indexOf("mnt/library"))   // dilepas lebih dulu, sebelum rm -rf bisa terjadi
        val setup = Chroot.aiSetupScript("/c", false); assertTrue(setup.contains("MOODLE_CLI_EOF") && setup.contains("chown 0:0 \$R/usr/local/bin/moodle"))
    }

    @Test fun agentCodeStillCannotReachRootAndBridgeHasNoToken() {
        val dir = File("src/main/java/id/pribadi/minilms"); assumeTrue(dir.exists())
        val bridge = File(dir, "MoodleBridge.kt").readText()
        assertFalse(Regex("wsfunction|\\.token|\\.privateToken|SecretStore|MoodleApi").containsMatchIn(bridge))      // jembatan tidak menyentuh kredensial
        assertTrue(File(dir, "MainViewModel.kt").readText().contains("MoodleApi.call(st.url, st.token, fn, p)"))           // token hanya dipegang penutup di sisi aplikasi
    }
}
