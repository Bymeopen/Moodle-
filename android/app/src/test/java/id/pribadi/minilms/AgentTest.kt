package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTest {
    private val now = 1_800_000_000L
    private val quiz = Item(6, "quiz", "Kuis 1", "M1", "info", now + 7200, 100.0, emptyList(), "https://lms/q", listOf(QuizAttempt(1, "finished", 80.0, 100.0, now - 100, 9, "Soal 1\n2+2=4")))
    private val tugas = Item(5, "assignment", "Esai", "M1", "Tulis esai", now + 86400, 100.0, emptyList(), "https://lms/a")
    private val lama = Item(4, "assignment", "Lama", "M1", "", now + 40 * 86400L, 100.0, emptyList())
    private val forum = Item(7, "forum", "Forum", "M1", "• Diskusi tentang inflasi", null, null, emptyList(), "https://lms/f")
    private val slide = Item(8, "link", "Slide", "M2", "", null, null, listOf(FileRef("a.docx", "/", "https://lms/a.docx", 100, null, 1), FileRef("b.pdf", "/", "https://lms/b.pdf", 100, null, 1)))
    private val snap = Snapshot(now, listOf(Course(11, "Ekonomi", "Dasar ekonomi", listOf(quiz, tugas, lama, forum, slide), listOf(Grade("Kuis 1", 80.0, 100.0), Grade("Total kursus", 80.0, 100.0)))), emptyList())

    private class Acts : LmsActions {
        val log = mutableListOf<String>()
        override fun sync() = "Sinkron selesai".also { log += "sync" }
        override fun downloadCourse(courseId: Long) = "Unduhan dimulai".also { log += "dl:$courseId" }
        override fun markDone(itemId: Long, done: Boolean) { log += "done:$itemId:$done" }
        override fun openPage(url: String) = "dibuka".also { log += "open:$url" }
        override fun addCards(courseId: Long, cards: List<Draft>) = "OK ${cards.size}".also { log += "cards:${cards.size}" }
        override fun exportPack(courseId: Long) = "Paket siap".also { log += "pack:$courseId" }
        override fun librarySearch(source: LibSource, query: String) = listOf(LibItem(source, "x", "Jurnal $query", "A", "2020", "", "https://j.id/1")).also { log += "lib:${source.name}" }
    }

    private fun tools(acts: Acts = Acts(), done: Set<Long> = emptySet()) = LmsTools({ snap }, { done }, { _, _, f -> if (f.name.endsWith(".docx")) "isi docx" else null }, { _, _, f -> f.name.endsWith(".docx") }, acts) { now }
    private fun call(t: LmsTools, name: String, json: String = "{}") = t.execute(name, JSONObject(json))

    // ---------- alat ----------
    @Test fun deadlinesSortedFilteredAndDoneHidden() {
        val a = JSONArray(call(tools(), "list_deadlines"))
        assertEquals(listOf("Kuis 1", "Esai"), (0 until a.length()).map { a.getJSONObject(it).getString("title") })   // "Lama" (40 hari) di luar 14 hari
        assertEquals(1, a.getJSONObject(0).getInt("finished_attempts"))
        val b = JSONArray(call(tools(done = setOf(5)), "list_deadlines"))
        assertEquals(listOf("Kuis 1"), (0 until b.length()).map { b.getJSONObject(it).getString("title") })
        val c = JSONArray(call(tools(done = setOf(5)), "list_deadlines", """{"include_done":true,"days":60}"""))
        assertEquals(3, c.length())
    }

    @Test fun readItemShowsAttemptsReviewAndFiles() {
        val q = JSONObject(call(tools(), "read_item", """{"course_id":11,"item_id":6}"""))
        assertEquals("Soal 1\n2+2=4", q.getJSONArray("attempts").getJSONObject(0).getString("review"))
        val s = JSONObject(call(tools(), "read_item", """{"course_id":11,"item_id":8}"""))
        assertEquals(2, s.getJSONArray("files").length()); assertTrue(s.getJSONArray("files").getJSONObject(0).getBoolean("downloaded"))
        assertEquals("isi docx", call(tools(), "read_file", """{"course_id":11,"item_id":8,"file_index":0}"""))
        assertTrue(call(tools(), "read_file", """{"course_id":11,"item_id":8,"file_index":1}""").contains("belum diunduh"))
        assertTrue(call(tools(), "read_file", """{"course_id":11,"item_id":8,"file_index":9}""").startsWith("Galat"))
    }

    @Test fun badArgumentsBecomeErrorTextNotCrash() {
        val t = tools()
        assertTrue(call(t, "get_course").contains("course_id"))                                   // parameter wajib
        assertTrue(call(t, "get_course", """{"course_id":"abc"}""").startsWith("Galat"))
        assertTrue(call(t, "get_course", """{"course_id":999}""").contains("tidak ditemukan"))
        assertTrue(call(t, "hapus_semua").contains("tidak dikenal"))
        assertTrue(call(t, "search_materials", """{"query":"a"}""").startsWith("Galat"))
        assertTrue(call(t, "library_search", """{"source":"x","query":"y"}""").startsWith("Galat"))
    }

    @Test fun controlToolsCallActionsAndValidate() {
        val acts = Acts(); val t = tools(acts)
        call(t, "sync_now"); call(t, "download_course", """{"course_id":11}""")
        assertTrue(call(t, "mark_task_done", """{"item_id":5,"done":true}""").startsWith("OK"))
        assertTrue(call(t, "mark_task_done", """{"item_id":7,"done":true}""").startsWith("Galat"))      // forum bukan tugas
        assertTrue(call(t, "mark_task_done", """{"item_id":999,"done":true}""").startsWith("Galat"))
        call(t, "open_page", """{"course_id":11,"item_id":6}""")
        assertTrue(call(t, "open_page", """{"course_id":11,"item_id":8}""").contains("tidak punya"))     // tanpa URL
        assertTrue(call(t, "create_flashcards", """{"course_id":11,"cards":[{"front":"A","back":"B"},{"front":"","back":"x"},{"front":"C"}]}""").startsWith("OK 1"))
        assertTrue(call(t, "create_flashcards", """{"course_id":11,"cards":[]}""").startsWith("Galat"))
        call(t, "library_search", """{"source":"campus","query":"manajemen"}""")
        assertEquals("Paket siap", call(t, "export_study_pack", """{"course_id":11}"""))
        assertTrue(call(t, "export_study_pack", """{"course_id":404}""").startsWith("Galat"))
        assertEquals(listOf("sync", "dl:11", "done:5:true", "open:https://lms/q", "cards:1", "lib:CAMPUS", "pack:11"), acts.log)
    }

    @Test fun noToolSubmitsWorkOrTakesQuizzes() {
        val names = tools().specs.map { it.name }
        assertTrue(names.none { it.contains("submit") || it.contains("attempt") || it.contains("post") || it.contains("answer") })
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun resultsAreCapped() {
        val big = Item(1, "page", "Besar", "", "x".repeat(50_000), null, null, emptyList())
        val t = LmsTools({ Snapshot(1, listOf(Course(1, "C", "", List(300) { big.copy(id = it.toLong()) }, emptyList())), emptyList()) }, { emptySet() }, { _, _, _ -> null }, { _, _, _ -> false }, Acts())
        assertTrue(t.execute("get_course", JSONObject("""{"course_id":1}""")).length <= LmsTools.MAX_RESULT)
        assertTrue(t.execute("search_materials", JSONObject("""{"query":"xx"}""")).length <= LmsTools.MAX_RESULT)
    }

    @Test fun noDataAsksForSync() {
        val t = LmsTools({ null }, { emptySet() }, { _, _, _ -> null }, { _, _, _ -> false }, Acts())
        assertTrue(t.execute("list_courses", JSONObject()).contains("sync_now"))
    }

    // ---------- loop ----------
    private class Scripted(val replies: (List<AgentMsg>) -> AgentReply) : AiBackend {
        var steps = 0
        override fun stream(system: String, messages: List<ChatMsg>, shouldStop: () -> Boolean, cache: Boolean, onUsage: (Usage) -> Unit, onText: (String) -> Unit) = ""
        override fun json(system: String, user: String, schema: JSONObject, docs: List<Doc>) = JSONObject()
        override fun step(system: String, msgs: List<AgentMsg>, tools: List<ToolSpec>): AgentReply { steps++; return replies(msgs) }
    }

    @Test fun runnerExecutesToolsThenAnswersWithTrace() {
        val acts = Acts(); val trace = mutableListOf<String>()
        val b = Scripted { m -> if (m.none { it is AgentMsg.Results }) AgentReply("", listOf(ToolCall("1", "sync_now", JSONObject()), ToolCall("2", "list_deadlines", JSONObject())), null, Usage(input = 10))
            else AgentReply("Selesai: ${(m.last() as AgentMsg.Results).results.size} hasil", emptyList(), null, Usage(output = 5)) }
        val r = AgentRunner(b, tools(acts)).run("sys", listOf(AgentMsg.User("tolong")), onTrace = { trace += it })
        assertEquals("Selesai: 2 hasil", r.text); assertEquals(2, r.calls); assertEquals(listOf("Sinkron dari Moodle", "Memeriksa tenggat"), trace)
        assertEquals(15L, r.usage.input + r.usage.output); assertEquals(listOf("sync"), acts.log)
    }

    @Test fun runnerStopsLoopsAndCapsCalls() {
        val b = Scripted { m -> if (m.last().let { it is AgentMsg.User && it.text.contains("Batas langkah") }) AgentReply("jawaban dari data", emptyList(), null)
            else AgentReply("", List(5) { ToolCall("c$it", "sync_now", JSONObject()) }, null) }
        val acts = Acts()
        val r = AgentRunner(b, tools(acts), maxSteps = 3, maxCalls = 4).run("sys", listOf(AgentMsg.User("x")))
        assertEquals("jawaban dari data", r.text); assertEquals(4, r.calls); assertEquals(4, acts.log.size)
        assertEquals(4, b.steps)                                                                  // 3 putaran + 1 jawaban paksa
    }

    @Test fun runnerHonoursStop() {
        var stop = false
        val b = Scripted { stop = true; AgentReply("", listOf(ToolCall("1", "sync_now", JSONObject())), null) }
        val acts = Acts()
        val r = AgentRunner(b, tools(acts)).run("sys", listOf(AgentMsg.User("x")), shouldStop = { stop })
        assertEquals("", r.text); assertTrue(acts.log.isEmpty())                                  // dihentikan sebelum alat berjalan
    }

    // ---------- klien ----------
    private val gemFirst = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"list_deadlines","args":{"days":7}},"thoughtSignature":"SIG123"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":10}}"""
    private val gemFinal = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Ada 2 tenggat."}]},"finishReason":"STOP"}]}"""

    @Test fun geminiAgentReplaysThoughtSignatureAndSendsToolResults() {
        FakeHttp(200 to gemFirst, 200 to gemFinal).use { srv ->
            val r = AgentRunner(GeminiClient("KEY", "m", srv.url), tools()).run("sys", listOf(AgentMsg.User("tenggat?")))
            assertEquals("Ada 2 tenggat.", r.text)
            val first = srv.seen[0]; val second = srv.seen[1]
            assertEquals("/v1beta/models/m:generateContent", first.path); assertEquals("KEY", first.headers["x-goog-api-key"])
            val decl = first.body.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
            assertTrue((0 until decl.length()).any { decl.getJSONObject(it).getString("name") == "list_deadlines" })
            assertEquals("OBJECT", decl.getJSONObject(1).optJSONObject("parameters")?.getString("type") ?: "OBJECT")
            val contents = second.body.getJSONArray("contents")
            assertEquals("SIG123", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))     // diputar ulang apa adanya
            val resp = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
            assertEquals("list_deadlines", resp.getString("name")); assertTrue(resp.getJSONObject("response").getString("result").contains("Kuis 1"))
            assertFalse(srv.seen[0].body.toString().contains("KEY"))
        }
    }

    @Test fun claudeAgentUsesToolUseAndToolResult() {
        val first = """{"content":[{"type":"text","text":"Saya cek."},{"type":"tool_use","id":"tu_1","name":"get_grades","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":50,"output_tokens":8}}"""
        val final = """{"content":[{"type":"text","text":"Nilai Anda 80%."}],"stop_reason":"end_turn","usage":{"input_tokens":70,"output_tokens":6}}"""
        FakeHttp(200 to first, 200 to final).use { srv ->
            val r = AgentRunner(ClaudeClient("sk-test", "claude-sonnet-5-5", srv.url), tools()).run("sys", listOf(AgentMsg.User("nilai?")))
            assertEquals("Nilai Anda 80%.", r.text); assertEquals(134L, r.usage.input + r.usage.output)
            val q1 = srv.seen[0]; val q2 = srv.seen[1]
            assertEquals("sk-test", q1.headers["x-api-key"]); assertFalse(q1.body.has("fallbacks")); assertFalse(q1.body.has("temperature")); assertFalse(q1.body.has("stream"))
            assertTrue(q1.body.getJSONArray("tools").length() >= 10)
            val msgs = q2.body.getJSONArray("messages")
            assertEquals("tool_use", msgs.getJSONObject(1).getJSONArray("content").getJSONObject(1).getString("type"))
            val tr = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0)
            assertEquals("tool_result", tr.getString("type")); assertEquals("tu_1", tr.getString("tool_use_id")); assertTrue(tr.getString("content").contains("Ekonomi"))
        }
    }

    @Test fun agentErrorsAreFriendly() {
        FakeHttp(429 to """{"error":{"status":"RESOURCE_EXHAUSTED","message":"quota"}}""").use { srv ->
            val e = assertThrows(ApiError::class.java) { GeminiClient("K", "m", srv.url).step("s", listOf(AgentMsg.User("x")), emptyList()) }
            assertTrue(e.message!!.contains("Batas"))
        }
        assertThrows(ApiError::class.java) { GeminiClient.parseStep(JSONObject("""{"promptFeedback":{"blockReason":"SAFETY"}}""")) }
        assertThrows(ApiError::class.java) { ClaudeClient.parseStep(JSONObject("""{"content":[],"stop_reason":"refusal"}""")) }
    }
}
