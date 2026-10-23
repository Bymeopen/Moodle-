package id.pribadi.minilms

/** Penguraian Markdown ringan untuk jawaban Claude (tanpa dependensi). Mendukung yang lazim muncul di jawaban tutor. */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<Span>) : MdBlock
    data class Para(val spans: List<Span>) : MdBlock
    data class Item(val depth: Int, val marker: String, val spans: List<Span>) : MdBlock
    data class Code(val lang: String, val text: String) : MdBlock
    data class Quote(val spans: List<Span>) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

object Markdown {
    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Para(inline(para.toString().trim())); para.clear() }
        while (i < lines.size) {
            val line = lines[i]; val t = line.trim()
            when {
                t.startsWith("```") -> {                       // blok kode (tanpa penutup = sampai akhir, penting saat streaming)
                    flush(); val lang = t.removePrefix("```").trim(); val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { if (code.isNotEmpty()) code.append('\n'); code.append(lines[i]); i++ }
                    out += MdBlock.Code(lang, code.toString())
                }
                t.isEmpty() -> flush()
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out += MdBlock.Rule }
                Regex("^#{1,6}\\s+.+").matches(t) -> { flush(); val lv = t.takeWhile { it == '#' }.length; out += MdBlock.Heading(lv, inline(t.drop(lv).trim())) }
                t.startsWith(">") -> { flush(); out += MdBlock.Quote(inline(t.removePrefix(">").trim())) }
                t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$").matches(lines[i + 1].trim()) -> {
                    flush(); val rows = mutableListOf(cells(t)); i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) { rows += cells(lines[i].trim()); i++ }
                    out += MdBlock.Table(rows); continue
                }
                Regex("^(\\s*)([-*+•])\\s+(.+)").matchEntire(line) != null -> {
                    flush(); val m = Regex("^(\\s*)([-*+•])\\s+(.+)").matchEntire(line)!!
                    out += MdBlock.Item(m.groupValues[1].length / 2, "•", inline(m.groupValues[3]))
                }
                Regex("^(\\s*)(\\d+)[.)]\\s+(.+)").matchEntire(line) != null -> {
                    flush(); val m = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)").matchEntire(line)!!
                    out += MdBlock.Item(m.groupValues[1].length / 2, m.groupValues[2] + ".", inline(m.groupValues[3]))
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    private fun cells(row: String) = row.trim().trim('|').split('|').map { it.trim() }

    /** **tebal**, *miring*, `kode`, [teks](url). Penanda yang tidak tertutup (saat streaming) ditampilkan apa adanya. */
    fun inline(s: String): List<Span> {
        val spans = mutableListOf<Span>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { spans += Span(buf.toString()); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("**", i) || s.startsWith("__", i) -> {
                    val mark = s.substring(i, i + 2); val end = s.indexOf(mark, i + 2)
                    if (end > i + 2) { flush(); spans += inline(s.substring(i + 2, end)).map { it.copy(bold = true) }; i = end + 2 } else { buf.append(c); i++ }
                }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i + 1) { flush(); spans += Span(s.substring(i + 1, end), code = true); i = end + 1 } else { buf.append(c); i++ }
                }
                c == '[' -> {
                    val m = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)").find(s.substring(i))
                    if (m != null) { flush(); spans += Span(m.groupValues[1], link = m.groupValues[2]); i += m.value.length } else { buf.append(c); i++ }
                }
                (c == '*' || c == '_') && (i == 0 || !s[i - 1].isLetterOrDigit()) && i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                    val end = s.indexOf(c, i + 1)
                    if (end > i + 1 && (end + 1 >= s.length || !s[end + 1].isLetterOrDigit())) { flush(); spans += inline(s.substring(i + 1, end)).map { it.copy(italic = true) }; i = end + 1 } else { buf.append(c); i++ }
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return spans
    }
}
