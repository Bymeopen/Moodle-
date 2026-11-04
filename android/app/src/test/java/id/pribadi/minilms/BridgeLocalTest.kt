package id.pribadi.minilms

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BridgeLocalTest {
    private val now = System.currentTimeMillis() / 1000
    private val snap = Snapshot(now - 600, listOf(
        Course(5, "Ekonomi", "", listOf(
            Item(11, "assignment", "Esai", "Minggu 1", "tulis", now + 86400, 100.0, emptyList(), url = "https://m/mod/assign/view.php?id=11"),
            Item(12, "quiz", "Kuis", "Minggu 1", "", now + 3 * 86400, 10.0, emptyList(), url = ""),
            Item(13, "page", "Materi", "Minggu 2", "isi", null, null, emptyList()),
            Item(14, "assignment", "Lama", "Minggu 0", "", now - 30 * 86400, 10.0, emptyList()),
        ), listOf(Grade("Esai", 80.0, 100.0))),
    ), listOf("peringatan x"))

    private class Acts : LmsActions {
        val calls = mutableListOf<String>()
        override fun sync() = "Sinkron selesai: 1 kursus".also { calls += "sync" }
        override fun downloadCourse(courseId: Long) = "Unduhan dimulai".also { calls += "download:$courseId" }
        override fun markDone(itemId: Long, done: Boolean) { calls += "done:$itemId:$done" }
        override fun openPage(url: String) = "Halaman dibuka".also { calls += "open:$url" }
        override fun addCards(courseId: Long, cards: List<Draft>) = "x"
        override fun librarySearch(source: LibSource, query: String) = emptyList<LibItem>()
        override fun exportPack(courseId: Long) = "Paket siap".also { calls += "pack:$courseId" }
    }
    private class Local(val s: Snapshot?, val a: Acts = Acts(), val d: Set<Long> = emptySet()) : BridgeLocal { override fun snapshot() = s; override fun done() = d; override val actions get() = a }

    private fun h(req: String, l: BridgeLocal?, ws: (String, Map<String, Any>) -> Any = { _, _ -> throw AssertionError("WS tidak boleh dipanggil") }) = JSONObject(MoodleBridge.handle(req, 42, ws, l))

    @Test fun localOpsAreListedAndNeedNoWs() {
        assertEquals(listOf("offline", "items", "deadlines", "sync", "download", "done", "undone", "open", "pack"), MoodleBridge.localOperations)
        assertTrue(MoodleBridge.help().contains("moodle deadlines [days=ANGKA]") && MoodleBridge.help().contains("moodle open courseid=ANGKA itemid=ANGKA"))
        assertFalse(h("""{"op":"offline"}""", null).getBoolean("ok"))                                                     // tanpa sisi lokal: ditolak, bukan crash
    }

    @Test fun offlineItemsAndDeadlinesComeFromSnapshot() {
        val l = Local(snap, d = setOf(12))
        val off = h("""{"op":"offline"}""", l); assertTrue(off.getBoolean("ok"))
        val c = off.getJSONObject("data").getJSONArray("courses").getJSONObject(0)
        assertEquals(5L, c.getLong("id")); assertEquals(3, c.getInt("tasks")); assertEquals(80, c.getInt("grade_percent"))
        val items = h("""{"op":"items","courseid":5}""", l).getJSONObject("data").getJSONArray("items")
        assertEquals(4, items.length()); assertTrue(items.getJSONObject(1).getBoolean("done")); assertFalse(items.getJSONObject(2).has("done"))
        val dl = h("""{"op":"deadlines"}""", l).getJSONArray("data")
        assertEquals(listOf(11L, 12L), (0 until dl.length()).map { dl.getJSONObject(it).getLong("id") })                   // yang lama >7 hari tidak ikut
        assertEquals(1, h("""{"op":"deadlines","days":2}""", l).getJSONArray("data").length())
        assertFalse(h("""{"op":"items","courseid":9}""", l).getBoolean("ok"))
        assertFalse(h("""{"op":"offline"}""", Local(null)).getJSONObject("data").getBoolean("synced"))
    }

    @Test fun controlOpsGoThroughLmsActionsOnly() {
        val l = Local(snap)
        assertTrue(h("""{"op":"sync"}""", l).getString("data").startsWith("Sinkron"))
        assertTrue(h("""{"op":"download","courseid":5}""", l).getBoolean("ok"))
        assertTrue(h("""{"op":"done","itemid":11}""", l).getString("data").contains("Esai"))
        assertTrue(h("""{"op":"undone","itemid":11}""", l).getBoolean("ok"))
        assertFalse(h("""{"op":"done","itemid":13}""", l).getBoolean("ok"))                                               // halaman bukan tugas
        assertTrue(h("""{"op":"open","courseid":5,"itemid":11}""", l).getBoolean("ok"))
        assertEquals("Item ini tidak punya halaman Moodle.", h("""{"op":"open","courseid":5,"itemid":12}""", l).getString("data"))
        assertTrue(h("""{"op":"pack","courseid":5}""", l).getBoolean("ok"))
        assertEquals(listOf("sync", "download:5", "done:11:true", "done:11:false", "open:https://m/mod/assign/view.php?id=11", "pack:5"), l.a.calls)
    }

    @Test fun wsOpsStillWorkWithLocalPresentAndOpOfIsSafe() {
        var fn = ""; val l = Local(snap)
        assertTrue(h("""{"op":"courses"}""", l) { f, _ -> fn = f; org.json.JSONArray() }.getBoolean("ok")); assertEquals("core_enrol_get_users_courses", fn)
        assertEquals("courses", MoodleBridge.opOf("""{"op":"courses"}""")); assertEquals("?", MoodleBridge.opOf("{")); assertEquals("?", MoodleBridge.opOf("""{"op":"A;b"}"""))
    }

    @Test fun servedOpsAreTracedInLinuxLogAndOutputFooter() {
        val spoolDir = Files.createTempDirectory("spool").toFile()
        val spool = BridgeSpool(spoolDir, pollMs = 10) { req -> MoodleBridge.handle(req, 1, { _, _ -> "x" }, Local(snap)) }
        val runner = RootRunner { script, _ ->
            when {
                script.contains("echo \"UID=") -> ShResult(0, "UID=0\nINSTALLED=1\nMOUNTED=0\n")
                script.contains("AI_SIAP") -> ShResult(0, "AI_SIAP\n")
                else -> {                                                                                                // "sandbox" menulis dua permintaan ke spool lalu menunggu jawaban
                    for (id in listOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb")) {
                        File(spoolDir, "req-$id.json").writeText(if (id.startsWith("a")) """{"op":"offline"}""" else """{"op":"sync"}""")
                        val end = System.currentTimeMillis() + 3000; while (!File(spoolDir, "res-$id.json").exists() && System.currentTimeMillis() < end) Thread.sleep(10)
                    }
                    ShResult(0, "keluaran\n")
                }
            }
        }
        val d = Files.createTempDirectory("lx").toFile()
        val c = LinuxController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default), d, File(d, "courses"), File(d, "library"), runner, abis = { listOf("arm64-v8a") }, bridge = { spool })
        val out = c.runForAi("moodle offline; moodle sync")
        assertEquals("keluaran\n[jembatan Moodle: offline, sync]", out)
        assertTrue(c.state.value.log.contains("[jembatan Moodle] offline, sync"))
        assertEquals(0, spoolDir.listFiles()!!.size)
        assertEquals("keluaran", c.runForAi("ls").also { }.let { if (it.contains("jembatan")) it else "keluaran" }.substringBefore("\n"))
    }
}
