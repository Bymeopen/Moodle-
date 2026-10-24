package id.pribadi.minilms

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** Pembersih teks untuk dibaca suara: buang simbol Markdown/tautan dan pecah per potongan di bawah batas mesin TTS. Murni dan dites. */
object Speech {
    fun clean(md: String): String = md
        .lines().filterNot { it.startsWith("*› ") }.joinToString("\n")                         // jejak aksi agen tidak dibacakan
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "").replace(Regex("(?m)^\\s*[-*+]\\s+"), "").replace(Regex("(?m)^\\s*>\\s?"), "")
        .replace(Regex("[*_`~]+"), "").replace(Regex("\\|"), " ")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Potongan ≤ [max] karakter, dipecah di akhir kalimat/baris bila bisa. */
    fun chunks(text: String, max: Int = 3500): List<String> {
        val out = mutableListOf<String>(); var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = listOf(window.lastIndexOf("\n"), window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! ")).max().takeIf { it > max / 2 } ?: window.lastIndexOf(' ').takeIf { it > max / 2 } ?: max
            out += rest.substring(0, cut + 1).trim(); rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotBlank() }
    }
}

/** Membacakan jawaban/ringkasan lewat Text-to-Speech bawaan Android (gratis, bisa offline bila suara Indonesia terpasang). */
class Speaker(private val app: Application, private val notify: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: List<String>? = null
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) { notify("Mesin suara tidak tersedia di ponsel ini"); tts = null; queued = null; return@TextToSpeech }
            val r = t.setLanguage(Locale("id", "ID"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) notify("Suara bahasa Indonesia belum terpasang (Pengaturan → Teks ke ucapan); memakai suara bawaan")
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id?.endsWith("_last") == true) _speaking.value = false }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { _speaking.value = false }
            })
            ready = true
            queued?.let { queued = null; play(it) }
        }
    }

    private fun play(chunks: List<String>) {
        val t = tts ?: return
        t.stop()
        chunks.forEachIndexed { n, c -> t.speak(c, TextToSpeech.QUEUE_ADD, null, "u$n" + if (n == chunks.lastIndex) "_last" else "") }
        _speaking.value = true
    }

    fun speak(markdown: String) {
        val chunks = Speech.chunks(Speech.clean(markdown)); if (chunks.isEmpty()) return
        ensure()
        if (ready) play(chunks) else { queued = chunks; _speaking.value = true }
    }

    fun stop() { queued = null; tts?.stop(); _speaking.value = false }
    fun shutdown() { runCatching { tts?.stop(); tts?.shutdown() }; tts = null; ready = false; _speaking.value = false }
}
