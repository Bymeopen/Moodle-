package id.pribadi.minilms

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Perpustakaan gratis, forum, tinjauan tes, dan kebijakan WebView. */
class LibraryTest {
    private val base = "https://lms.kampus.ac.id"
    private val asked = mutableListOf<String>()

    @Before fun setup() {
        asked.clear()
        MoodleApi.htmlToText = { it.replace(Regex("<[^>]+>"), "").trim() }
        LibraryApi.http = HttpGet { u -> asked += u; when {
            u.contains("advancedsearch") -> """{"response":{"docs":[{"identifier":"buku-1","title":"Dasar Ekonomi","creator":["A","B"],"year":"1999"},{"identifier":"../bad id","title":"x"}]}}"""
            u.contains("doaj.org") -> """{"results":[{"id":"j1","bibjson":{"title":"Jurnal A","year":"2020","author":[{"name":"Siti"}],"journal":{"title":"JAM"},"link":[{"type":"fulltext","url":"https://jam.ac.id/a.pdf"}]}},
                {"id":"j2","bibjson":{"title":"Tanpa link"}},{"id":"j3","bibjson":{"title":"Aneh","link":[{"type":"fulltext","url":"javascript:alert(1)"}]}},
                {"id":"j4","bibjson":{"title":"Lokal","link":[{"type":"fulltext","url":"http://localhost/ojs/article/view/1"}]}}]}"""
            u.contains("/metadata/") -> """{"files":[{"name":"a_text.pdf","size":"900000"},{"name":"kecil.pdf","size":"500"},{"name":"sub/x.pdf","size":"20000"},{"name":"b.epub","size":"1"}]}"""
            else -> "{}" } }
    }

    @Test fun archiveParsesAndRejectsUnsafeIds() {
        val r = LibraryApi.search(LibSource.BOOKS, "ekonomi")
        assertEquals(listOf("buku-1"), r.map { it.id })
        assertEquals("A, B", r[0].author)
        assertTrue(asked[0].contains("access-restricted-item%3Afalse") || asked[0].contains("access-restricted-item:false"))
    }

    @Test fun doajKeepsOnlyHttpFulltext() {
        val r = LibraryApi.search(LibSource.JOURNALS, "akuntansi")
        assertEquals(listOf("j1"), r.map { it.id }); assertEquals("Siti", r[0].author); assertEquals("JAM", r[0].note)
    }

    @Test fun blankQueryDoesNotHitNetwork() { assertTrue(LibraryApi.search(LibSource.BOOKS, "  ").isEmpty()); assertTrue(asked.isEmpty()) }

    @Test fun pdfPickerSkipsTinySubdirAndNonPdf() {
        assertEquals("a_text.pdf" to 900000L, LibraryApi.pdfFor("buku-1"))
        assertEquals("https://archive.org/download/buku-1/a%20b.pdf", LibraryApi.downloadUrl("buku-1", "a b.pdf"))
    }

    @Test fun publicHttpOnlyTrustedHostsAndHttps() {
        assertTrue(PublicHttp.publicHost("ia800.us.archive.org")); assertTrue(PublicHttp.publicHost("jurnal.univ.ac.id"))
        listOf("localhost", "127.0.0.1", "10.0.0.5", "192.168.1.1", "[::1]", "intranet", "printer.local", "x.internal", "169.254.169.254").forEach { assertFalse(it, PublicHttp.publicHost(it)) }
        assertThrows(ApiError::class.java) { PublicHttp.getText("http://archive.org/x") }             // bukan https
        assertThrows(ApiError::class.java) { PublicHttp.getText("https://127.0.0.1/x") }
        assertThrows(ApiError::class.java) { PublicHttp.getText("https://localhost/x") }
    }

