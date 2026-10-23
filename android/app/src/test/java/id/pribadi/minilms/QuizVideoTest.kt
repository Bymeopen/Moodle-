package id.pribadi.minilms

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** Tes (kuis), video, private token SSO, dan login otomatis ke halaman Moodle. */
class QuizVideoTest {
    private val base = "https://lms.kampus.ac.id"
    private val calls = mutableListOf<Pair<String, Map<String, String>>>()
    private var autologinBody = """{"key":"K123","autologinurl":"https://lms.kampus.ac.id/admin/tool/mobile/autologin.php"}"""

    @Before fun setup() {
        calls.clear()
        MoodleApi.htmlToText = { it.replace(Regex("<[^>]+>"), "").trim() }
        MoodleApi.transport = Transport { url, form ->
            calls += url to form
            when (form["wsfunction"]) {
                "core_enrol_get_users_courses" -> """[{"id":11,"fullname":"Algoritma","shortname":"ALGO"}]"""
                "mod_quiz_get_quizzes_by_courses" -> """{"quizzes":[{"coursemodule":6,"intro":"<p>UTS</p>","timeclose":2000000000,"timelimit":1800,"attempts":2,"grade":100}]}"""
                "core_course_get_contents" -> """[{"name":"Minggu 1","modules":[
                    {"id":6,"modname":"quiz","name":"Kuis 1","url":"$base/mod/quiz/view.php?id=6"},
                    {"id":7,"modname":"resource","name":"Rekaman","url":"$base/mod/resource/view.php?id=7","contents":[
                      {"type":"file","filename":"rekaman.mp4","filepath":"/","filesize":9,"fileurl":"$base/webservice/pluginfile.php/1/rekaman.mp4","mimetype":"video/mp4","timemodified":1}]}]}]"""
                "tool_mobile_get_autologin_key" -> autologinBody
                else -> """{"exception":"x","message":"tak dikenal"}"""
            }
        }
    }

    private val site = Site(base, "TOK", 7, "Budi", "Kampus", "PRIV")

    @Test fun quizBecomesTaskWithDeadlineAndInfo() {
        val it = MoodleApi.fetchAll(site).courses.single().items.first { it.id == 6L }
        assertEquals("quiz", it.type); assertTrue(it.isTask)
        assertEquals(2000000000L, it.dueAt)
        assertEquals("$base/mod/quiz/view.php?id=6", it.url)
        assertTrue(it.content.contains("Durasi 30 menit") && it.content.contains("Percobaan maks 2"))
    }

    @Test fun videoFileIsDownloadableAndKindIsVideo() {
        val it = MoodleApi.fetchAll(site).courses.single().items.first { it.id == 7L }
        assertEquals(1, it.files.size)
        assertEquals(FileKind.VIDEO, FileKind.of(it.files[0].name, it.files[0].mime))
        assertEquals(FileKind.AUDIO, FileKind.of("a.mp3", null))
    }

    @Test fun itemUrlAndPrivateTokenSurviveJson() {
        val it = MoodleApi.fetchAll(site).courses.single().items.first { it.id == 6L }
        assertEquals(it.url, it.toJson().toItem().url)
        assertEquals("PRIV", site.toJson().toSite().privateToken)
        assertEquals("", JSONObjectOf("""{"url":"$base","token":"t","userId":1,"fullName":"a","siteName":"b"}""").toSite().privateToken)   // data lama tanpa private token
    }

    private fun JSONObjectOf(s: String) = org.json.JSONObject(s)

    @Test fun ssoCallbackReturnsPrivateToken() {
        val sig = java.security.MessageDigest.getInstance("MD5").digest((base + "9").toByteArray()).joinToString("") { "%02x".format(it) }
        val cb = "moodlemobile://token=" + Base64.getEncoder().encodeToString("$sig:::TOK:::PRIV".toByteArray())
        val r = Sso.parseCallbackFull(cb, base, "9")
        assertEquals("TOK", r.token); assertEquals("PRIV", r.privateToken)
    }

    @Test fun autologinUrlSameHostOnly() {
        val u = MoodleApi.autologinUrl(site, "$base/mod/quiz/view.php?id=6")!!
        assertTrue(u.startsWith("$base/admin/tool/mobile/autologin.php?userid=7&key=K123&urlto="))
        assertEquals("PRIV", calls.last().second["privatetoken"])
        assertNull(MoodleApi.autologinUrl(site, "https://evil.example/x"))                       // host lain: jangan kirim kunci
        assertNull(MoodleApi.autologinUrl(site.copy(privateToken = ""), "$base/x"))              // tanpa private token
        autologinBody = """{"key":"K","autologinurl":"https://evil.example/a.php"}"""
        assertNull(MoodleApi.autologinUrl(site, "$base/x"))                                      // server menunjuk host lain
    }
}
