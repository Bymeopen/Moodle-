package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klien Google Gemini API (generateContent / streamGenerateContent) dengan kunci API milik pengguna.
 * Auth: header x-goog-api-key. [baseUrl] bisa diganti untuk tes. Tanpa dependensi.
 */
class GeminiClient(private val apiKey: String, private val model: String, private val baseUrl: String = "https://generativelanguage.googleapis.com") : AiBackend {
    companion object {
        const val MAX_OUTPUT = 32768

        /** JSON Schema -> subset OpenAPI milik Gemini: tipe huruf besar, buang additionalProperties dan kata kunci lain yang tidak didukung. */
        fun toSchema(s: JSONObject): JSONObject {
            val o = JSONObject()
            s.optString("type").takeIf { it.isNotEmpty() }?.let { o.put("type", it.uppercase()) }
            s.optString("description").takeIf { it.isNotEmpty() }?.let { o.put("description", it) }
            s.optJSONArray("enum")?.let { o.put("enum", it) }
            s.optJSONArray("required")?.let { o.put("required", it) }
            s.optJSONObject("items")?.let { o.put("items", toSchema(it)) }
            s.optJSONObject("properties")?.let { p -> o.put("properties", JSONObject().also { out -> p.keys().forEach { k -> out.put(k, toSchema(p.getJSONObject(k))) } }) }
            return o
        }

        private fun part(m: ChatMsg): JSONArray = JSONArray(
            m.docs.map { d -> JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf").put("data", d.base64)) } +
                JSONObject().put("text", m.text))

        /** Body permintaan. [schema] != null -> keluaran JSON terstruktur. */
        fun body(system: String, messages: List<ChatMsg>, schema: JSONObject? = null): JSONObject {
            val b = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray(messages.map { JSONObject().put("role", if (it.role == "assistant") "model" else "user").put("parts", part(it)) }))
            val cfg = JSONObject().put("maxOutputTokens", MAX_OUTPUT)
            if (schema != null) cfg.put("responseMimeType", "application/json").put("responseSchema", toSchema(schema))
            return b.put("generationConfig", cfg)
        }

        /**
         * Satu baris SSE (satu potongan GenerateContentResponse) -> teks. Bagian "thought" diabaikan.
         * Melempar bila prompt diblokir; [finish] menerima finishReason selain STOP.
         */
        fun parseChunk(line: String, onUsage: (Usage) -> Unit = {}, finish: (String) -> Unit = {}): String? {
            if (!line.startsWith("data:")) return null
            val j = try { JSONObject(line.removePrefix("data:").trim()) } catch (e: Exception) { return null }
            j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
                throw ApiError("Google memblokir permintaan ini ($it). Ubah pertanyaan atau materinya.")
            }
            j.optJSONObject("usageMetadata")?.let { u ->
                val prompt = u.optLong("promptTokenCount"); val cached = u.optLong("cachedContentTokenCount")
                onUsage(Usage(input = maxOf(prompt - cached, 0), cacheRead = cached, cacheWrite = 0, output = u.optLong("candidatesTokenCount") + u.optLong("thoughtsTokenCount")))
            }
            val cand = j.optJSONArray("candidates")?.optJSONObject(0) ?: return null
            cand.optString("finishReason").takeIf { it.isNotEmpty() && it != "STOP" && it != "FINISH_REASON_UNSPECIFIED" }?.let(finish)
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: return null
            return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { !it.optBoolean("thought") && it.has("text") }.joinToString("") { it.getString("text") }.ifEmpty { null }
        }

