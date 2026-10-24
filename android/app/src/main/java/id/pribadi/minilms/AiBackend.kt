package id.pribadi.minilms

import org.json.JSONObject

/** Penyedia AI yang didukung. Kunci API disimpan terpisah per penyedia. */
enum class Provider(val label: String, val keyName: String, val keyUrl: String) {
    GEMINI("Google Gemini", "gemini_key", "https://aistudio.google.com/apikey"),
    CLAUDE("Anthropic Claude", "anthropic_key", "https://console.anthropic.com/settings/keys"),
}

/** Satu model: id untuk API, nama tampil, catatan harga singkat. */
data class ModelInfo(val id: String, val label: String, val note: String)

object Models {
    // Gemini 4 Argon (flagship) hanya berbayar sehingga tidak ditampilkan; masih bisa dipakai lewat "ID model lain" di Profil.
    // Nama model diverifikasi dari dokumentasi resmi (ai.google.dev/gemini-api/docs/models) per Okt 2026. Bisa diganti manual di Profil.
    val gemini = listOf(
        ModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash", "Cepat · GRATIS"),
        ModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "Paling ringan · GRATIS (batas harian lebih longgar)"),
    )
    val claude = listOf(
        ModelInfo("claude-opus-5-5", "Claude Opus 5.5", "Terpintar · BERBAYAR (\$4 / \$20 per juta token)"),
        ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5", "Seimbang · BERBAYAR (\$2 / \$10)"),
        ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", "Termurah · BERBAYAR (\$0,10 / \$0,50)"),
    )

    fun of(p: Provider) = if (p == Provider.GEMINI) gemini else claude
    fun default(p: Provider) = of(p).first().id
    fun provider(modelId: String) = if (modelId.startsWith("gemini")) Provider.GEMINI else Provider.CLAUDE
    fun label(modelId: String) = (gemini + claude).firstOrNull { it.id == modelId }?.label ?: modelId

    /** Batas total PDF terlampir (byte mentah). Gemini: permintaan inline ≤ ~20 MB setelah base64. */
    fun maxPdfBytes(p: Provider) = if (p == Provider.GEMINI) 14L * 1024 * 1024 else 20L * 1024 * 1024
}

/** Antarmuka bersama klien AI; [AiController] hanya bicara lewat ini. */
interface AiBackend {
    /** Streaming teks. [onText] dipanggil per potongan; mengembalikan teks lengkap. */
    fun stream(system: String, messages: List<ChatMsg>, shouldStop: () -> Boolean = { false }, cache: Boolean = false,
               onUsage: (Usage) -> Unit = {}, onText: (String) -> Unit): String

    /** Satu putaran agen (tanpa streaming): model menjawab teks dan/atau meminta panggilan alat. Penyedia yang belum mendukung melempar ApiError. */
    fun step(system: String, msgs: List<AgentMsg>, tools: List<ToolSpec>): AgentReply = throw ApiError("Penyedia ini belum mendukung agen LMS")

    /** Keluaran terstruktur sesuai skema JSON (JSON Schema); hasilnya JSON valid atau ApiError. */
    fun json(system: String, user: String, schema: JSONObject, docs: List<Doc> = emptyList()): JSONObject
}