    @Test fun storeRoundTripAndMissingFileDropped() {
        val dir = kotlin.io.path.createTempDirectory("lib").toFile()
        val st = LibraryStore(dir)
        assertTrue(st.list().isEmpty())
        File(dir, "a.pdf").writeText("x"); st.add(LibSaved("a", "Judul", "P", "a.pdf", 1))
        st.add(LibSaved("b", "Hilang", "", "b.pdf", 1))             // file tidak ada
        assertEquals(listOf("a"), st.list().map { it.id })
        st.remove("a"); assertTrue(st.list().isEmpty()); assertFalse(File(dir, "a.pdf").exists())
    }

    // ---- forum & tinjauan tes ----
    private val site = Site(base, "TOK", 7, "Budi", "K")

    @Test fun forumTextIncludesRepliesAndFailsToNull() {
        MoodleApi.transport = Transport { _, f -> when (f["wsfunction"]) {
            "mod_forum_get_forum_discussions" -> """{"discussions":[{"discussion":5,"name":"Tanya","userfullname":"Dosen","message":"<p>Halo</p>","numreplies":1}]}"""
            "mod_forum_get_discussion_posts" -> """{"posts":[{"parentid":0,"message":"Halo","author":{"fullname":"Dosen"}},{"parentid":1,"message":"<b>Siap</b>","author":{"fullname":"Budi"}}]}"""
            else -> """{"exception":"x","message":"mati"}""" } }
        val t = MoodleApi.forumText(site, 3)!!
        assertTrue(t.contains("• Tanya — Dosen") && t.contains("↳ Budi: Siap"))
        assertFalse(t.contains("↳ Dosen"))                           // posting pertama bukan balasan
        MoodleApi.transport = Transport { _, _ -> """{"exception":"x","message":"mati"}""" }
        assertNull(MoodleApi.forumText(site, 3))
    }

    @Test fun quizReviewTextAndBlankOnError() {
        MoodleApi.transport = Transport { _, f -> if (f["wsfunction"] == "mod_quiz_get_attempt_review") """{"questions":[{"number":1,"html":"<p>2+2?</p><i>Jawaban: 4</i>"}]}""" else "{}" }
        assertEquals("Soal 1\n2+2?Jawaban: 4", MoodleApi.quizReview(site, 9))
        MoodleApi.transport = Transport { _, _ -> """{"exception":"x","message":"tinjauan dimatikan"}""" }
        assertEquals("", MoodleApi.quizReview(site, 9))
    }

    @Test fun attemptReviewSurvivesJson() {
        val a = QuizAttempt(1, "finished", 80.0, 100.0, 5L, 9L, "Soal 1\nx")
        val i = Item(1, "quiz", "K", "", "", null, 100.0, emptyList(), "u", listOf(a))
        assertEquals(a, i.toJson().toItem().attempts.single())
    }

    @Test fun webPolicy() {
        val h = "lms.kampus.ac.id"
        assertTrue(WebPolicy.allowedInApp(h, "https://lms.kampus.ac.id/mod/quiz/view.php?id=1"))
        assertTrue(WebPolicy.allowedInApp(h, "https://m.youtube.com/watch?v=x"))
        assertFalse(WebPolicy.allowedInApp(h, "https://evil.example/"))
        assertFalse(WebPolicy.allowedInApp(h, "https://youtube.com.evil.example/"))
        assertFalse(WebPolicy.allowedInApp(h, "intent://x")); assertFalse(WebPolicy.allowedInApp(h, "javascript:alert(1)"))
        assertNull(WebPolicy.hostOf("file:///etc/passwd"))
        assertTrue(WebPolicy.isYoutube("https://m.youtube.com/watch?v=x")); assertTrue(WebPolicy.isYoutube("https://youtu.be/x"))
        assertFalse(WebPolicy.isYoutube("https://youtube.com.evil.example/")); assertFalse(WebPolicy.isYoutube("https://lms.kampus.ac.id/"))
    }
}

