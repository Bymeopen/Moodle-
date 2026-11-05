package id.pribadi.minilms

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

private val Z: ZoneId = ZoneOffset.UTC
private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)

private fun item(id: Long, type: String, title: String, content: String = "", due: Long? = null, section: String = "", files: List<FileRef> = emptyList(), attempts: List<QuizAttempt> = emptyList()) =
    Item(id, type, title, section, content, due, if (due != null) 100.0 else null, files, "", attempts)

class SearchTest {
    private val c1 = Course(1, "Akuntansi Dasar - GANJIL 2026-2027", "", listOf(
        item(1, "page", "Pengantar Jurnal", "Jurnal umum mencatat transaksi dengan debit dan kredit.", section = "Minggu 1"),
        item(2, "forum", "Diskusi Neraca", "Mari bahas NERACA saldo dan laporan posisi keuangan."),
        item(3, "assignment", "Tugas Ekuitas", "Hitung ekuitas pemilik.", at(2026, 11, 10)),
        item(4, "quiz", "Kuis 1", "", at(2026, 11, 12), attempts = listOf(QuizAttempt(1, "finished", 80.0, 100.0, null, 1, "Soal 1: apa itu piutang? Jawaban: tagihan"))),
        item(5, "link", "Materi", "", files = listOf(FileRef("Modul-Hutang.pdf", "/", "u", 1, null, 1))),
    ), emptyList())
    private val c2 = Course(2, "Bahasa Indonesia", "", listOf(item(9, "page", "Ejaan", "Kata baku dan tidak baku. Jurnal ilmiah wajib baku.")), emptyList())
    private val courses = listOf(c1, c2)

    @Test fun foldKeepsLengthAndRemovesCaseAndAccents() {
        val s = "Café ÉCOLE Naïve İstanbul"
        assertEquals(s.length, Search.fold(s).length)
        assertEquals("cafe ecole naive istanbul".length, Search.fold(s).length)
        assertTrue(Search.fold(s).startsWith("cafe ecole naive"))
    }

    @Test fun shortOrBlankQueryGivesNothing() {
        assertTrue(Search.search(courses, emptyList(), emptyList(), "").isEmpty())
        assertTrue(Search.search(courses, emptyList(), emptyList(), "a").isEmpty())
        assertTrue(Search.search(courses, emptyList(), emptyList(), "  ").isEmpty())
    }

    @Test fun matchesCaseInsensitiveAndAllTermsRequired() {
        val hit = Search.search(courses, emptyList(), emptyList(), "neraca SALDO")
        assertEquals(listOf(2L), hit.map { it.itemId })
        assertEquals(HitKind.FORUM, hit[0].kind)
        assertTrue(Search.search(courses, emptyList(), emptyList(), "neraca kuis").isEmpty())      // kata kedua tidak ada di item yang sama
    }

