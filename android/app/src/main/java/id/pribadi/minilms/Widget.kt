package id.pribadi.minilms

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.ZoneId

/** Satu baris widget: judul tugas, kursus, dan sisa waktu. */
data class WidgetLine(val title: String, val course: String, val whenText: String, val urgent: Boolean)

/** Isi widget layar utama. [signedIn] false = tampilkan ajakan masuk. */
data class WidgetData(val signedIn: Boolean, val lines: List<WidgetLine>, val moreCount: Int, val dueCards: Int, val syncedText: String)

/** Murni (tanpa Android) agar mudah dites; [WidgetRender] hanya memindahkannya ke RemoteViews. */
object WidgetModel {
    const val MAX_LINES = 3

    fun build(snapshot: Snapshot?, done: Set<Long>, cards: List<Card>, nowSec: Long, signedIn: Boolean, today: java.time.LocalDate = java.time.LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): WidgetData {
        if (!signedIn) return WidgetData(false, emptyList(), 0, 0, "")
        val rows = deadlinesOf(snapshot).filter { r ->
            val i = r.item
            i.id !in done && i.dueAt!! >= nowSec && !(i.type == "quiz" && i.attempts.any { it.state == "finished" })
        }
        val lines = rows.take(MAX_LINES).map { r ->
            WidgetLine(r.item.title, Logic.splitTerm(r.course.name).first, Logic.relative(r.item.dueAt!!, nowSec), Logic.urgency(r.item.dueAt, nowSec) == Urgency.HIGH)
        }
        val due = cards.count { Srs.isDue(it, today) }
        return WidgetData(true, lines, (rows.size - lines.size).coerceAtLeast(0), due, snapshot?.let { "Sinkron " + Logic.ago(it.syncedAt, nowSec) } ?: "Belum sinkron")
    }
}

/** Tujuan yang bisa dibuka dari widget/notifikasi; dibaca MainActivity lewat extra [EXTRA]. */
object OpenTarget {
    const val EXTRA = "open"
    const val REVIEW = "review"; const val DEADLINES = "deadlines"; const val PLAN = "plan"; const val SEARCH = "search"; const val NOTES = "notes"
    val all = setOf(REVIEW, DEADLINES, PLAN, SEARCH, NOTES)
    fun intent(ctx: Context, target: String?): Intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).also { if (target != null) it.putExtra(EXTRA, target) }
    fun pending(ctx: Context, target: String?, code: Int): PendingIntent = PendingIntent.getActivity(ctx, code, intent(ctx, target), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

class DeadlineWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { for (id in ids) mgr.updateAppWidget(id, WidgetRender.views(ctx)) }

    companion object {
        /** Perbarui semua widget yang terpasang; aman dipanggil dari mana saja (galat widget tidak boleh menjatuhkan sinkron/simpan). */
        fun refresh(ctx: Context) = runCatching {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, DeadlineWidget::class.java))
            if (ids.isNotEmpty()) { val v = WidgetRender.views(ctx); for (id in ids) mgr.updateAppWidget(id, v) }
        }
    }
}

object WidgetRender {
    private val rows = intArrayOf(R.id.w_row1, R.id.w_row2, R.id.w_row3)
    private val titles = intArrayOf(R.id.w_title1, R.id.w_title2, R.id.w_title3)
    private val subs = intArrayOf(R.id.w_sub1, R.id.w_sub2, R.id.w_sub3)

    fun views(ctx: Context): RemoteViews {
        val repo = Repo.get(ctx)
        val now = System.currentTimeMillis() / 1000
        val d = WidgetModel.build(repo.snapshot.value, repo.doneAssignments.value, repo.cards.value, now, repo.site.value != null)
        val v = RemoteViews(ctx.packageName, R.layout.widget_deadlines)
        v.setOnClickPendingIntent(R.id.w_root, OpenTarget.pending(ctx, null, 10))
        v.setOnClickPendingIntent(R.id.w_header, OpenTarget.pending(ctx, OpenTarget.DEADLINES, 11))
        v.setOnClickPendingIntent(R.id.w_study, OpenTarget.pending(ctx, if (d.dueCards > 0) OpenTarget.REVIEW else OpenTarget.PLAN, 12))
        v.setOnClickPendingIntent(R.id.w_search, OpenTarget.pending(ctx, OpenTarget.SEARCH, 13))
        v.setTextViewText(R.id.w_synced, if (d.signedIn) d.syncedText else "")
        if (!d.signedIn) {
            for (i in rows.indices) v.setViewVisibility(rows[i], android.view.View.GONE)
            v.setTextViewText(R.id.w_empty, "Masuk ke Mini LMS untuk melihat tenggat"); v.setViewVisibility(R.id.w_empty, android.view.View.VISIBLE)
            v.setViewVisibility(R.id.w_study, android.view.View.GONE); v.setViewVisibility(R.id.w_more, android.view.View.GONE)
            return v
        }
        v.setViewVisibility(R.id.w_study, android.view.View.VISIBLE)
        for (i in rows.indices) {
            val line = d.lines.getOrNull(i)
            v.setViewVisibility(rows[i], if (line == null) android.view.View.GONE else android.view.View.VISIBLE)
            if (line != null) {
                v.setTextViewText(titles[i], line.title)
                v.setTextViewText(subs[i], line.course + " · " + line.whenText)
                v.setTextColor(subs[i], ctx.getColor(if (line.urgent) R.color.widget_urgent else R.color.widget_sub))
            }
        }
        v.setViewVisibility(R.id.w_empty, if (d.lines.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)
        v.setTextViewText(R.id.w_empty, "Tidak ada tenggat terdekat")
        v.setViewVisibility(R.id.w_more, if (d.moreCount > 0) android.view.View.VISIBLE else android.view.View.GONE)
        v.setTextViewText(R.id.w_more, "+${d.moreCount} tenggat lain")
        v.setTextViewText(R.id.w_study, if (d.dueCards > 0) "Mulai belajar · ${d.dueCards} kartu" else "Rencana belajar")
        return v
    }
}