class CampusRepoTest {
    // potongan nyata dari halaman hasil EPrints (UMS)
    private val html = """<table><tr class="ep_search_result">
    <td>1.</td><td>
    <span class="person_name">Partini, Partini</span> and <span class="person_name">, Dra. Titik, S.E., M.Si</span>
(2018)
<a href="https://eprints.ums.ac.id/60144/"><em>Minat  Mahasiswa &amp; Manajemen
 Keuangan.</em></a>
    Skripsi thesis, Universitas Muhammadiyah Surakarta.
  </td></tr>
  <tr class="ep_search_result"><td>2.</td><td><span class="person_name">X</span> (2020) <a href="https://evil.example/9/"><em>Palsu</em></a> t</td></tr>
  <tr class="ep_search_result"><td>3.</td><td><span class="person_name">Y</span> (2021) <a href="http://eprints.ums.ac.id/7/"><em>Manajemen SDM</em></a> Tesis</td></tr></table>"""

    @Test fun parsesEprintsRowsAndIgnoresForeignHosts() {
        val r = LibraryApi.parseEprints(html, "eprints.ums.ac.id")
        assertEquals(listOf("Minat Mahasiswa & Manajemen Keuangan", "Manajemen SDM"), r.map { it.title })
        assertEquals("Partini, Partini, Dra. Titik, S.E., M.Si", r[0].author); assertEquals("2018", r[0].year)
        assertEquals("Skripsi thesis, Universitas Muhammadiyah Surakarta", r[0].note)
        assertEquals("https://eprints.ums.ac.id/7/", r[1].url)           // http dinaikkan ke https
        assertTrue(r.all { it.source == LibSource.CAMPUS })
    }

    @Test fun campusSearchUrlAndTrustedHosts() {
        var asked = ""
        LibraryApi.http = HttpGet { asked = it; html }
        assertEquals(2, LibraryApi.search(LibSource.CAMPUS, "manajemen keuangan", LibraryApi.campuses[0]).size)
        assertTrue(asked.startsWith("https://eprints.ums.ac.id/cgi/search/archive/simple?q=manajemen+keuangan&_action_search=Search"))
        assertTrue(LibraryApi.campuses.all { PublicHttp.publicHost(it.host) })
        assertTrue(MANAGEMENT_TOPICS.contains("manajemen pemasaran"))
    }
}

class PdfResolverTest {
    @Test fun findsHighwireMetaRelativeAndHttp() {
        val html = """<head><meta name="citation_pdf_url" content="https://jurnal.kampus.ac.id/index.php/jm/article/download/12/34"></head>"""
        assertEquals("https://jurnal.kampus.ac.id/index.php/jm/article/download/12/34", PdfResolver.find(html, "https://jurnal.kampus.ac.id/index.php/jm/article/view/12"))
        val rel = """<meta content="/files/a.pdf?x=1&amp;y=2" name="citation_pdf_url">"""
        assertEquals("https://situs.id/files/a.pdf?x=1&y=2", PdfResolver.find(rel, "https://situs.id/a/1"))
        assertEquals("https://situs.id/x.pdf", PdfResolver.find("""<meta name="citation_pdf_url" content="http://situs.id/x.pdf">""", "https://situs.id/a"))
    }

    @Test fun findsEprintsDocumentAndAnchorAndRejectsInternal() {
        assertEquals("https://eprints.ums.ac.id/60/1/skripsi.pdf", PdfResolver.find("""<meta name="eprints.document_url" content="https://eprints.ums.ac.id/60/1/skripsi.pdf" />""", "https://eprints.ums.ac.id/60/"))
        assertEquals("https://a.id/doc/b.pdf", PdfResolver.find("""<a class="x" href="/doc/b.pdf">PDF</a>""", "https://a.id/p/1"))
        assertNull(PdfResolver.find("""<meta name="citation_pdf_url" content="https://127.0.0.1/x.pdf">""", "https://a.id/"))
        assertNull(PdfResolver.find("<html>tidak ada</html>", "https://a.id/"))
    }

