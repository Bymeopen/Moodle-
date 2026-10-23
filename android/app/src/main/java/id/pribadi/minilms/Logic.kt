package id.pribadi.minilms

import java.time.Instant
import java.time.ZoneId

/** Logika tampilan murni (tanpa Android) agar mudah dites. */
enum class Bucket(val label: String) { OVERDUE("Terlambat"), TODAY("Hari ini"), TOMORROW("Besok"), WEEK("Minggu ini"), LATER("Nanti") }
enum class Urgency { HIGH, MEDIUM, LOW }

data class GradeSummary(val percent: Double?, val graded: Int, val total: Int, val rows: List<Grade>)

object Logic {
    fun bucket(dueSec: Long, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): Bucket {
        if (dueSec < nowSec) return Bucket.OVERDUE
        val today = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate()
        val due = Instant.ofEpochSecond(dueSec).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
        return when { days <= 0 -> Bucket.TODAY; days == 1L -> Bucket.TOMORROW; days <= 7 -> Bucket.WEEK; else -> Bucket.LATER }
    }

    fun urgency(dueSec: Long, nowSec: Long): Urgency = when {
        dueSec - nowSec < 24 * 3600 -> Urgency.HIGH      // termasuk yang sudah lewat
        dueSec - nowSec < 72 * 3600 -> Urgency.MEDIUM
        else -> Urgency.LOW
    }

    /** "5 jam lagi", "2 hari lagi", "Terlambat 3 hari". */
    fun relative(dueSec: Long, nowSec: Long): String {
        val d = dueSec - nowSec
        val a = Math.abs(d)
        val unit = when { a < 3600 -> "${maxOf(1, a / 60)} menit"; a < 86400 -> "${a / 3600} jam"; a < 86400 * 60 -> "${a / 86400} hari"; else -> "${a / (86400 * 30)} bulan" }
        return if (d >= 0) "$unit lagi" else "Terlambat $unit"
    }

    /** Nama depan berhuruf kapital di awal; angka (NIM) dibuang. "MUHAMMAD HARIS 2026" -> "Muhammad". */
    fun firstName(full: String): String = full.trim().split(Regex("\\s+")).firstOrNull { it.any(Char::isLetter) }
        ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""

    private val STOP = setOf("dan", "dengan", "dalam", "untuk", "di", "ke", "yang", "of", "the", "and", "for", "to")

    /** Dua huruf awal dari dua kata bermakna pertama: "Al-Islam dan Kemuhammadiyahan" -> "AI". */
    fun initials(name: String): String = name.split(Regex("[\\s-]+")).filter { w -> w.firstOrNull()?.isLetter() == true && w.lowercase() !in STOP }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { name.take(2).uppercase() }

    fun greeting(hour: Int) = when (hour) { in 4..10 -> "Selamat pagi"; in 11..14 -> "Selamat siang"; in 15..17 -> "Selamat sore"; else -> "Selamat malam" }

    /** "Algoritma - GANJIL 2026-2027" -> ("Algoritma", "GANJIL 2026-2027"). */
    fun splitTerm(name: String): Pair<String, String?> {
        val m = Regex("\\s[-–]\\s((?:GANJIL|GENAP|PENDEK)[^-–]*?\\d{4}\\s*[-/]\\s*\\d{2,4})\\s*$", RegexOption.IGNORE_CASE).find(name) ?: return name to null
        return name.substring(0, m.range.first).trim() to m.groupValues[1].trim()
    }

    /** Ringkasan nilai: hanya item yang sudah bernilai; persen dari "Total kursus" bila ada, else rata-rata persen item. */
    fun gradeSummary(c: Course): GradeSummary {
        val graded = c.grades.filter { it.grade != null }
        val total = graded.firstOrNull { it.name.equals("Total kursus", true) && (it.max ?: 0.0) > 0 }
        val items = graded.filter { !it.name.equals("Total kursus", true) }
        val pct = total?.let { it.grade!! / it.max!! * 100 }
            ?: items.filter { (it.max ?: 0.0) > 0 }.map { it.grade!! / it.max!! * 100 }.takeIf { it.isNotEmpty() }?.average()
        return GradeSummary(pct, items.size, c.grades.count { !it.name.equals("Total kursus", true) }, items)
    }