    @Test fun titleOutranksBodyAndAcrossCourses() {
        val hits = Search.search(courses, emptyList(), emptyList(), "jurnal")
        assertEquals(listOf(1L, 9L), hits.map { it.itemId })                                          // judul > isi
        assertEquals(setOf(1L, 2L), hits.map { it.courseId }.toSet())
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test fun kindsAndQuizReviewAndFileNames() {
        assertEquals(HitKind.TUGAS, Search.search(courses, emptyList(), emptyList(), "ekuitas")[0].kind)
        val q = Search.search(courses, emptyList(), emptyList(), "piutang")
        assertEquals(HitKind.TES, q[0].kind); assertTrue(q[0].snippet.contains("piutang"))
        assertEquals(listOf(5L), Search.search(courses, emptyList(), emptyList(), "hutang").map { it.itemId })
    }

    @Test fun downloadedFileTextIsSearchedOnlyWhenProvided() {
        val none = Search.search(courses, emptyList(), emptyList(), "amortisasi")
        assertTrue(none.isEmpty())
        val withText = Search.search(courses, emptyList(), emptyList(), "amortisasi") { _, i, f -> if (i.id == 5L && f.name.endsWith(".pdf")) "Bab 3 membahas amortisasi aset tak berwujud" else null }
        assertEquals(1, withText.size); assertEquals(HitKind.FILE, withText[0].kind); assertEquals("Modul-Hutang.pdf", withText[0].title)
        assertTrue(withText[0].snippet.contains("amortisasi"))
    }

    @Test fun notesAndCardsAreSearched() {
        val notes = listOf(Note("n1", 1, 3, "ekuitas pemilik", "ingat rumus modal awal", 1))
        val cards = listOf(Card("k1", 1, "Apa itu debit?", "Sisi kiri akun", 2.5, 0, 0, "2026-01-01"))
        assertEquals(HitKind.CATATAN, Search.search(courses, notes, cards, "modal awal").single().kind)
        val k = Search.search(courses, notes, cards, "sisi kiri").single()
        assertEquals(HitKind.KARTU, k.kind); assertEquals(1L, k.courseId)
    }

    @Test fun snippetIsAroundMatchAndFlattened() {
        val text = "a".repeat(200) + "\n\nkata   kunci\npenting di sini " + "b".repeat(200)
        val sn = Search.snippet(text, Search.fold(text), listOf("kunci"))
        assertTrue(sn.contains("kunci")); assertFalse(sn.contains("\n")); assertTrue(sn.startsWith("…") && sn.endsWith("…")); assertTrue(sn.length < 200)
    }

    @Test fun limitIsApplied() {
        val many = Course(3, "X", "", (1..100L).map { item(it, "page", "Judul $it", "kata kunci sama") }, emptyList())
        assertEquals(10, Search.search(listOf(many), emptyList(), emptyList(), "kunci", limit = 10).size)
    }
}

class NotesTest {
    @Test fun makeTrimsAndRejectsEmpty() {
        assertNull(Notes.make("i", 1, 2, "  ", "\n ", 0))
        val n = Notes.make("i", 1, 2, "  banyak   spasi \n baris ", "  isi  ", 5)!!
        assertEquals("banyak spasi baris", n.quote); assertEquals("isi", n.text)
        assertEquals(Notes.MAX_QUOTE, Notes.make("i", 1, 2, "x".repeat(5000), "", 0)!!.quote.length)
        assertEquals(Notes.MAX_TEXT, Notes.make("i", 1, 2, "", "y".repeat(9000), 0)!!.text.length)
    }

    @Test fun jsonRoundTripAndTolerantOfMissingOptionalFields() {
        val n = Note("a", 1, 2, "kutip", "isi", 99)
        assertEquals(n, JSONArray(listOf(n).toJsonArray().toString()).getJSONObject(0).toNote())
        val minimal = org.json.JSONObject().put("id", "z").put("courseId", 1).put("itemId", 2).put("createdAt", 3)
        assertEquals(Note("z", 1, 2, "", "", 3), minimal.toNote())
    }

    @Test fun rangesFindQuotesIgnoringCaseSpacingAndMergeOverlaps() {
        val content = "Debit adalah sisi kiri.\nKredit adalah sisi\n  kanan akun."
        val r = Notes.ranges(content, listOf("SISI KIRI", "sisi kanan"))
        assertEquals(2, r.size)
        assertEquals("sisi kiri", content.substring(r[0].first, r[0].last + 1))
        assertEquals("sisi\n  kanan", content.substring(r[1].first, r[1].last + 1))
        val merged = Notes.ranges("abcdefghij", listOf("cdef", "efgh"))
        assertEquals(listOf(2..7), merged)
        assertTrue(Notes.ranges("abc", listOf("x")).isEmpty()); assertTrue(Notes.ranges("", listOf("a")).isEmpty()); assertTrue(Notes.ranges("abc", listOf("a")).isEmpty())  // kutipan 1 huruf diabaikan
    }

