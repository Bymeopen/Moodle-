package id.pribadi.minilms

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.time.LocalDate

class ClaudeTest {
    private var server: ServerSocket? = null
    private val seen = mutableListOf<Triple<Map<String, String>, JSONObject, Int>>()

    /** Server HTTP/1.1 minimal: balasan berurutan per permintaan (yang terakhir diulang). */
    private fun serve(vararg replies: Pair<Int, String>): String {
        val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); server = ss
        var n = 0
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: Exception) { break }
                sock.use {
                    val inp = it.getInputStream().buffered()
                    fun line(): String { val sb = StringBuilder(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }; return sb.toString() }
                    line() // request line
                    val headers = mutableMapOf<String, String>()
                    while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]!!.toInt()).also { buf -> var off = 0; while (off < buf.size) off += inp.read(buf, off, buf.size - off) }
                    seen += Triple(headers, JSONObject(body.decodeToString()), n)
                    val (code, text) = replies[minOf(n++, replies.size - 1)]
                    val bytes = text.toByteArray()
                    it.getOutputStream().apply { write("HTTP/1.1 $code X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
                }
            }
        }
        return "http://127.0.0.1:${ss.localPort}"
    }
    @After fun stop() { server?.close() }

    private val course = Course(1, "Algoritma", "Dasar", listOf(
        Item(2, "page", "Intro", "Minggu 1", "Isi halaman", null, null, emptyList()),
        Item(3, "link", "Slide", "Minggu 1", "https://x", null, null, listOf(FileRef("slide.pdf", "/", "u", 5, null, 1))),
    ), emptyList())

    @Test fun bodyShape() {
        val b = ClaudeClient.body("claude-opus-5-5", "sys", listOf(ChatMsg("user", "hai")), stream = true)
        assertEquals("claude-opus-5-5", b.getString("model")); assertTrue(b.getBoolean("stream")); assertEquals(64000, b.getInt("max_tokens"))
        assertEquals("default", b.getString("fallbacks"))
        // tidak ada parameter yang ditolak model baru
        for (k in listOf("temperature", "top_p", "top_k", "thinking")) assertFalse(b.has(k))
        assertFalse(ClaudeClient.body("claude-haiku-5-5", "s", emptyList(), true).has("fallbacks"))
        val j = ClaudeClient.body("claude-opus-5-5", "s", emptyList(), stream = false, schema = JSONObject("""{"type":"object"}"""))
        assertEquals("json_schema", j.getJSONObject("output_config").getJSONObject("format").getString("type")); assertFalse(j.has("stream"))
    }

    @Test fun sseParsing() {
        assertEquals("Halo", ClaudeClient.parseSse("""data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Halo"}}"""))
        assertNull(ClaudeClient.parseSse("""data: {"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":""}}"""))
        assertNull(ClaudeClient.parseSse("event: ping")); assertNull(ClaudeClient.parseSse("data: bukan json"))
        var stop = ""; ClaudeClient.parseSse("""data: {"type":"message_delta","delta":{"stop_reason":"refusal"}}""", stop = { stop = it }); assertEquals("refusal", stop)
        assertThrows(ApiError::class.java) { ClaudeClient.parseSse("""data: {"type":"error","error":{"message":"overloaded"}}""") }
    }

    private val sse = listOf("Ha", "lo").joinToString("\n\n") { """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$it"}}""" } + "\n\n"

    @Test fun streamsTextAndSendsHeaders() {
        val url = serve(200 to sse)
        val parts = mutableListOf<String>()
        val full = ClaudeClient("sk-test", "claude-opus-5-5", url).stream("sys", listOf(ChatMsg("user", "hai"))) { parts += it }
        assertEquals("Halo", full); assertEquals(listOf("Ha", "lo"), parts)
        val (h, b, _) = seen.single()
        assertEquals("sk-test", h["x-api-key"]); assertEquals("2023-06-01", h["anthropic-version"]); assertEquals(ClaudeClient.FALLBACK_BETA, h["anthropic-beta"])
        assertEquals("hai", b.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun retriesWithoutFallbackOn400() {
        val url = serve(400 to """{"type":"error","error":{"type":"invalid_request_error","message":"unknown beta: server-side-fallback"}}""", 200 to sse)
        assertEquals("Halo", ClaudeClient("k", "claude-opus-5-5", url).stream("s", listOf(ChatMsg("user", "x"))) { })
        assertEquals(2, seen.size); assertTrue(seen[0].second.has("fallbacks")); assertFalse(seen[1].second.has("fallbacks")); assertNull(seen[1].first["anthropic-beta"])
    }

    @Test fun otherErrorsAreFriendly() {
        val url = serve(401 to """{"error":{"message":"invalid x-api-key"}}""")
        val e = assertThrows(ApiError::class.java) { ClaudeClient("bad", "claude-opus-5-5", url).stream("s", listOf(ChatMsg("user", "x"))) { } }
        assertTrue(e.message!!.contains("Kunci API"))
    }

    @Test fun jsonOutputParsed() {
        val url = serve(200 to """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\"cards\":[{\"front\":\"A\",\"back\":\"B\"}]}"}]}""")
        val out = ClaudeClient("k", "claude-opus-5-5", url).json("s", "u", JSONObject("""{"type":"object"}"""))
        assertEquals("A", out.getJSONArray("cards").getJSONObject(0).getString("front"))
    }

    @Test fun jsonRefusalAndTruncation() {
        var url = serve(200 to """{"stop_reason":"refusal","content":[]}""")
        assertTrue(assertThrows(ApiError::class.java) { ClaudeClient("k", "claude-opus-5-5", url).json("s", "u", JSONObject()) }.message!!.contains("menolak"))
        server?.close()
        url = serve(200 to """{"stop_reason":"end_turn","content":[{"type":"text","text":"bukan json"}]}""")
        assertThrows(ApiError::class.java) { ClaudeClient("k", "claude-opus-5-5", url).json("s", "u", JSONObject()) }
    }

    @Test fun materialListsFilesButNotTheirContent() {
        val t = AiMaterial.build(course)
        assertTrue(t.contains("Isi halaman")); assertTrue(t.contains("slide.pdf")); assertTrue(t.contains("## Minggu 1"))
        assertFalse(t.contains("https://x"))   // URL tautan tidak ikut sebagai isi
    }

    @Test fun srsScheduling() {
        val d = LocalDate.of(2026, 1, 1)
        var c = Card("1", 1, "f", "b", 2.5, 0, 0, d.toString())
        c = Srs.review(c, "good", d); assertEquals(1, c.interval); assertEquals("2026-01-02", c.due)
        c = Srs.review(c, "good", d); assertEquals(6, c.interval)
        c = Srs.review(c, "good", d); assertEquals(15, c.interval)
        val l = Srs.review(c, "again", d); assertEquals(0, l.interval); assertEquals(0, l.reps); assertEquals(d.toString(), l.due); assertTrue(l.ease < c.ease && l.ease >= 1.3)
        assertEquals(c, JSONObject(c.toJson().toString()).toCard())
        assertThrows(IllegalArgumentException::class.java) { Srs.review(c, "x", d) }
    }
}
