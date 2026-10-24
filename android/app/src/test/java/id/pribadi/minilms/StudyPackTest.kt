package id.pribadi.minilms

import org.junit.Assert.*
import org.junit.Test

class StudyPackTest {
    private val quiz = Item(6, "quiz", "Kuis 1", "Minggu 1", "info tes", 1_800_000_000L, 100.0, emptyList(), "u", listOf(QuizAttempt(1, "finished", 80.0, 100.0, 5, 9, "Soal 1\n2+2=4")))
    private val page = Item(1, "page", "Pengantar", "Minggu 1", "Isi halaman pengantar", null, null, emptyList())
    private val slide = Item(2, "link", "Slide", "Minggu 2", "", null, null, listOf(FileRef("a.docx", "/", "https://x/a.docx", 10, null, 1), FileRef("b.pdf", "/", "https://x/b.pdf", 10, null, 1)))
    private val yt = Item(3, "link", "Video 1", "Minggu 2", "https://youtu.be/abc", null, null, emptyList(), "https://lms/mod/url/view.php?id=3")
    private val moodlePage = Item(4, "link", "Presensi", "Minggu 2", "https://lms/mod/attendance/view.php?id=4", null, null, emptyList(), "https://lms/mod/attendance/view.php?id=4")
    private val forum = Item(5, "forum", "Forum", "Minggu 2", "• Diskusi inflasi", null, null, emptyList(), "u")
    private val course = Course(11, "Ekonomi: Dasar/1", "Dasar ekonomi", listOf(page, quiz, slide, yt, moodlePage, forum), emptyList())

    @Test fun textsGroupBySectionAndIncludeReviewFilesAndForum() {
        val t = StudyPack.texts(course, { i, f -> if (f.name == "a.docx") "isi dokumen" else null })
        assertEquals(1, t.size)
        val c = t[0].content
        assertTrue(c.startsWith("# Ekonomi: Dasar/1 — bagian 1 dari 1"))
        assertTrue(c.contains("Dasar ekonomi") && c.contains("## Minggu 1") && c.contains("Isi halaman pengantar"))
        assertTrue(c.contains("Soal dan jawaban percobaan 1") && c.contains("2+2=4") && c.contains("Tenggat:"))
        assertTrue(c.contains("Isi file a.docx") && c.contains("isi dokumen") && c.contains("• Diskusi inflasi"))
        assertFalse(c.contains("youtu.be"))                                  // tautan murni masuk berkas tautan
        assertFalse(c.contains("Presensi"))
        assertFalse(t[0].name.contains('/') || t[0].name.contains(':'))      // nama berkas aman
    }

    @Test fun longContentIsSplitIntoBoundedParts() {
        val big = (1..40).map { Item(it.toLong(), "page", "Bab $it", "Bagian ${it % 3}", "x".repeat(900), null, null, emptyList()) }
        val t = StudyPack.texts(Course(1, "Besar", "", big, emptyList()), max = 5000)
        assertTrue(t.size >= 7)
        assertTrue(t.all { it.content.length <= 5000 + 200 })                // + judul bagian
        assertEquals(40, t.sumOf { Regex("### Bab").findAll(it.content).count() })      // tidak ada item hilang
        val huge = Item(1, "page", "Raksasa", "S", "y".repeat(12_000), null, null, emptyList())
        val parts = StudyPack.texts(Course(1, "H", "", listOf(huge), emptyList()), max = 5000)
        assertEquals(12_000, parts.sumOf { p -> p.content.count { it == 'y' } })        // blok melebihi batas dipotong tanpa kehilangan isi
    }

    @Test fun linksCollectExternalOnly() {
        val l = StudyPack.links(course)!!
        assertTrue(l.content.contains("Video 1: https://youtu.be/abc") && l.content.contains("## Minggu 2"))
        assertFalse(l.content.contains("attendance"))                        // halaman Moodle sendiri bukan tautan luar
        assertNull(StudyPack.links(Course(1, "K", "", listOf(page), emptyList())))
    }

    @Test fun emptyCourseHasNoTexts() { assertTrue(StudyPack.texts(Course(1, "K", "", emptyList(), emptyList())).isEmpty()) }

    @Test fun sourceLimitPrefersTextThenLinksThenPdfs() {
        val (sent, omitted) = StudyPack.limit(listOf("t1", "t2"), listOf("l"), (1..60).map { "p$it" })
        assertEquals(50, sent.size); assertEquals(13, omitted)
        assertEquals(listOf("t1", "t2", "l", "p1"), sent.take(4))
        assertEquals(0, StudyPack.limit(listOf("a"), emptyList(), emptyList<String>()).second)
    }
}

class SpeechTest {
    @Test fun cleanStripsMarkdownAndAgentTrace() {
        val t = Speech.clean("*› Memeriksa tenggat*\n\n## Judul\n\nIni **tebal** dan `kode` serta [tautan](https://x.id).\n\n- poin satu\n> kutipan\n```\nkode blok\n```\n| a | b |")
        assertFalse(t.contains("Memeriksa")); assertFalse(t.contains("*") || t.contains("#") || t.contains("`") || t.contains("https") || t.contains("kode blok"))
        assertTrue(t.contains("Judul") && t.contains("Ini tebal dan kode serta tautan.") && t.contains("poin satu") && t.contains("kutipan"))
    }

    @Test fun chunksStayUnderLimitAndKeepAllText() {
        val text = (1..400).joinToString(" ") { "Kalimat nomor $it berisi beberapa kata." }
        val c = Speech.chunks(text, 500)
        assertTrue(c.all { it.length <= 500 } && c.size > 5)
        assertEquals(text.replace(Regex("\\s+"), ""), c.joinToString("").replace(Regex("\\s+"), ""))
        assertTrue(Speech.chunks("   ").isEmpty()); assertEquals(1, Speech.chunks("pendek").size)
        assertEquals(10, Speech.chunks("x".repeat(10_000), 1000).size)       // tanpa spasi: tetap terpotong
    }
}
