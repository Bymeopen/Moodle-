package id.pribadi.minilms

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class LogicTest {
    @get:Rule val tmp = TemporaryFolder()
    private val z: ZoneId = ZoneOffset.UTC
    private fun at(day: Int, hour: Int = 12) = LocalDate.of(2026, 10, day).atTime(hour, 0).toEpochSecond(ZoneOffset.UTC)

    @Test fun deadlineBuckets() {
        val now = at(23, 10)
        assertEquals(Bucket.OVERDUE, Logic.bucket(at(23, 9), now, z)); assertEquals(Bucket.TODAY, Logic.bucket(at(23, 23), now, z))
        assertEquals(Bucket.TOMORROW, Logic.bucket(at(24, 1), now, z)); assertEquals(Bucket.WEEK, Logic.bucket(at(30, 1), now, z))
        assertEquals(Bucket.LATER, Logic.bucket(at(31, 1), now, z))
    }

    @Test fun relativeAndUrgency() {
        val now = 1_000_000L
        assertEquals("30 menit lagi", Logic.relative(now + 1800, now)); assertEquals("5 jam lagi", Logic.relative(now + 5 * 3600 + 10, now))
        assertEquals("2 hari lagi", Logic.relative(now + 2 * 86400 + 5, now)); assertEquals("Terlambat 3 hari", Logic.relative(now - 3 * 86400 - 5, now))
        assertEquals(Urgency.HIGH, Logic.urgency(now + 3600, now)); assertEquals(Urgency.HIGH, Logic.urgency(now - 10, now))
        assertEquals(Urgency.MEDIUM, Logic.urgency(now + 2 * 86400, now)); assertEquals(Urgency.LOW, Logic.urgency(now + 5 * 86400, now))
    }

    @Test fun namesAndTerms() {
        assertEquals("Muhammad", Logic.firstName("MUHAMMAD HARIS ILYASA 20260500714")); assertEquals("", Logic.firstName("12345"))
        assertEquals("AI", Logic.initials("Al-Islam dan Kemuhammadiyahan")); assertEquals("PA", Logic.initials("Pengantar Akuntansi")); assertEquals("BI", Logic.initials("Bahasa Indonesia"))
        assertEquals("Selamat pagi", Logic.greeting(8)); assertEquals("Selamat malam", Logic.greeting(22))
        assertEquals("Pengantar Akuntansi" to "GANJIL 2026/2027", Logic.splitTerm("Pengantar Akuntansi - GANJIL 2026/2027"))
        assertEquals("Al-Islam dan Kemuhammadiyahan 1 - Fabira" to "GANJIL 2026-2027", Logic.splitTerm("Al-Islam dan Kemuhammadiyahan 1 - Fabira - GANJIL 2026-2027"))
        assertEquals("Statistika" to null, Logic.splitTerm("Statistika"))
        assertEquals(Logic.paletteIndex(7L, 6), Logic.paletteIndex(7L, 6)); assertEquals(1, Logic.paletteIndex(7L, 6)); assertNotEquals(Logic.paletteIndex(11L, 6), Logic.paletteIndex(12L, 6))
    }

    @Test fun gradeSummaryHidesUngradedAndUsesTotal() {
        val c = Course(1, "K", "", emptyList(), listOf(Grade("Forum whole forum", null, 100.0), Grade("Presensi 01", 100.0, 100.0), Grade("Kuis 02", 90.0, 100.0), Grade("Kemandirian 01", null, 100.0), Grade("Total kursus", 80.0, 100.0)))
        val s = Logic.gradeSummary(c)
        assertEquals(80.0, s.percent!!, 0.001); assertEquals(2, s.graded); assertEquals(4, s.total); assertEquals(listOf("Presensi 01", "Kuis 02"), s.rows.map { it.name })
        // tanpa Total kursus: rata-rata persen item
        assertEquals(95.0, Logic.gradeSummary(c.copy(grades = c.grades.dropLast(1))).percent!!, 0.001)
        assertNull(Logic.gradeSummary(c.copy(grades = listOf(Grade("x", null, 1.0)))).percent)
    }

    @Test fun fileKindsAndFormats() {
        assertEquals(FileKind.PDF, FileKind.of("a.PDF", null)); assertEquals(FileKind.PPT, FileKind.of("a.pptx", null)); assertEquals(FileKind.IMG, FileKind.of("x", "image/png")); assertEquals(FileKind.OTHER, FileKind.of("a.bin", null))
        assertEquals("1.5 MB", Logic.fmtSize(1_572_864)); assertEquals("12 KB", Logic.fmtSize(12_345)); assertEquals("baru saja", Logic.ago(100, 130)); assertEquals("2 jam lalu", Logic.ago(0, 7300))
    }

    @Test fun usageCostAndLabel() {
        val u = Usage(input = 1000, cacheRead = 9000, cacheWrite = 0, output = 500)
        // opus-5-5: 1000*4 + 9000*0.2 + 500*20 = 4000+1800+10000 = 15800 / 1e6
        assertEquals(0.0158, u.costUsd("claude-opus-5-5"), 1e-9)
        assertTrue(u.label("claude-opus-5-5").contains("cache 9.0K")); assertTrue(u.label("claude-opus-5-5").contains("≈ $0.02"))
        assertTrue(Usage(output = 10).costUsd("claude-haiku-5-5") < Usage(output = 10).costUsd("claude-sonnet-5-5"))
        assertEquals(Usage(3, 2, 1, 4), Usage(1, 1, 0, 1) + Usage(2, 1, 1, 3))
    }

    @Test fun markdownBlocks() {
        val b = Markdown.parse("# Judul\nPara **tebal** dan *miring* `kode`\n\n- satu\n  - bersarang\n1. angka\n\n```kotlin\nval x = 1\n```\n> kutip\n\n| A | B |\n|---|---|\n| 1 | 2 |\n---")
        assertEquals(listOf(MdBlock.Heading(1, listOf(Span("Judul")))), b.take(1))
        val para = b[1] as MdBlock.Para
        assertEquals(listOf(Span("Para "), Span("tebal", bold = true), Span(" dan "), Span("miring", italic = true), Span(" "), Span("kode", code = true)), para.spans)
        assertEquals(MdBlock.Item(0, "•", listOf(Span("satu"))), b[2]); assertEquals(1, (b[3] as MdBlock.Item).depth); assertEquals("1.", (b[4] as MdBlock.Item).marker)
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), b[5]); assertTrue(b[6] is MdBlock.Quote)
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), (b[7] as MdBlock.Table).rows); assertEquals(MdBlock.Rule, b[8])
    }

    @Test fun markdownIsTolerantWhileStreaming() {
        assertEquals(listOf(Span("a **belum tutup")), Markdown.inline("a **belum tutup"))
        assertEquals(MdBlock.Code("", "tanpa penutup"), Markdown.parse("```\ntanpa penutup").single())
        assertEquals(listOf(Span("snake_case_name")), Markdown.inline("snake_case_name"))                 // underscore di dalam kata bukan miring
        assertEquals(listOf(Span("situs", link = "https://x.id")), Markdown.inline("[situs](https://x.id)"))
        assertEquals(listOf(Span("2 * 3 * 4")), Markdown.inline("2 * 3 * 4"))
    }

    @Test fun chatStoreRoundTrip() {
        val s = ChatStore(tmp.newFolder("chats"))
        assertEquals(emptyList<ChatMsg>(), s.load("1"))
        s.save("1", listOf(ChatMsg("user", "hai", docs = listOf(Doc("a.pdf", "QQ=="))), ChatMsg("assistant", "halo", usage = Usage(1, 1, 1, 1)), ChatMsg("assistant", "")))
        assertEquals(listOf(ChatMsg("user", "hai"), ChatMsg("assistant", "halo")), s.load("1"))   // lampiran, usage, dan pesan kosong tidak disimpan
        s.save("1", emptyList()); assertEquals(emptyList<ChatMsg>(), s.load("1"))
        s.save("../x", listOf(ChatMsg("user", "a"))); assertEquals(1, s.load("../x").size)             // kunci disanitasi
    }

    @Test fun srsPreviewLabels() {
        val c = Card("1", 1, "f", "b", 2.5, 0, 0, "2026-01-01"); val d = LocalDate.of(2026, 1, 1)
        assertEquals("sebentar", Srs.preview(c, "again", d)); assertEquals("1 hari", Srs.preview(c, "good", d))
        assertEquals("6 hari", Srs.preview(c.copy(reps = 1, interval = 1), "good", d)); assertEquals("2 bln", Srs.intervalLabel(65))
    }

    @Test fun streamingUsageAndCacheControl() {
        val seen = mutableListOf<Usage>()
        ClaudeClient.parseSse("""data: {"type":"message_start","message":{"usage":{"input_tokens":12,"cache_read_input_tokens":3000,"cache_creation_input_tokens":0,"output_tokens":1}}}""", usage = { seen += it })
        ClaudeClient.parseSse("""data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":240}}""", usage = { seen += it })
        assertEquals(Usage(12, 3000, 0, 1), seen[0]); assertEquals(240, seen[1].output)
        assertTrue(ClaudeClient.body("claude-opus-5-5", "s", emptyList(), true, cache = true).getJSONObject("cache_control").getString("type") == "ephemeral")
        assertFalse(ClaudeClient.body("claude-opus-5-5", "s", emptyList(), true).has("cache_control"))
    }
}