    @Test fun toDraftNeedsTwoDistinctSides() {
        assertEquals(Draft("kutipan", "catatan"), Notes.toDraft(Note("a", 1, 2, "kutipan", "catatan", 0), "Judul"))
        assertEquals(Draft("Judul", "hanya catatan"), Notes.toDraft(Note("a", 1, 2, "", "hanya catatan", 0), "Judul"))
        assertEquals(Draft("hanya kutipan", "Judul"), Notes.toDraft(Note("a", 1, 2, "hanya kutipan", "", 0), "Judul"))
        assertNull(Notes.toDraft(Note("a", 1, 2, "sama", "sama", 0), "Judul"))
        assertNull(Notes.toDraft(Note("a", 1, 2, "", "", 0), ""))
    }

    @Test fun markdownGroupsByItemInCourseOrder() {
        val c = Course(1, "Kursus", "", listOf(item(10, "page", "Bab Satu"), item(11, "page", "Bab Dua")), emptyList())
        val notes = listOf(Note("1", 1, 11, "", "catatan dua", 2), Note("2", 1, 10, "kutip\nbaris", "catatan satu", 1), Note("3", 99, 10, "", "kursus lain", 0))
        val md = Notes.markdown(c, notes)
        assertTrue(md.startsWith("# Catatan saya — Kursus"))
        assertTrue(md.indexOf("## Bab Satu") < md.indexOf("## Bab Dua"))
        assertTrue(md.contains("> kutip baris")); assertFalse(md.contains("kursus lain"))
        assertEquals("", Notes.markdown(c, emptyList()))
    }
}

class PlannerTest {
    private val now = at(2026, 11, 2, 8, 0)             // Senin pagi

    private fun task(id: Long, due: Long, minutes: Int = 60, title: String = "T$id") = PlanTask(id, title, "Kursus", due, minutes, false)
    private fun plan(tasks: List<PlanTask>, daily: Int = 60, cards: Int = 0, horizon: Int = 7, nowSec: Long = now) = Planner.plan(tasks, nowSec, daily, cards, horizon, Z)

    @Test fun estimateByType() {
        assertEquals(45, Planner.estimate(item(1, "quiz", "q")))
        assertEquals(60, Planner.estimate(item(2, "assignment", "a", "pendek")))
        assertEquals(180, Planner.estimate(item(3, "assignment", "a", "x".repeat(10_000))))
        assertEquals(0, Planner.estimate(item(4, "assignment", "a", "x".repeat(500))) % 15)
    }

    @Test fun tasksOfFiltersDoneFinishedQuizAndOld() {
        val finished = listOf(QuizAttempt(1, "finished", 90.0, 100.0, null, 1))
        val c = Course(1, "Kursus - GANJIL 2026-2027", "", listOf(
            item(1, "assignment", "Aktif", due = now + 86400), item(2, "assignment", "Selesai", due = now + 86400),
            item(3, "quiz", "Kuis sudah", due = now + 86400, attempts = finished), item(4, "assignment", "Lama", due = now - 30 * 86400L),
            item(5, "assignment", "Terlambat baru", due = now - 86400), item(6, "page", "Halaman"), item(7, "assignment", "Tanpa tenggat")), emptyList())
        val t = Planner.tasksOf(listOf(c), setOf(2), now)
        assertEquals(listOf(5L, 1L), t.map { it.itemId })
        assertEquals("Kursus", t[0].course)
    }

    @Test fun fillsDayWithinCapacityInDueOrder() {
        val p = plan(listOf(task(2, at(2026, 11, 5), 60), task(1, at(2026, 11, 3), 60)), daily = 60)
        val today = p.days[0]
        assertTrue(today.total <= 60)
        assertEquals(1L, today.sessions.first().itemId)                    // tenggat paling dekat lebih dulu
        p.days.forEach { assertTrue("kapasitas harian terlampaui: ${it.total}", it.total <= 60) }
        assertTrue(p.atRisk.isEmpty())
        val allocated = p.days.flatMap { it.sessions }.groupBy { it.itemId }.mapValues { (_, v) -> v.sumOf { it.minutes } }
        assertEquals(60, allocated[1L]); assertEquals(60, allocated[2L])
    }

