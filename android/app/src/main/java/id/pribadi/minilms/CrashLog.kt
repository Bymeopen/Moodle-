package id.pribadi.minilms

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Jejak crash lokal + diagnostik yang bisa dibagikan pengguna. Tidak ada pengiriman otomatis ke mana pun.
 * Teks disamarkan dari token/kunci/kata sandi sebelum disimpan atau dibagikan.
 */
object CrashLog {
    private const val FILE = "crash.log"

    /** Samarkan pola rahasia umum: key=..., token=..., password=..., kunci API Google/Anthropic, dan string hex panjang. */
    fun redact(text: String): String = text
        .replace(Regex("(?i)(token|key|password|passwd|secret|authorization)([\"'=:\\s]+)([A-Za-z0-9_\\-./+=]{6,})"), "$1$2[disamarkan]")
        .replace(Regex("AIza[0-9A-Za-z_\\-]{20,}"), "[kunci-google]")
        .replace(Regex("sk-ant-[0-9A-Za-z_\\-]{10,}"), "[kunci-anthropic]")
        .replace(Regex("\\b[0-9a-fA-F]{32,}\\b"), "[hex-disamarkan]")

    fun format(e: Throwable, thread: String, nowMillis: Long): String {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        return redact("Waktu: ${java.util.Date(nowMillis)}\nThread: $thread\n$sw").take(12_000)
    }

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(app.filesDir, FILE).writeText(format(e, t.name, System.currentTimeMillis())) }
            previous?.uncaughtException(t, e)
        }
    }

    fun last(ctx: Context): String? = File(ctx.filesDir, FILE).takeIf { it.isFile }?.readText()
    fun clear(ctx: Context) { File(ctx.filesDir, FILE).delete() }

    /** Ringkasan keadaan aplikasi untuk laporan galat (tanpa rahasia dan tanpa isi kursus). */
    fun diagnostics(ctx: Context, repo: Repo, provider: String, model: String): String {
        val pm = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val snap = repo.snapshot.value
        return buildString {
            append("Mini LMS ").append(pm.versionName).append(" (").append(pm.longVersionCode).append(")\n")
            append("Android ").append(android.os.Build.VERSION.RELEASE).append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append("), ")
            append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append('\n')
            append("Sinkron terakhir: ").append(snap?.let { java.util.Date(it.syncedAt * 1000).toString() } ?: "belum").append('\n')
            append("Kursus: ").append(snap?.courses?.size ?: 0).append(", item: ").append(snap?.courses?.sumOf { it.items.size } ?: 0)
            append(", file: ").append(snap?.courses?.sumOf { c -> c.items.sumOf { it.files.size } } ?: 0).append(", kartu: ").append(repo.cards.value.size).append('\n')
            append("Peringatan sinkron: ").append(snap?.warnings?.joinToString("; ").orEmpty().ifEmpty { "-" }).append('\n')
            append("AI: ").append(provider).append(" / ").append(model).append('\n')
        }.let(::redact)
    }
}

class MiniLmsApp : Application() {
    override fun onCreate() { super.onCreate(); CrashLog.install(this) }
}
