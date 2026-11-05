package id.pribadi.minilms.visual

import androidx.activity.ComponentActivity
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
class StudyShotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun show(name: String, mode: ThemeMode, content: @Composable () -> Unit) {
        rule.setContent { LmsTheme(mode) { androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() } } }
        rule.shot("${name}_${mode.name.lowercase()}")
    }

    private val notes = listOf(
        Note("1", 12, 10, "Debit adalah sisi kiri akun", "Ingat: aset bertambah di debit.", Fx.now - 3600),
        Note("2", 12, 11, "", "Kerjakan soal 3 dan 4 dulu.", Fx.now - 7200),
        Note("3", 11, 1, "Pelajari silabus dan kontrak perkuliahan", "", Fx.now - 86400))

    private val hits = Search.search(Fx.snapshot.courses, notes, FxAi.cards, "tugas") + Search.search(Fx.snapshot.courses, notes, FxAi.cards, "debit")
    private val searchState = MainViewModel.SearchState("tugas", hits.take(6))
    @Test fun searchLight() = show("search", ThemeMode.LIGHT) { SearchContent(searchState, {}, {}, {}) }
    @Test fun searchDark() = show("search", ThemeMode.DARK) { SearchContent(searchState, {}, {}, {}) }
    @Test fun searchEmpty() = show("search_empty", ThemeMode.LIGHT) { SearchContent(MainViewModel.SearchState(), {}, {}, {}) }
    @Test fun searchNone() = show("search_none", ThemeMode.LIGHT) { SearchContent(MainViewModel.SearchState("zzzz"), {}, {}, {}) }

    private val tasks = Planner.tasksOf(Fx.snapshot.courses, setOf(5L), Fx.now)
    private val plan = Planner.plan(tasks, Fx.now - 12 * 3600, 90, 6)
    private val cfg = MainViewModel.PlanCfg(true, 90, 19)
    @Test fun plannerLight() = show("planner", ThemeMode.LIGHT) { PlannerContent(plan, cfg, 6, Fx.now, {}, {}, {}, {}, {}, {}) }
    @Test fun plannerDark() = show("planner", ThemeMode.DARK) { PlannerContent(plan, cfg, 6, Fx.now, {}, {}, {}, {}, {}, {}) }
    @Test fun plannerRisk() = show("planner_risk", ThemeMode.LIGHT) {
        PlannerContent(Planner.plan(tasks, Fx.now, 30, 0), cfg.copy(minutes = 30, reminder = false), 0, Fx.now, {}, {}, {}, {}, {}, {})
    }
    @Test fun plannerEmpty() = show("planner_empty", ThemeMode.LIGHT) { PlannerContent(Planner.plan(emptyList(), Fx.now, 60, 0), cfg, 0, Fx.now, {}, {}, {}, {}, {}, {}) }

    @Test fun notesLight() = show("notes", ThemeMode.LIGHT) { NotesContent(notes, Fx.snapshot.courses, { _, _, _ -> }, {}, {}, {}, {}, {}) }
    @Test fun notesDark() = show("notes", ThemeMode.DARK) { NotesContent(notes, Fx.snapshot.courses, { _, _, _ -> }, {}, {}, {}, {}, {}) }
    @Test fun notesEmpty() = show("notes_empty", ThemeMode.LIGHT) { NotesContent(emptyList(), Fx.snapshot.courses, { _, _, _ -> }, {}, {}, {}, {}, {}) }

    @Test fun detailWithNotes() = show("detail_notes", ThemeMode.LIGHT) {
        val c = Fx.akuntansi.copy(items = Fx.akuntansi.items + Item(14, "page", "Konsep Debit Kredit", "Minggu 1", "Debit adalah sisi kiri akun, kredit adalah sisi kanan. Setiap transaksi dicatat di dua akun.", null, null, emptyList()))
        CourseDetailContent(c, Repo.CourseStats(1, 0, 12_000_000, 0), Fx.now, emptySet(), DlState(), emptyMap(), isDownloaded = { _, _ -> false }, fileKey = { _, _ -> "" },
            onBack = {}, onDownloadCourse = {}, onPause = {}, onDeleteDownloads = {}, onOpenFile = { _, _ -> }, onDownloadFile = { _, _ -> }, onLink = {}, onOpenMoodle = {}, onToggleDone = {}, onAskAi = {}, onExportZip = {}, onShareNotebook = {}, onOpenWeb = {},
            notes = listOf(Note("9", 12, 14, "Debit adalah sisi kiri akun", "Aset bertambah di debit.", 1)))
    }
}
