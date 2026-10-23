package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMsg(val role: String, val text: String)

object Models {
    // default mengikuti rekomendasi Claude API; Sonnet/Haiku lebih murah untuk pemakaian harian
    val all = listOf("claude-opus-5-5" to "Claude Opus 5.5 (terpintar)", "claude-sonnet-5-5" to "Claude Sonnet 5.5 (seimbang)", "claude-haiku-5-5" to "Claude Haiku 5.5 (termurah)")
    const val DEFAULT = "claude-opus-5-5"
}

/**
 * Klien Claude Messages API langsung dari ponsel memakai kunci API milik pengguna.
 * [baseUrl] bisa diganti untuk tes. Tanpa dependensi: HttpURLConnection + org.json.
 */
class ClaudeClient(private val apiKey: String, private val model: String, private val baseUrl: String = "https://api.anthropic.com") {
    companion object {
        const val VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Body permintaan. Fallback server-side (jika model menolak karena safety) hanya untuk Opus/Sonnet. */
        fun body(model: String, system: String, messages: List<ChatMsg>, stream: Boolean, schema: JSONObject? = null, withFallback: Boolean = true): JSONObject {
            val b = JSONObject().put("model", model).put("max_tokens", if (stream) 64000 else 16000).put("system", system)
                .put("messages", JSONArray(messages.map { JSONObject().put("role", it.role).put("content", it.text) }))
            if (stream) b.put("stream", true)
            if (schema != null) b.put("output_config", JSONObject().put("format", JSONObject().put("type", "json_schema").put("schema", schema)))
            if (withFallback && !model.contains("haiku")) b.put("fallbacks", "default")
            return b
        }

        /** Satu baris SSE -> potongan teks (atau null). Melempar bila event error; mengisi [stop] bila ada stop_reason khusus. */
        fun parseSse(line: String, stop: (String) -> Unit = {}): String? {
            if (!line.startsWith("data:")) return null
            val j = try { JSONObject(line.removePrefix("data:").trim()) } catch (e: Exception) { return null }
            return when (j.optString("type")) {
                "content_block_delta" -> j.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.optString("text")
                "message_delta" -> { j.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotEmpty() && it != "null" }?.let(stop); null }
                "error" -> throw ApiError(j.optJSONObject("error")?.optString("message") ?: "Galat dari Claude")
                else -> null
            }
        }

        fun friendly(code: Int, message: String?): String = when (code) {
            401 -> "Kunci API tidak valid. Periksa di Lainnya → Claude AI."
            403 -> "Kunci API tidak punya izin untuk model ini."
            429 -> "Batas penggunaan terlampaui. Coba lagi sebentar."
            529, 503 -> "Layanan Claude sedang sibuk. Coba lagi."
            else -> message ?: "HTTP $code"
        }
    }

    private fun open(body: JSONObject, withFallback: Boolean): HttpURLConnection {
        val c = URL("$baseUrl/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 20_000; c.readTimeout = 120_000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", apiKey)
        c.setRequestProperty("anthropic-version", VERSION)
        if (withFallback && body.has("fallbacks")) c.setRequestProperty("anthropic-beta", FALLBACK_BETA)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return c
    }

    /** Kirim; bila ditolak 400 karena fallback, ulangi tanpa fallback (fitur beta; tidak semua akun/model mendukung). */
    private fun send(build: (Boolean) -> JSONObject): HttpURLConnection {
        var c = open(build(true), true)
        if (c.responseCode == 400) {
            val err = c.errorStream?.bufferedReader()?.readText().orEmpty(); c.disconnect()
            if (!err.contains("fallback", ignoreCase = true) && !err.contains("beta", ignoreCase = true)) throw ApiError(friendly(400, runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrNull()))
            c = open(build(false), false)
        }
        if (c.responseCode != 200) {
            val msg = runCatching { JSONObject(c.errorStream?.bufferedReader()?.readText().orEmpty()).getJSONObject("error").getString("message") }.getOrNull()
            val code = c.responseCode; c.disconnect(); throw ApiError(friendly(code, msg))
        }
        return c
    }

    /** Streaming teks. [onText] dipanggil per potongan. Mengembalikan teks lengkap. */
    fun stream(system: String, messages: List<ChatMsg>, shouldStop: () -> Boolean = { false }, onText: (String) -> Unit): String {
        val c = send { fb -> body(model, system, messages, stream = true, withFallback = fb) }
        val all = StringBuilder(); var stopReason = ""
        try {
            c.inputStream.bufferedReader().use { r ->
                while (true) {
                    if (shouldStop()) break
                    val line = r.readLine() ?: break
                    parseSse(line) { stopReason = it }?.let { all.append(it); onText(it) }
                }
            }
        } finally { c.disconnect() }
        if (stopReason == "refusal") throw ApiError("Claude menolak permintaan ini.")
        if (stopReason == "max_tokens") all.append("\n\n[Jawaban terpotong]")
        return all.toString()
    }

    /** Keluaran terstruktur sesuai skema JSON; hasilnya JSON valid atau ApiError. */
    fun json(system: String, user: String, schema: JSONObject): JSONObject {
        val c = send { fb -> body(model, system, listOf(ChatMsg("user", user)), stream = false, schema = schema, withFallback = fb) }
        try {
            val resp = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            when (resp.optString("stop_reason")) { "refusal" -> throw ApiError("Claude menolak permintaan ini."); "max_tokens" -> throw ApiError("Jawaban terpotong; coba dengan materi lebih pendek.") }
            val text = resp.getJSONArray("content").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.getString("text") } }
            return try { JSONObject(text) } catch (e: Exception) { throw ApiError("Format jawaban Claude tidak valid") }
        } finally { c.disconnect() }
    }
}

/** Menyusun teks materi kursus untuk konteks AI. File (PDF/slide) tidak dibaca; hanya dicantumkan namanya. */
object AiMaterial {
    const val MAX_CHARS = 400_000

    fun build(c: Course): String = buildString {
        append("Kursus: ").append(c.name).append('\n')
        if (c.summary.isNotBlank()) append(c.summary).append('\n')
        var section: String? = null
        for (i in c.items) {
            if (i.section != section) { section = i.section; if (section.isNotEmpty()) append("\n## ").append(section).append('\n') }
            append("\n### [").append(i.type).append("] ").append(i.title).append('\n')
            if (i.type != "link" && i.content.isNotBlank()) append(i.content).append('\n')
            i.dueAt?.let { append("Tenggat (epoch detik): ").append(it).append('\n') }
            if (i.files.isNotEmpty()) append("File terlampir (isi tidak dibaca): ").append(i.files.joinToString { it.name }).append('\n')
        }
    }

    /** Teks materi atau ApiError bila terlalu panjang (tidak dipotong diam-diam). */
    fun forAi(c: Course): String {
        val t = build(c)
        if (t.length > MAX_CHARS) throw ApiError("Materi kursus terlalu panjang untuk satu permintaan (${t.length / 1000}K karakter)")
        return t
    }
}
