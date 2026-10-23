package id.pribadi.minilms

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GeminiTest {
    private var srv: FakeHttp? = null
    private fun serve(vararg r: Pair<Int, String>) = FakeHttp(*r).also { srv = it }
    @After fun stop() { srv?.close() }

    private fun chunk(text: String, usage: String = "", finish: String = "") =
        """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}${if (finish.isNotEmpty()) ""","finishReason":"$finish"""" else ""}}]$usage}"""
    private val usage = ""","usageMetadata":{"promptTokenCount":1000,"cachedContentTokenCount":400,"candidatesTokenCount":50,"thoughtsTokenCount":30}"""

    @Test fun bodyShape() {
        val schema = JSONObject("""{"type":"object","additionalProperties":false,"required":["cards"],"properties":{"cards":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["front"],"properties":{"front":{"type":"string"}}}}}}""")
        val b = GeminiClient.body("sys", listOf(ChatMsg("user", "hai", listOf(Doc("a.pdf", "QQ=="))), ChatMsg("assistant", "halo"), ChatMsg("user", "lagi")), schema)
        assertEquals("sys", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val c = b.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"), (0 until 3).map { c.getJSONObject(it).getString("role") })   // assistant -> model
        val p0 = c.getJSONObject(0).getJSONArray("parts")
        assertEquals("application/pdf", p0.getJSONObject(0).getJSONObject("inlineData").getString("mimeType")); assertEquals("QQ==", p0.getJSONObject(0).getJSONObject("inlineData").getString("data"))
        assertEquals("hai", p0.getJSONObject(1).getString("text"))
        val cfg = b.getJSONObject("generationConfig")
        assertEquals("application/json", cfg.getString("responseMimeType"))
        val rs = cfg.getJSONObject("responseSchema")
        assertEquals("OBJECT", rs.getString("type")); assertFalse(rs.has("additionalProperties"))                  // tipe huruf besar, kata kunci tak didukung dibuang
        assertEquals("ARRAY", rs.getJSONObject("properties").getJSONObject("cards").getString("type"))
        assertEquals("STRING", rs.getJSONObject("properties").getJSONObject("cards").getJSONObject("items").getJSONObject("properties").getJSONObject("front").getString("type"))
        assertFalse(GeminiClient.body("s", listOf(ChatMsg("user", "x"))).getJSONObject("generationConfig").has("responseSchema"))
    }

    @Test fun parseChunkTextUsageThoughtsAndBlocks() {
        val seen = mutableListOf<Usage>(); var fin = ""
        assertEquals("Halo", GeminiClient.parseChunk(chunk("Halo", usage), { seen += it }, { fin = it }))
        assertEquals(Usage(input = 600, cacheRead = 400, cacheWrite = 0, output = 80), seen.single())         // prompt dikurangi cache; output = kandidat + thinking
        assertNull(GeminiClient.parseChunk("""data: {"candidates":[{"content":{"parts":[{"text":"rahasia","thought":true}]}}]}"""))   // bagian thought diabaikan
        GeminiClient.parseChunk(chunk("x", finish = "MAX_TOKENS"), finish = { fin = it }); assertEquals("MAX_TOKENS", fin)
        assertNull(GeminiClient.parseChunk("event: ping")); assertNull(GeminiClient.parseChunk("data: bukan json"))
        assertTrue(assertThrows(ApiError::class.java) { GeminiClient.parseChunk("""data: {"promptFeedback":{"blockReason":"SAFETY"}}""") }.message!!.contains("SAFETY"))
    }

    @Test fun streamsWithKeyInHeaderNotUrl() {
        val s = serve(200 to (chunk("Ha") + "\n\n" + chunk("lo", usage) + "\n\n"))
        val parts = mutableListOf<String>(); var u = Usage()
        val full = GeminiClient("KUNCI-RAHASIA", "gemini-3.8-flash", s.url).stream("sys", listOf(ChatMsg("user", "hai")), onUsage = { u = it }) { parts += it }
        assertEquals("Halo", full); assertEquals(listOf("Ha", "lo"), parts); assertEquals(80, u.output)
        val r = s.seen.single()
        assertEquals("/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse", r.path)
        assertEquals("KUNCI-RAHASIA", r.headers["x-goog-api-key"]); assertFalse(r.path.contains("KUNCI"))
    }

    @Test fun friendlyErrors() {
        for ((code, body, expect) in listOf(
            Triple(429, """{"error":{"code":429,"message":"quota","status":"RESOURCE_EXHAUSTED"}}""", "Batas pemakaian"),
            Triple(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""", "Kunci API Gemini tidak valid"),
            Triple(404, """{"error":{"code":404,"message":"models/x not found","status":"NOT_FOUND"}}""", "Model tidak ditemukan"),
            Triple(403, """{"error":{"code":403,"message":"denied","status":"PERMISSION_DENIED"}}""", "izin"))) {
            serve(code to body).use { s ->
                val e = assertThrows(ApiError::class.java) { GeminiClient("k", "m", s.url).stream("s", listOf(ChatMsg("user", "x"))) { } }
                assertTrue("$code: ${e.message}", e.message!!.contains(expect))
            }
        }
    }

    @Test fun jsonStructuredAndFenceStripping() {
        val s = serve(200 to """{"candidates":[{"content":{"parts":[{"text":"pikir","thought":true},{"text":"```json\n{\"cards\":[{\"front\":\"A\"}]}\n```"}]},"finishReason":"STOP"}]}""")
        val out = GeminiClient("k", "m", s.url).json("s", "u", JSONObject("""{"type":"object"}"""))
        assertEquals("A", out.getJSONArray("cards").getJSONObject(0).getString("front"))
        assertEquals("/v1beta/models/m:generateContent", s.seen.single().path)
    }

    @Test fun jsonRetriesWithoutSchemaWhenRejected() {
        val s = serve(400 to """{"error":{"code":400,"message":"Invalid JSON payload: Unknown name \"foo\" at 'generation_config.response_schema'","status":"INVALID_ARGUMENT"}}""",
            200 to """{"candidates":[{"content":{"parts":[{"text":"{\"ok\":1}"}]},"finishReason":"STOP"}]}""")
        assertEquals(1, GeminiClient("k", "m", s.url).json("s", "u", JSONObject("""{"type":"object"}""")).getInt("ok"))
        assertEquals(2, s.seen.size); assertTrue(s.seen[0].body.getJSONObject("generationConfig").has("responseSchema")); assertFalse(s.seen[1].body.getJSONObject("generationConfig").has("responseSchema"))
        assertTrue(s.seen[1].body.getJSONObject("systemInstruction").toString().contains("JSON valid"))   // skema dipindah ke prompt
    }

    @Test fun jsonBlockedOrTruncated() {
        serve(200 to """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""").use { s -> assertThrows(ApiError::class.java) { GeminiClient("k", "m", s.url).json("s", "u", JSONObject()) } }
        serve(200 to """{"candidates":[{"content":{"parts":[{"text":"{"}]},"finishReason":"MAX_TOKENS"}]}""").use { s -> assertTrue(assertThrows(ApiError::class.java) { GeminiClient("k", "m", s.url).json("s", "u", JSONObject()) }.message!!.contains("terpotong")) }
    }

    @Test fun datedPricingAndFreeTierLabels() {
        val u = Usage(input = 1_000_000, output = 1_000_000)
        assertEquals(0.75 + 3.75, u.costUsd("gemini-3.8-flash", LocalDate.of(2026, 12, 31)), 1e-9)       // harga lama s.d. 31 Des 2026
        assertEquals(1.50 + 7.50, u.costUsd("gemini-3.8-flash", LocalDate.of(2027, 1, 1)), 1e-9)
        assertEquals(2.0 + 10.0, u.costUsd("gemini-4-argon", LocalDate.of(2026, 11, 30)), 1e-9); assertEquals(4.0 + 20.0, u.costUsd("gemini-4-argon", LocalDate.of(2026, 12, 1)), 1e-9)
        assertEquals(0.30 + 2.50, u.costUsd("gemini-3.5-flash-lite", LocalDate.of(2026, 10, 1)), 1e-9)
        assertTrue(Logic.freeTier("gemini-3.8-flash")); assertTrue(Logic.freeTier("gemini-3.5-flash-lite")); assertFalse(Logic.freeTier("gemini-4-argon")); assertFalse(Logic.freeTier("claude-opus-5-5"))
        assertTrue(Usage(1000, 0, 0, 100).label("gemini-3.8-flash").endsWith("jika berbayar")); assertFalse(Usage(1000, 0, 0, 100).label("claude-opus-5-5").contains("jika berbayar"))
    }

    @Test fun modelsAndProviders() {
        assertEquals(Provider.GEMINI, Models.provider("gemini-4-argon")); assertEquals(Provider.CLAUDE, Models.provider("claude-opus-5-5"))
        assertEquals("gemini-3.8-flash", Models.default(Provider.GEMINI)); assertTrue(Models.gemini.none { it.id.contains("argon") })     // berbayar-saja tidak ditawarkan
        assertEquals("Gemini 3.8 Flash", Models.label("gemini-3.8-flash")); assertEquals("model-baru", Models.label("model-baru"))
        assertTrue(Models.maxPdfBytes(Provider.GEMINI) < Models.maxPdfBytes(Provider.CLAUDE))
    }

    @Test fun extractJsonVariants() {
        assertEquals("{\"a\":1}", GeminiClient.extractJson("```json\n{\"a\":1}\n```")); assertEquals("{\"a\":1}", GeminiClient.extractJson(" {\"a\":1} ")); assertEquals("{\"a\":1}", GeminiClient.extractJson("```\n{\"a\":1}\n```"))
    }
}