    @Test fun taskDoesNotStartTooEarly() {
        val p = plan(listOf(task(1, at(2026, 11, 8), 60)), daily = 60)       // jatuh tempo Minggu; lead = ceil(60/30)+1 = 3 hari
        assertTrue(p.days[0].sessions.isEmpty() && p.days[1].sessions.isEmpty())
        assertEquals(60, p.days.flatMap { it.sessions }.sumOf { it.minutes })
        val first = p.days.first { it.sessions.isNotEmpty() }.date
        assertFalse(first.isBefore(LocalDate.of(2026, 11, 5)))
    }

    @Test fun overloadedTaskIsAtRiskNotSilentlyDropped() {
        val p = plan(listOf(task(1, at(2026, 11, 3, 23, 0), 240)), daily = 60)       // 4 jam, tapi hanya ~2 hari x 60
        assertEquals(listOf(1L), p.atRisk.map { it.itemId })
        assertTrue(p.days.flatMap { it.sessions }.sumOf { it.minutes } in 1..120)
    }

    @Test fun tasksBeyondHorizonAreNotRiskyOrScheduledYet() {
        val p = plan(listOf(task(1, at(2026, 12, 25), 60)), daily = 60)
        assertTrue(p.atRisk.isEmpty()); assertEquals(0, p.days.sumOf { it.total })
    }

    @Test fun overdueIsReportedSeparately() {
        val p = plan(listOf(task(1, now - 3600), task(2, now + 86400)), daily = 120)
        assertEquals(listOf(1L), p.overdue.map { it.itemId })
        assertTrue(p.days.flatMap { it.sessions }.none { it.itemId == 1L })
    }

    @Test fun cardsTakeTodaysCapacityAndAreCapped() {
        assertEquals(0, plan(emptyList(), cards = 0).days[0].cardMinutes)
        assertEquals(5, plan(emptyList(), cards = 10).days[0].cardMinutes)
        assertEquals(20, plan(emptyList(), cards = 500).days[0].cardMinutes)
        val p = plan(listOf(task(1, at(2026, 11, 2, 23, 0), 90)), daily = 60, cards = 500)       // 60 - 20 kartu = 40 untuk tugas
        assertEquals(40, p.days[0].sessions.sumOf { it.minutes }); assertEquals(60, p.days[0].total)
    }

    @Test fun lateInTheDayTodayHasLessCapacity() {
        val night = at(2026, 11, 2, 22, 30)
        val p = plan(listOf(task(1, at(2026, 11, 2, 23, 59), 60)), daily = 120, nowSec = night)
        assertEquals(30, p.days[0].sessions.sumOf { it.minutes })         // 22.30 -> 23.00 tinggal 30 menit
        assertEquals(listOf(1L), p.atRisk.map { it.itemId })
        assertEquals(0, plan(emptyList(), nowSec = at(2026, 11, 2, 23, 30), cards = 10).days[0].sessions.size)
    }

    @Test fun capacityClampedAndHorizonRespected() {
        assertEquals(3, plan(emptyList(), horizon = 3).days.size)
        val p = plan(listOf(task(1, at(2026, 11, 3), 30)), daily = 1)           // 1 menit -> dipaksa minimal 15
        assertTrue(p.days.all { it.total <= 15 })
    }

