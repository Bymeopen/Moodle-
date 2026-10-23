package id.pribadi.minilms

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Kartu datar dengan garis tepi halus (gaya modern, tanpa bayangan berat). */
@Composable
fun Card2(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
          border: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val b = if (border) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    if (onClick != null) Surface(onClick, modifier, shape = shape, color = color, border = b) { Column(content = content) }
    else Surface(modifier, shape = shape, color = color, border = b) { Column(content = content) }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) =
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) =
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
    }

@Composable
fun Pill(text: String, container: Color, content: Color, icon: ImageVector? = null, modifier: Modifier = Modifier) =
    Surface(modifier, shape = CircleShape, color = container) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = content)
            Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
        }
    }

/** Cincin progres dengan konten di tengah. */
@Composable
fun ProgressRing(progress: Float, size: Dp = 44.dp, stroke: Dp = 5.dp, color: Color = MaterialTheme.colorScheme.primary,
                 track: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable () -> Unit = {}) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx(); val inset = s / 2
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            if (p > 0f) drawArc(color, -90f, 360f * p, false, Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Banner gradien kursus dengan inisial sebagai watermark. */
@Composable
fun CourseBanner(name: String, colorKey: Long, modifier: Modifier = Modifier, height: Dp = 84.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val (a, b) = Palette.course(colorKey)
    Box(modifier.fillMaxWidth().height(height).background(Brush.linearGradient(listOf(a, b), start = Offset(0f, 0f), end = Offset(900f, 500f)))) {
        Text(Logic.initials(Logic.splitTerm(name).first), Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = (height.value * 0.62f).sp, fontWeight = FontWeight.Black), color = Color.White.copy(alpha = 0.22f))
        content()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (actionLabel != null && onAction != null) FilledTonalButton(onAction) { Text(actionLabel) }
    }

enum class Tone { INFO, WARN, ERROR }

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.INFO, icon: ImageVector = Icons.Rounded.Info, action: String? = null, onAction: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) { Tone.INFO -> cs.secondaryContainer to cs.onSecondaryContainer; Tone.WARN -> cs.primaryContainer to cs.onPrimaryContainer; Tone.ERROR -> cs.errorContainer to cs.onErrorContainer }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = bg) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = fg)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = fg)
            if (action != null && onAction != null) TextButton(onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action, color = fg) }
        }
    }
}

/** Placeholder berkilau saat data belum ada. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = a)))
}

/** Ikon file berwarna sesuai jenis. */
@Composable
fun FileTile(kind: FileKind, size: Dp = 40.dp) {
    val (icon, color) = when (kind) {
        FileKind.PDF -> Icons.Rounded.PictureAsPdf to Color(0xFFD64545); FileKind.DOC -> Icons.Rounded.Description to Color(0xFF2F6FE4)
        FileKind.PPT -> Icons.Rounded.Slideshow to Color(0xFFE8710C); FileKind.XLS -> Icons.Rounded.TableChart to Color(0xFF1E9E5A)
        FileKind.IMG -> Icons.Rounded.Image to Color(0xFF8E5BD6); FileKind.ZIP -> Icons.Rounded.FolderZip to Color(0xFF7A6F66)
        FileKind.OTHER -> Icons.Rounded.InsertDriveFile to Color(0xFF7A6F66)
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.55f), tint = color)
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) =
    Box(modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(size * 0.52f), tint = tint) }

@Composable
fun StatTile(icon: ImageVector, value: String, label: String, tint: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) =
    Card2(modifier, onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBadge(icon, tint, size = 34.dp)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }

@Composable
fun Modifier.clickableRow(onClick: () -> Unit) = this.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)

/** Bilah progres sederhana (tanpa titik penanda M3) dengan animasi. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
                track: Color = MaterialTheme.colorScheme.surfaceVariant, height: Dp = 6.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(500), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/** Waktu sekarang (detik) yang menyegarkan diri tiap menit dan saat [key] berubah, supaya hitung mundur tidak basi. */
@Composable
fun rememberNowSec(vararg key: Any?): androidx.compose.runtime.State<Long> = androidx.compose.runtime.produceState(System.currentTimeMillis() / 1000, *key) {
    while (true) { value = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(60_000) }
}