    @Test fun directPdfSkipsFetch() {
        var fetched = false
        assertEquals("https://a.id/b.pdf", PdfResolver.resolve("https://a.id/b.pdf") { fetched = true; "" })
        assertFalse(fetched)
        assertEquals("https://a.id/z.pdf", PdfResolver.resolve("https://a.id/view/1") { """<meta name="citation_pdf_url" content="https://a.id/z.pdf">""" })
    }

    @Test fun inAppViewerSupportMatrix() {
        assertTrue(canViewInApp("a.pdf", null)); assertTrue(canViewInApp("a.DOCX", null)); assertTrue(canViewInApp("a.png", "image/png"))
        assertTrue(canViewInApp("s.pptx", null)); assertFalse(canViewInApp("old.doc", null)); assertFalse(canViewInApp("a.xlsx", null)); assertFalse(canViewInApp("a.zip", null))
    }
}

class AuditV28Test {
    private val base = "https://lms.kampus.ac.id"

    @Test fun luceneInputIsNeutralised() {
        assertEquals("a", LibraryApi.luceneSafe("((\"::"))
        assertEquals("manajemen keuangan", LibraryApi.luceneSafe("manajemen (keuangan"))
        assertEquals("a b c", LibraryApi.luceneSafe("a+b-c"))
    }

    @Test fun duplicateResultsAreDropped() {
        MoodleApi.htmlToText = { it }
        val r = LibraryApi.parseArchive("""{"response":{"docs":[{"identifier":"x","title":"A"},{"identifier":"x","title":"A lagi"}]}}""")
        assertEquals(1, r.size)
        val d = LibraryApi.parseDoaj("""{"results":[{"id":"j","bibjson":{"title":"T","link":[{"type":"fulltext","url":"https://a.id/x"}]}},{"id":"j","bibjson":{"title":"T2","link":[{"type":"fulltext","url":"https://a.id/y"}]}},{"id":"","bibjson":{"title":"Kosong","link":[{"type":"fulltext","url":"https://a.id/z"}]}}]}""")
        assertEquals(listOf("j"), d.map { it.id })
    }

    @Test fun internalAddressesBlocked() {
        listOf("127.0.0.1", "10.1.2.3", "192.168.0.9", "169.254.169.254", "0.0.0.0", "::1", "fd00::1", "fe80::1").forEach {
            assertTrue(it, PublicHttp.blockedAddress(java.net.InetAddress.getByName(it)))
        }
        assertFalse(PublicHttp.blockedAddress(java.net.InetAddress.getByName("93.184.216.34")))
    }

    @Test fun forumKeepsOldContentWhenForumListFails() {
        MoodleApi.htmlToText = { it }
        val old = Item(7, "forum", "Forum", "Minggu 1", "• Diskusi lama", null, null, emptyList(), "$base/mod/forum/view.php?id=7")
        val prev = Snapshot(1, listOf(Course(11, "Algo", "", listOf(old), emptyList())), emptyList())
        MoodleApi.transport = Transport { _, f -> when (f["wsfunction"]) {
            "core_enrol_get_users_courses" -> """[{"id":11,"fullname":"Algo","shortname":"A"}]"""
            "core_course_get_contents" -> """[{"name":"Minggu 1","modules":[{"id":7,"modname":"forum","name":"Forum","url":"$base/mod/forum/view.php?id=7"}]}]"""
            "mod_assign_get_assignments" -> """{"courses":[]}"""; "mod_quiz_get_quizzes_by_courses" -> """{"quizzes":[]}"""; "mod_page_get_pages_by_courses" -> """{"pages":[]}"""
            "gradereport_user_get_grade_items" -> """{"usergrades":[]}"""
            else -> """{"exception":"x","message":"forum dimatikan"}""" } }
        val it = MoodleApi.fetchAll(Site(base, "T", 1, "B", "S"), previous = prev).courses.single().items.single()
        assertEquals("forum", it.type); assertEquals("• Diskusi lama", it.content)
    }
}
