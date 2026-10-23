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
                {"id":"j2","bibjson":{"title":"Tanpa link"}},{"id":"j3","bibjson":{"title":"Aneh","link":[{"type":"fulltext","url":"javascript:alert(1)"}]}}]}"""
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
        assertTrue(PublicHttp.trustedHost("ia800.us.archive.org")); assertTrue(PublicHttp.trustedHost("doaj.org"))
        assertFalse(PublicHttp.trustedHost("evilarchive.org")); assertFalse(PublicHttp.trustedHost("archive.org.evil.com"))
        assertThrows(ApiError::class.java) { PublicHttp.getText("http://archive.org/x") }
        assertThrows(ApiError::class.java) { PublicHttp.getText("https://evil.example/x") }
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
        assertTrue(LibraryApi.campuses.all { PublicHttp.trustedHost(it.host) })
        assertTrue(MANAGEMENT_TOPICS.contains("manajemen pemasaran"))
    }
}
