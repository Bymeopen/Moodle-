package id.pribadi.minilms

import android.Manifest
import android.content.Intent
import android.graphics.Color as AColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notif.ensureChannels(this)
        // jangan memproses ulang callback SSO yang sama saat aktivitas dibuat ulang (rotasi/ganti tema)
        if (savedInstanceState == null) intent?.let(::route)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        route(intent)
    }

    /** Callback SSO Moodle: moodlemobile://token=... */
    private fun route(i: Intent) {
        val d = i.data ?: return
        if (d.scheme == Sso.SCHEME) { i.data = null; vm.handleCallback(d) }
    }
}

@Composable
fun App(vm: MainViewModel) {
    val mode by vm.theme.collectAsStateWithLifecycle()
    LmsTheme(mode) {
        // ikon bilah status/navigasi mengikuti tema aplikasi (bisa beda dari tema sistem)
        val dark = LocalIsDark.current
        val act = LocalContext.current as ComponentActivity
        SideEffect {
            act.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(AColor.TRANSPARENT, AColor.TRANSPARENT) { dark },
            )
        }
        val site by vm.site.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val host = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { host.showSnackbar(it); vm.clearMessage() } }
        Scaffold(snackbarHost = { SnackbarHost(host) }, containerColor = MaterialTheme.colorScheme.background, contentWindowInsets = WindowInsets(0)) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) { if (site == null) LoginScreen(vm) else MainScreen(vm) }
        }
    }
}

private data class Dest(val label: String, val icon: ImageVector, val selected: ImageVector)
private val dests = listOf(
    Dest("Beranda", Icons.Outlined.Home, Icons.Rounded.Home), Dest("Kursus", Icons.Outlined.School, Icons.Rounded.School),
    Dest("Tenggat", Icons.Outlined.EventAvailable, Icons.Rounded.EventAvailable), Dest("AI", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
    Dest("Profil", Icons.Outlined.Person, Icons.Rounded.Person),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var overlay by rememberSaveable { mutableStateOf("") }            // "", "course:ID", "grades", "review", "library", "linux"
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(CourseFilter.ALL) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val site by vm.site.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val syncError by vm.syncError.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val web by vm.web.collectAsStateWithLifecycle()
    val viewing by vm.viewing.collectAsStateWithLifecycle()
    val needReauth by vm.needReauth.collectAsStateWithLifecycle()
    val done by vm.doneAssignments.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val hasKey by vm.ai.hasKey.collectAsStateWithLifecycle()

    val courses = snap?.courses.orEmpty()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val nowSec by rememberNowSec(snap, tick)
    val rows = remember(snap) { deadlinesOf(snap) }
    val urgent = remember(rows, done, nowSec) { rows.count { it.item.id !in done && it.item.dueAt!! in nowSec..(nowSec + 86400) } }
    val dueCards = remember(cards) { cards.count { Srs.isDue(it) } }

    // izin notifikasi untuk unduhan dan pengingat (Android 13+)
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33 && !Notif.canPost(vm.getApplication()) && !vm.repo.askedNotif) { vm.repo.askedNotif = true; perm.launch(Manifest.permission.POST_NOTIFICATIONS) } }

    playing?.let { VideoPlayerDialog(it, vm::stopPlaying) }
    viewing?.let { DocViewerDialog(it, vm::closeViewer, vm::openViewingExternal) }
    if (needReauth) AlertDialog(vm::dismissReauth, title = { Text("Aktifkan login otomatis") },
        text = { Text("Agar tes, forum, dan video terbuka langsung di aplikasi tanpa meminta login lagi, masuk sekali lagi lewat Google kampus. Data dan unduhan Anda tidak hilang.") },
        confirmButton = { TextButton(vm::reauthorize) { Text("Masuk sekarang") } }, dismissButton = { TextButton(vm::dismissReauth) { Text("Nanti") } })
    web?.let { t -> androidx.compose.ui.window.Dialog(vm::closeWeb, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) { MoodleWebScreen(t.url, t.launch, vm::consumeLaunch, vm::closeWeb, vm::openLink, vm::retryAutologin) } } } }
    BackHandler(enabled = overlay.isNotEmpty() || tab != 0) { if (overlay.isNotEmpty()) overlay = "" else tab = 0 }

    val course = if (overlay.startsWith("course:")) courses.firstOrNull { it.id == overlay.removePrefix("course:").toLongOrNull() } else null
    val openCourse = { id: Long -> overlay = "course:$id" }
    val askAi = { id: Long?, m: AiMode -> vm.ai.open(id, m); overlay = ""; tab = 3 }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            when {
                overlay == "review" -> ReviewScreen(vm) { overlay = "" }
                overlay == "library" -> LibraryScreen(vm) { overlay = "" }
                overlay == "linux" -> LinuxScreen(vm) { overlay = "" }
                overlay == "grades" -> Column { BackBar("Nilai") { overlay = "" }; GradesScreen(courses) }
                course != null -> CourseDetailScreen(vm, course, stats[course.id] ?: Repo.CourseStats(0, 0, 0, 0), onBack = { overlay = "" }, onAskAi = { askAi(course.id, it) })
                else -> when (tab) {
                    0 -> PullToRefreshBox(busy, { vm.sync() }) {
                        HomeScreen(site?.fullName.orEmpty(), snap, stats, dl, online, busy, syncError, done, dueCards, hasKey, nowSec, Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                            onSync = { vm.sync() }, onOpenCourse = openCourse, onDeadlines = { tab = 2 }, onCourses = { tab = 1 }, onGrades = { overlay = "grades" },
                            onDownloadAll = vm::downloadAll, onPause = vm::pause, onReview = { overlay = "review" }, onAskAi = { askAi(null, AiMode.CHAT) }, onLibrary = { overlay = "library" })
                    }
                    1 -> PullToRefreshBox(busy, { vm.sync() }) { CoursesScreen(snap, stats, busy, query, filter, { query = it }, { filter = it }, openCourse) }
                    2 -> DeadlinesScreen(rows, done, nowSec, showDone, { showDone = it }, vm::toggleDone, openCourse)
                    3 -> AiScreen(vm, onReview = { overlay = "review" })
                    else -> ProfileScreen(vm, onGrades = { overlay = "grades" }, onReview = { overlay = "review" }, onLibrary = { overlay = "library" }, onLinux = { overlay = "linux" })
                }
            }
        }
        if (overlay.isEmpty()) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            dests.forEachIndexed { i, d ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(d.label) },
                    icon = {
                        BadgedBox(badge = { if (i == 2 && urgent > 0) Badge { Text("$urgent") } }) { Icon(if (tab == i) d.selected else d.icon, d.label) }
                    })
            }
        }
    }
}

@Composable
fun BackBar(title: String, onBack: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Kembali") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
