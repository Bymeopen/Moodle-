package id.pribadi.minilms.visual

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.pribadi.minilms.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1100dp-xxhdpi")
class ScreensShotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        rule.setContent { LmsTheme(mode) { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() } } }
        rule.shot("${name}_${mode.name.lowercase()}")
    }

    private fun home(mode: ThemeMode, dl: DlState = DlState(), online: Boolean = true) = show("home", mode) {
        HomeScreen("MUHAMMAD HARIS ILYASA 20260500714", Fx.snapshot, Fx.stats, dl, online, false, null, emptySet(), 3, true, Fx.now, 8,
            {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun homeLight() = home(ThemeMode.LIGHT)
    @Test fun homeDark() = home(ThemeMode.DARK, DlState(running = true, done = 3, total = 9, name = "Buku-Ajar-Akuntansi.pdf", bytes = 4_000_000, bytesTotal = 12_000_000), online = false)

    @Test fun coursesLight() = show("courses", ThemeMode.LIGHT) { CoursesScreen(Fx.snapshot, Fx.stats, false, "", CourseFilter.ALL, {}, {}, {}) }
    @Test fun coursesDark() = show("courses", ThemeMode.DARK) { CoursesScreen(Fx.snapshot, Fx.stats, false, "", CourseFilter.ALL, {}, {}, {}) }

    @Test fun deadlinesLight() = show("deadlines", ThemeMode.LIGHT) { DeadlinesScreen(deadlinesOf(Fx.snapshot), setOf(5L), Fx.now, false, {}, {}, {}) }
    @Test fun deadlinesDark() = show("deadlines", ThemeMode.DARK) { DeadlinesScreen(deadlinesOf(Fx.snapshot), setOf(5L), Fx.now, false, {}, {}, {}) }

    @Test fun gradesLight() = show("grades", ThemeMode.LIGHT) { GradesScreen(Fx.snapshot.courses) }
    @Test fun gradesDark() = show("grades", ThemeMode.DARK) { GradesScreen(Fx.snapshot.courses) }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1100dp-xxhdpi")
class MoreShotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun show(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        rule.setContent { LmsTheme(mode) { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() } } }
        rule.shot("${name}_${mode.name.lowercase()}")
    }
    private fun detail(mode: ThemeMode) = show("detail", mode) {
        CourseDetailContent(Fx.akuntansi.copy(items = Fx.akuntansi.items + Fx.aik.items.take(3), summary = "Dasar-dasar pencatatan dan pelaporan keuangan."), Repo.CourseStats(3, 1, 21_000_000, 5_800_000), Fx.now, setOf(12L), DlState(), mapOf("12/13/${"https://lms.kampus.ac.id/webservice/pluginfile.php/Buku-Ajar-Akuntansi.pdf".hashCode()}" to 0.4f),
            isDownloaded = { _, f -> f.name.startsWith("Modul") }, fileKey = { i, f -> "12/${i.id}/${f.url.hashCode()}" }, onBack = {}, onDownloadCourse = {}, onPause = {}, onDeleteDownloads = {},
            onOpenFile = { _, _ -> }, onDownloadFile = { _, _ -> }, onLink = {}, onToggleDone = {}, onAskAi = {}, onExportZip = {}, onShareNotebook = {}, onOpenNotebook = {})
    }
    @Test fun detailLight() = detail(ThemeMode.LIGHT)
    @Test fun detailDark() = detail(ThemeMode.DARK)

    @Test fun aiChatLight() = show("ai_chat", ThemeMode.LIGHT) { AiContent(FxAi.state(AiMode.CHAT, chat = FxAi.chat, busy = true), AiActions()) }
    @Test fun aiChatDark() = show("ai_chat", ThemeMode.DARK) { AiContent(FxAi.state(AiMode.CHAT, chat = FxAi.chat.take(2)), AiActions()) }
    @Test fun aiEmptyLight() = show("ai_empty", ThemeMode.LIGHT) { AiContent(FxAi.state(AiMode.CHAT), AiActions()) }
    @Test fun aiErrorLight() = show("ai_error", ThemeMode.LIGHT) { AiContent(FxAi.state(AiMode.CHAT, chat = FxAi.chat.take(1), error = "Kunci API tidak valid. Periksa di Profil → Claude AI."), AiActions()) }
    @Test fun aiSummaryLight() = show("ai_summary", ThemeMode.LIGHT) { AiContent(FxAi.state(AiMode.SUMMARY), AiActions()) }
    @Test fun aiQuizLight() = show("ai_quiz", ThemeMode.LIGHT) { AiContent(FxAi.state(AiMode.QUIZ), AiActions()) }
    @Test fun aiCardsLight() = show("ai_cards", ThemeMode.LIGHT) { AiContent(FxAi.state(AiMode.CARDS), AiActions()) }
    @Test fun aiKeyLight() = show("ai_key", ThemeMode.LIGHT) { AiContent(FxAi.state(AiMode.CHAT, hasKey = false), AiActions()) }
    @Test fun reviewLight() = show("review", ThemeMode.LIGHT) { ReviewContent(FxAi.cards.take(1), { c, _ -> c }, {}) }

    private val profile = ProfileState("MUHAMMAD HARIS ILYASA 20260500714", "Solusi | SiberMu", "https://solusi.sibermu.ac.id", Fx.now - 300, Fx.now, true, true, ThemeMode.SYSTEM, 9_200_000, 5, "claude-opus-5-5", true, Usage(2400, 16800, 0, 900), "2.1")
    @Test fun profileLight() = show("profile", ThemeMode.LIGHT) { ProfileContent(profile, ProfileActions()) }
    @Test fun profileDark() = show("profile", ThemeMode.DARK) { ProfileContent(profile, ProfileActions()) }

    @Test fun loginLight() = show("login", ThemeMode.LIGHT) { LoginContent(false, null, {}, {}, { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }) }
    @Test fun loginProvidersLight() = show("login_idp", ThemeMode.LIGHT) { LoginContent(false, PublicConfig("https://x", "https://x/launch.php", 1, listOf(Idp("Login SSO Google", 3))), {}, {}, { _, _, _ -> }, { _, _ -> }, { _, _, _ -> }) }
}