    @Test fun todayMessage() {
        assertNull(Planner.todayMessage(plan(emptyList()), 0))
        val p = plan(listOf(task(1, at(2026, 11, 2, 23, 0), 45, "Esai"), task(2, at(2026, 11, 2, 23, 30), 45, "Kuis"), task(3, at(2026, 11, 2, 23, 40), 45, "Laporan")), daily = 180, cards = 6)
        val (title, body) = Planner.todayMessage(p, 6)!!
        assertTrue(title.startsWith("Rencana belajar hari ini · ")); assertTrue(body.contains("Esai")); assertTrue(body.contains("6 kartu hafalan")); assertTrue(body.contains("+1 tugas lain"))
        assertNull(Planner.todayMessage(StudyPlan(emptyList(), emptyList(), emptyList(), 0), 3))
        assertTrue(Planner.todayMessage(plan(listOf(task(9, now - 60))), 0)!!.second.contains("1 tugas terlambat"))
    }

    @Test fun formatAndSchedulingHelpers() {
        assertEquals("45 mnt", Planner.fmtMinutes(45)); assertEquals("1 j", Planner.fmtMinutes(60)); assertEquals("1 j 30 mnt", Planner.fmtMinutes(90))
        assertEquals(11 * 3600L, Planner.secondsUntilHour(19, at(2026, 11, 2, 8), Z))
        assertEquals(23 * 3600L, Planner.secondsUntilHour(8, at(2026, 11, 2, 9), Z))          // jam sudah lewat -> besok
        assertEquals(24 * 3600L, Planner.secondsUntilHour(8, at(2026, 11, 2, 8), Z))          // tepat jam itu -> besok, bukan 0
    }
}

class WidgetModelTest {
    private val now = at(2026, 11, 2, 8, 0)
    private val today = LocalDate.of(2026, 11, 2)
    private val c = Course(1, "Akuntansi - GANJIL 2026-2027", "", listOf(
        item(1, "assignment", "Tugas A", due = now + 3600), item(2, "assignment", "Tugas B", due = now + 3 * 86400), item(3, "assignment", "Tugas C", due = now + 5 * 86400),
        item(4, "assignment", "Tugas D", due = now + 6 * 86400), item(5, "assignment", "Sudah", due = now + 100), item(6, "assignment", "Lewat", due = now - 100),
        item(7, "quiz", "Kuis selesai", due = now + 200, attempts = listOf(QuizAttempt(1, "finished", 1.0, 1.0, null, 1)))), emptyList())
    private val snap = Snapshot(now - 600, listOf(c), emptyList())

    @Test fun signedOutShowsNothing() {
        val d = WidgetModel.build(snap, emptySet(), emptyList(), now, signedIn = false, today = today)
        assertFalse(d.signedIn); assertTrue(d.lines.isEmpty())
    }

    @Test fun linesAreNearestOpenDeadlinesWithOverflowCount() {
        val d = WidgetModel.build(snap, setOf(5), emptyList(), now, true, today)
        assertEquals(listOf("Tugas A", "Tugas B", "Tugas C"), d.lines.map { it.title })
        assertEquals(1, d.moreCount)                                                   // Tugas D
        assertTrue(d.lines[0].urgent); assertFalse(d.lines[1].urgent)
        assertEquals("Akuntansi", d.lines[0].course); assertEquals("1 jam lagi", d.lines[0].whenText)
        assertEquals("Sinkron 10 menit lalu", d.syncedText)
    }

    @Test fun dueCardsCounted() {
        val cards = listOf(Card("a", 1, "f", "b", 2.5, 0, 0, "2026-11-01"), Card("b", 1, "f", "b", 2.5, 0, 0, "2026-11-02"), Card("c", 1, "f", "b", 2.5, 1, 1, "2026-11-03"))
        assertEquals(2, WidgetModel.build(snap, emptySet(), cards, now, true, today).dueCards)
    }

    @Test fun noSnapshotIsHandled() {
        val d = WidgetModel.build(null, emptySet(), emptyList(), now, true, today)
        assertTrue(d.signedIn); assertTrue(d.lines.isEmpty()); assertEquals("Belum sinkron", d.syncedText)
    }
}
