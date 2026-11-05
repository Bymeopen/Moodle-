package id.pribadi.minilms

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Satu tugas/tes yang perlu dikerjakan, dengan perkiraan waktu kerja (menit). */
data class PlanTask(val itemId: Long, val title: String, val course: String, val dueAt: Long, val minutes: Int, val isQuiz: Boolean)
data class PlanSession(val itemId: Long, val title: String, val course: String, val minutes: Int, val dueAt: Long)
data class PlanDay(val date: LocalDate, val sessions: List<PlanSession>, val cardMinutes: Int) {
    val total get() = sessions.sumOf { it.minutes } + cardMinutes
}
/** [atRisk] = tugas yang tidak muat dalam kapasitas harian sebelum tenggatnya; [overdue] = sudah lewat dan belum ditandai selesai. */
data class StudyPlan(val days: List<PlanDay>, val atRisk: List<PlanTask>, val overdue: List<PlanTask>, val horizon: Int)

/**
 * Perencana belajar lokal: menyebar waktu kerja tugas ke hari-hari sebelum tenggat (yang paling dekat tenggatnya lebih dulu),
 * dalam batas menit belajar per hari. Tidak mengubah data Moodle dan murni (mudah dites).
 */
object Planner {
    const val DEFAULT_DAILY = 60
    const val DEFAULT_HOUR = 19
    val DAILY_CHOICES = listOf(30, 60, 90, 120)
    private const val OVERDUE_DAYS = 7

    /** Perkiraan kasar: tes 45 menit; tugas 60 menit ditambah panjang instruksi (maks 180). Cukup untuk membagi hari, bukan janji. */
    fun estimate(i: Item): Int = when (i.type) {
        "quiz" -> 45
        else -> (60 + i.content.length / 25).coerceAtMost(180) / 15 * 15
    }

    /** Tugas/tes yang belum selesai (bukan ditandai selesai, bukan tes yang sudah ada percobaan selesai) dan bertenggat. */
    fun tasksOf(courses: List<Course>, done: Set<Long>, nowSec: Long): List<PlanTask> = courses.flatMap { c ->
        c.items.filter { it.isTask && it.dueAt != null && it.id !in done && !(it.type == "quiz" && it.attempts.any { a -> a.state == "finished" }) }
            .filter { it.dueAt!! >= nowSec - OVERDUE_DAYS * 86400L }
            .map { PlanTask(it.id, it.title, Logic.splitTerm(c.name).first, it.dueAt!!, estimate(it), it.type == "quiz") }
    }.sortedBy { it.dueAt }

    private fun cardMinutes(dueCards: Int) = if (dueCards <= 0) 0 else minOf(20, (dueCards + 1) / 2)

    /**
     * Susun rencana [horizon] hari mulai hari ini. Tiap tugas baru muncul beberapa hari sebelum tenggat (supaya tidak semuanya
     * menumpuk di hari pertama), lalu diisi per hari berurutan tenggat. Kapasitas hari ini dipangkas sesuai jam yang tersisa.
     */
    fun plan(tasks: List<PlanTask>, nowSec: Long, dailyMinutes: Int = DEFAULT_DAILY, dueCards: Int = 0, horizon: Int = 7, zone: ZoneId = ZoneId.systemDefault()): StudyPlan {
        val daily = dailyMinutes.coerceIn(15, 480)
        val now = Instant.ofEpochSecond(nowSec).atZone(zone)
        val today = now.toLocalDate()
        val overdue = tasks.filter { it.dueAt < nowSec }
        val open = tasks.filter { it.dueAt >= nowSec }.sortedBy { it.dueAt }

        val minutesLeftToday = maxOf(0, (23 * 60) - (now.hour * 60 + now.minute))
        val cardsToday = cardMinutes(dueCards)
        val cap = IntArray(horizon) { if (it == 0) minOf(daily, minutesLeftToday) else daily }
        cap[0] = maxOf(0, cap[0] - cardsToday)

        val remaining = open.associate { it.itemId to it.minutes }.toMutableMap()
        val perDay = Array(horizon) { mutableListOf<PlanSession>() }
        for (d in 0 until horizon) {
            val date = today.plusDays(d.toLong())
            var left = cap[d]
            for (t in open) {                                   // urut tenggat: yang paling dekat mendapat jatah lebih dulu
                if (left <= 0) break
                val rem = remaining[t.itemId] ?: 0
                if (rem <= 0) continue
                val dueDate = Instant.ofEpochSecond(t.dueAt).atZone(zone).toLocalDate()
                if (dueDate.isBefore(date)) continue
                val lead = maxOf(2, Math.ceil(t.minutes / (daily / 2.0)).toInt() + 1)         // hari kerja yang dialokasikan sebelum tenggat
                if (date.isBefore(dueDate.minusDays(lead.toLong()))) continue
                val take = minOf(rem, left)
                perDay[d] += PlanSession(t.itemId, t.title, t.course, take, t.dueAt)
                remaining[t.itemId] = rem - take; left -= take
            }
        }
        val days = (0 until horizon).map { d -> PlanDay(today.plusDays(d.toLong()), perDay[d].sortedBy { it.dueAt }, if (d == 0) cardsToday else 0) }
        // berisiko: masih ada sisa menit padahal tenggatnya jatuh di dalam jendela rencana
        val last = today.plusDays(horizon.toLong() - 1)
        val risk = open.filter { t ->
            (remaining[t.itemId] ?: 0) > 0 && !Instant.ofEpochSecond(t.dueAt).atZone(zone).toLocalDate().isAfter(last)
        }
        return StudyPlan(days, risk, overdue, horizon)
    }

    /** "1 j 30 mnt", "45 mnt". */
    fun fmtMinutes(m: Int) = if (m >= 60) "${m / 60} j" + if (m % 60 > 0) " ${m % 60} mnt" else "" else "$m mnt"

    /** Teks notifikasi harian; null bila hari ini tidak ada yang perlu diingatkan. Pair = (judul, isi). */
    fun todayMessage(plan: StudyPlan, dueCards: Int): Pair<String, String>? {
        val today = plan.days.firstOrNull() ?: return null
        val parts = mutableListOf<String>()
        today.sessions.take(2).forEach { parts += "${fmtMinutes(it.minutes)} ${it.title}" }
        if (today.sessions.size > 2) parts += "+${today.sessions.size - 2} tugas lain"
        if (dueCards > 0) parts += "$dueCards kartu hafalan"
        if (plan.overdue.isNotEmpty()) parts += "${plan.overdue.size} tugas terlambat"
        if (parts.isEmpty()) return null
        val title = if (today.total > 0) "Rencana belajar hari ini · ${fmtMinutes(today.total)}" else "Rencana belajar hari ini"
        return title to parts.joinToString(" · ")
    }

    /** Detik sampai [hour]:00 berikutnya (hari ini bila belum lewat, selain itu besok); untuk menunda pekerjaan harian. */
    fun secondsUntilHour(hour: Int, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochSecond(nowSec).atZone(zone)
        var t = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0).atZone(zone)
        if (!t.isAfter(now)) t = now.toLocalDate().plusDays(1).atTime(hour.coerceIn(0, 23), 0).atZone(zone)
        return t.toEpochSecond() - nowSec
    }
}