    /** Warna banner stabil per kursus berdasarkan id (id Moodle berurutan, jadi kursus bersebelahan jarang kembar). */
    /** Model Gemini yang punya tingkat gratis (data bisa dipakai Google untuk perbaikan produk). */
    fun freeTier(model: String) = model.startsWith("gemini") && !model.contains("argon")

    fun paletteIndex(key: Long, size: Int): Int = Math.floorMod(key, size.toLong()).toInt()

    /** Nilai ringkas: 80 -> "80", 72.5 -> "72,5". */
    fun fmtGrade(g: Double) = if (g == Math.rint(g)) g.toLong().toString() else "%.1f".format(java.util.Locale("id"), g)

    fun fmtSize(b: Long) = when {
        b >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }

    /** "dibaca 3 hari lagi" dll. untuk waktu sinkron: "baru saja", "5 menit lalu", "2 jam lalu". */
    fun ago(thenSec: Long, nowSec: Long): String {
        val d = nowSec - thenSec
        return when { d < 60 -> "baru saja"; d < 3600 -> "${d / 60} menit lalu"; d < 86400 -> "${d / 3600} jam lalu"; else -> "${d / 86400} hari lalu" }
    }
}

/** Jenis file untuk ikon dan warna. */
enum class FileKind { PDF, DOC, PPT, XLS, IMG, ZIP, VIDEO, AUDIO, OTHER;
    companion object {
        fun of(name: String, mime: String?): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "pdf" || mime == "application/pdf" -> PDF
                ext in setOf("doc", "docx", "odt", "rtf", "txt", "md") -> DOC
                ext in setOf("ppt", "pptx", "odp") -> PPT
                ext in setOf("xls", "xlsx", "csv", "ods") -> XLS
                ext in setOf("png", "jpg", "jpeg", "gif", "webp") || mime?.startsWith("image/") == true -> IMG
                ext in setOf("zip", "rar", "7z") -> ZIP
                ext in setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov") || mime?.startsWith("video/") == true -> VIDEO
                ext in setOf("mp3", "m4a", "wav", "ogg", "aac", "opus") || mime?.startsWith("audio/") == true -> AUDIO
                else -> OTHER
            }
        }
    }
}

/** Pemakaian token satu jawaban dan estimasi biayanya (USD per juta token; harga publik, perkiraan). */
data class Usage(val input: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val output: Long = 0) {
    operator fun plus(o: Usage) = Usage(input + o.input, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, output + o.output)
    val isEmpty get() = input + cacheRead + cacheWrite + output == 0L

    /**
     * Estimasi biaya (USD per juta token; harga publik). Gemini: harga Argon/Flash naik pada 1 Des 2026 / 1 Jan 2027.
     * Tingkat gratis Gemini tidak ikut dihitung (lihat [Logic.freeTier]).
     */
    fun costUsd(model: String, today: java.time.LocalDate = java.time.LocalDate.now()): Double {
        val (inP, outP, readP) = when {
            model.contains("haiku") -> Triple(0.10, 0.50, 0.01)
            model.contains("sonnet") -> Triple(2.0, 10.0, 0.20)
            model.startsWith("claude") -> Triple(4.0, 20.0, 0.20)
            model.contains("argon") -> if (today < java.time.LocalDate.of(2026, 12, 1)) Triple(2.0, 10.0, 0.10) else Triple(4.0, 20.0, 0.20)
            model.contains("flash-lite") -> Triple(0.30, 2.50, 0.03)
            model.contains("flash") -> if (today < java.time.LocalDate.of(2027, 1, 1)) Triple(0.75, 3.75, 0.075) else Triple(1.50, 7.50, 0.15)
            else -> Triple(1.0, 5.0, 0.1)
        }
        return (input * inP + cacheWrite * inP * 1.25 + cacheRead * readP + output * outP) / 1_000_000.0
    }

    fun label(model: String): String {
        fun k(n: Long) = if (n >= 1000) "%.1fK".format(java.util.Locale.US, n / 1000.0) else "$n"
        val cost = costUsd(model)
        return buildString {
            append("↑").append(k(input + cacheWrite + cacheRead))
            if (cacheRead > 0) append(" (cache ").append(k(cacheRead)).append(')')
            append(" ↓").append(k(output)).append(" · ≈ $").append(if (cost < 0.01) "%.4f".format(java.util.Locale.US, cost) else "%.2f".format(java.util.Locale.US, cost))
            if (Logic.freeTier(model)) append(" jika berbayar")
        }
    }
}