        fun friendly(code: Int, status: String?, message: String?): String = when {
            code == 429 -> "Batas pemakaian Gemini tercapai (tingkat gratis dibatasi per menit/hari). Tunggu sebentar, ganti ke model Flash-Lite, atau pakai Claude."
            code == 400 && (message?.contains("API key", true) == true || status == "INVALID_ARGUMENT" && message?.contains("key", true) == true) -> "Kunci API Gemini tidak valid. Periksa di Profil → AI."
            code == 403 -> "Kunci API tidak punya izin untuk model ini (model berbayar butuh penagihan aktif)."
            code == 404 -> "Model tidak ditemukan. Nama model Gemini sering berubah; ganti di Profil → AI."
            code == 503 || code == 500 -> "Layanan Gemini sedang sibuk. Coba lagi."
            else -> message ?: "HTTP $code"
        }

        /** Ambil JSON dari teks yang mungkin dibungkus ```json ... ```. */
        fun extractJson(text: String): String {
            val t = text.trim()
            val fenced = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$").find(t)
            return fenced?.groupValues?.get(1) ?: t
        }
    }

    private fun open(path: String, body: JSONObject): HttpURLConnection {
        val c = URL("$baseUrl/v1beta/models/$model:$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 120_000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-goog-api-key", apiKey)      // kunci di header, bukan di URL
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return c
    }

    private fun errorOf(c: HttpURLConnection): Pair<Int, ApiError> {
        val code = c.responseCode
        val e = runCatching { JSONObject(c.errorStream?.bufferedReader()?.readText().orEmpty()).getJSONObject("error") }.getOrNull()
        c.disconnect()
        return code to ApiError(friendly(code, e?.optString("status"), e?.optString("message")))
    }

    override fun stream(system: String, messages: List<ChatMsg>, shouldStop: () -> Boolean, cache: Boolean, onUsage: (Usage) -> Unit, onText: (String) -> Unit): String {
        val c = open("streamGenerateContent?alt=sse", body(system, messages))
        if (c.responseCode != 200) throw errorOf(c).second
        val all = StringBuilder(); var finish = ""
        try {
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    if (shouldStop()) break
                    val line = r.readLine() ?: break
                    parseChunk(line, onUsage) { finish = it }?.let { all.append(it); onText(it) }
                }
            }
        } finally { c.disconnect() }
        if (all.isEmpty() && finish.isNotEmpty()) throw ApiError("Google tidak memberi jawaban ($finish). Coba ubah pertanyaan.")
        if (finish == "MAX_TOKENS") all.append("\n\n[Jawaban terpotong]")
        return all.toString()
    }

    override fun json(system: String, user: String, schema: JSONObject, docs: List<Doc>): JSONObject {
        val msgs = listOf(ChatMsg("user", user, docs))
        var c = open("generateContent", body(system, msgs, schema))
        if (c.responseCode == 400) {
            val (_, err) = errorOf(c)
            // skema ditolak (fitur terbatas per model): ulangi tanpa responseSchema, skema dimasukkan ke prompt
            if (err.message?.contains("schema", true) != true && err.message?.contains("Unknown name", true) != true) throw err
            c = open("generateContent", body("$system\nBalas HANYA dengan JSON valid (tanpa teks lain) sesuai skema ini: $schema", msgs).also {
                it.getJSONObject("generationConfig").put("responseMimeType", "application/json")
            })
        }
        if (c.responseCode != 200) throw errorOf(c).second
        try {
            val resp = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            resp.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let { throw ApiError("Google memblokir permintaan ini ($it).") }
            val cand = resp.optJSONArray("candidates")?.optJSONObject(0) ?: throw ApiError("Google tidak memberi jawaban")
            if (cand.optString("finishReason") == "MAX_TOKENS") throw ApiError("Jawaban terpotong; coba dengan materi lebih pendek.")
            val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: throw ApiError("Google tidak memberi jawaban (${cand.optString("finishReason")})")
            val text = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { !it.optBoolean("thought") && it.has("text") }.joinToString("") { it.getString("text") }
            return try { JSONObject(extractJson(text)) } catch (e: Exception) { throw ApiError("Format jawaban Gemini tidak valid") }
        } finally { c.disconnect() }
    }
}
