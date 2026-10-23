package id.pribadi.minilms

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class MoodleApiTest {
    private val base = "https://lms.kampus.ac.id"
    private val calls = mutableListOf<Pair<String, Map<String, String>>>()

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun cb(sig: String, token: String) = "moodlemobile://token=" + Base64.getEncoder().encodeToString("$sig:::$token".toByteArray())

    @Before fun setup() {
        calls.clear()
        MoodleApi.htmlToText = { it.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").trim() }
        MoodleApi.transport = Transport { url, form ->
            calls += url to form
            when {
                url.endsWith("/login/token.php") -> if (form["password"] == "benar") """{"token":"TOK"}""" else """{"error":"Invalid login","errorcode":"invalidlogin"}"""
                form["wstoken"] != "TOK" -> """{"exception":"x","errorcode":"invalidtoken","message":"Invalid token"}"""
                else -> when (form["wsfunction"]) {
                    "core_webservice_get_site_info" -> """{"userid":7,"fullname":"Budi","sitename":"Kampus X"}"""
                    "core_enrol_get_users_courses" -> """[{"id":11,"fullname":"Algoritma","shortname":"ALGO","summary":"<p>Dasar &amp; lanjut</p>"}]"""
                    "mod_assign_get_assignments" -> """{"courses":[{"id":11,"assignments":[{"cmid":3,"intro":"<p>Kerjakan</p>","duedate":2000000000,"grade":80}]}]}"""
                    "mod_page_get_pages_by_courses" -> """{"pages":[{"coursemodule":2,"content":"<b>Isi</b> halaman"}]}"""
                    "core_course_get_contents" -> """[{"name":"Minggu 1","modules":[
                        {"id":2,"modname":"page","name":"Intro"},
                        {"id":3,"modname":"assign","name":"PR1"},
                        {"id":4,"modname":"resource","name":"Slide","url":"https://lms.kampus.ac.id/mod/resource/view.php?id=4","contents":[
                          {"type":"file","filename":"slide.pdf","filepath":"/","filesize":5,"fileurl":"https://lms.kampus.ac.id/webservice/pluginfile.php/1/slide.pdf","mimetype":"application/pdf","timemodified":100}]},
                        {"id":5,"modname":"forum","name":"Rahasia","url":"x","uservisible":false}]}]"""
                    "gradereport_user_get_grade_items" -> """{"usergrades":[{"gradeitems":[{"itemname":"PR1","graderaw":70,"grademax":80},{"itemtype":"course","itemname":null,"graderaw":null,"grademax":80}]}]}"""
                    else -> """{"exception":"x","message":"fungsi tidak dikenal"}"""
                }
            }
        }
    }

    @Test fun ssoCallbackValid() = assertEquals("TOK", Sso.parseCallback(cb(md5(base + "123"), "TOK"), base, "123"))

    @Test fun ssoCallbackRejectsWrongSignature() {
        val e = assertThrows(ApiError::class.java) { Sso.parseCallback(cb(md5(base + "lain"), "TOK"), base, "123") }
        assertTrue(e.message!!.contains("Tanda tangan"))
    }

    @Test fun ssoCallbackRejectsGarbage() {
        assertThrows(ApiError::class.java) { Sso.parseCallback("moodlemobile://nothing", base, "1") }
        assertThrows(ApiError::class.java) { Sso.parseCallback("moodlemobile://token=%%%", base, "1") }
    }

    @Test fun ssoLaunchUrl() = assertEquals("$base/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=9&urlscheme=moodlemobile", Sso.launchUrl(base, "9"))

    @Test fun normalizeBaseRequiresHttps() {
        assertEquals("https://a.b/moodle", MoodleApi.normalizeBase(" https://a.b/moodle/ "))
        assertThrows(ApiError::class.java) { MoodleApi.normalizeBase("http://a.b") }
        assertThrows(ApiError::class.java) { MoodleApi.normalizeBase("bukan url") }
    }

    @Test fun tokenRequest() {
        assertEquals("TOK", MoodleApi.requestToken(base, "u", "benar"))
        assertThrows(ApiError::class.java) { MoodleApi.requestToken(base, "u", "salah") }
    }

    @Test fun wsErrorBecomesApiError() { assertThrows(ApiError::class.java) { MoodleApi.call(base, "BURUK", "core_webservice_get_site_info") } }

    @Test fun paramsAreFlattenedAndTokenNotInUrl() {
        MoodleApi.call(base, "TOK", "mod_assign_get_assignments", mapOf("courseids" to listOf(11L, 12L)))
        val (url, form) = calls.last()
        assertEquals("11", form["courseids[0]"]); assertEquals("12", form["courseids[1]"])
        assertFalse(url.contains("TOK"))
    }

    @Test fun fetchAllMapsEverything() {
        val site = MoodleApi.siteInfo(base, "TOK")
        assertEquals("Kampus X", site.siteName)
        val snap = MoodleApi.fetchAll(site, now = 1)
        val c = snap.courses.single()
        assertEquals("Algoritma", c.name); assertEquals("Dasar & lanjut", c.summary)
        assertEquals(listOf("Minggu 1"), c.items.map { it.section }.distinct())
        val byTitle = c.items.associateBy { it.title }
        assertEquals(3, c.items.size)  // modul tersembunyi dilewati
        assertEquals("Isi halaman", byTitle.getValue("Intro").content)
        assertEquals(80.0, byTitle.getValue("PR1").maxGrade!!, 0.0); assertEquals(2000000000L, byTitle.getValue("PR1").dueAt)
        assertEquals("slide.pdf", byTitle.getValue("Slide").files.single().name)
        assertEquals(listOf("PR1", "Total kursus"), c.grades.map { it.name })
        assertEquals(70.0, c.grades[0].grade!!, 0.0); assertNull(c.grades[1].grade)
        assertTrue(snap.warnings.isEmpty())
    }

    @Test fun oneFailingFunctionOnlyWarns() {
        val inner = MoodleApi.transport
        MoodleApi.transport = Transport { u, f -> if (f["wsfunction"] == "gradereport_user_get_grade_items") """{"exception":"x","message":"nilai dimatikan"}""" else inner.post(u, f) }
        val snap = MoodleApi.fetchAll(MoodleApi.siteInfo(base, "TOK"), now = 1)
        assertEquals(1, snap.courses.size); assertTrue(snap.courses[0].grades.isEmpty())
        assertTrue(snap.warnings.single().contains("nilai dimatikan"))
    }

    @Test fun snapshotJsonRoundTrip() {
        val snap = MoodleApi.fetchAll(MoodleApi.siteInfo(base, "TOK"), now = 5)
        val back = JSONObject(snap.toJson().toString()).toSnapshot()
        assertEquals(snap, back)
    }

    @Test fun safeNameStripsTraversal() {
        assertFalse(safeName("../../evil.txt").contains("/"))
        assertFalse(safeName(".bashrc").startsWith("."))
        assertEquals("Tugas 1 (final).pdf", safeName("Tugas 1 (final).pdf"))
        assertEquals("_", safeName("///"))
    }
}
