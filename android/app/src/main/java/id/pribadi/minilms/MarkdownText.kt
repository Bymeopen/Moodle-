package id.pribadi.minilms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun annotated(spans: List<Span>, codeBg: Color, link: Color): AnnotatedString = buildAnnotatedString {
    spans.forEach { s ->
        withStyle(SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null, fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null, background = if (s.code) codeBg else Color.Unspecified,
            fontSize = if (s.code) 13.sp else TextUnitUnspecified, color = if (s.link != null) link else Color.Unspecified,
            textDecoration = if (s.link != null) TextDecoration.Underline else null,
        )) { append(s.text) }
    }
}
private val TextUnitUnspecified = androidx.compose.ui.unit.TextUnit.Unspecified

/** Jawaban Claude (Markdown) sebagai teks berformat. Aman saat streaming: penanda yang belum tertutup tampil apa adanya. */
@Composable
fun MarkdownText(src: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(src) { Markdown.parse(src) }
    val cs = MaterialTheme.colorScheme; val ty = MaterialTheme.typography
    val codeBg = cs.surfaceContainerHigh
    Column(modifier) {
        blocks.forEachIndexed { idx, b ->
            // butir daftar berurutan dirapatkan; blok lain diberi jarak normal
            val prev = blocks.getOrNull(idx - 1)
            if (idx > 0) Spacer(Modifier.height(if (b is MdBlock.Item && prev is MdBlock.Item) 3.dp else 10.dp))
            when (b) {
                is MdBlock.Heading -> Text(annotated(b.spans, codeBg, cs.primary), color = color,
                    style = when (b.level) { 1 -> ty.titleLarge; 2 -> ty.titleMedium; else -> ty.titleSmall }, modifier = Modifier.padding(top = if (b.level <= 2) 2.dp else 0.dp))
                is MdBlock.Para -> Text(annotated(b.spans, codeBg, cs.primary), style = ty.bodyMedium, color = color)
                is MdBlock.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text(b.marker, Modifier.width(22.dp), style = ty.bodyMedium, color = if (b.marker == "•") cs.primary else cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    Text(annotated(b.spans, codeBg, cs.primary), style = ty.bodyMedium, color = color)
                }
                is MdBlock.Code -> CodeBlock(b)
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(cs.primary, RoundedCornerShape(2.dp)))
                    Text(annotated(b.spans, codeBg, cs.primary), Modifier.padding(start = 10.dp), style = ty.bodyMedium.copy(fontStyle = FontStyle.Italic), color = cs.onSurfaceVariant)
                }
                is MdBlock.Table -> TableBlock(b)
                MdBlock.Rule -> HorizontalDivider(color = cs.outlineVariant)
            }
        }
    }
}

@Composable
private fun CodeBlock(b: MdBlock.Code) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerHigh) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(b.lang.ifEmpty { "kode" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                IconButton({ clip.setText(AnnotatedString(b.text)) }, Modifier.size(36.dp)) { Icon(Icons.Rounded.ContentCopy, "Salin kode", Modifier.size(16.dp), tint = cs.onSurfaceVariant) }
            }
            Text(b.text, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurface)
        }
    }
}

@Composable
private fun TableBlock(t: MdBlock.Table) {
    val cs = MaterialTheme.colorScheme
    val cols = t.rows.maxOf { it.size }
    Column(Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp)).clip10()) {
        t.rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().background(if (i == 0) cs.surfaceContainerHigh else Color.Transparent)) {
                repeat(cols) { c -> Text(row.getOrElse(c) { "" }, Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal) }
            }
            if (i < t.rows.lastIndex) HorizontalDivider(color = cs.outlineVariant)
        }
    }
}

private fun Modifier.clip10() = this.then(Modifier.clip(RoundedCornerShape(10.dp)))
