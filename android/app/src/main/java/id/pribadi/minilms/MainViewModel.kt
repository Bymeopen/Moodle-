package id.pribadi.minilms

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NOTEBOOK_MAX_SOURCES = 50
private const val STALE_SECONDS = 30 * 60L

data class DlState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val name: String = "", val bytes: Long = 0, val bytesTotal: Long = 0,
                   val failed: Int = 0, val error: String = "")

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repo.get(app)
    val site = repo.site
    val snapshot = repo.snapshot
    val tick = repo.tick
    val ai = AiController(app, repo, viewModelScope)
    val cards = repo.cards
    val doneAssignments = repo.doneAssignments
    val theme = repo.theme

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun clearMessage() { _message.value = null }
    /** Galat sinkron otomatis/manual terakhir; tampil sebagai banner di Beranda. */
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError

    // ---------- konektivitas ----------
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private fun isOnline() = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true } ?: false
    private val _online = MutableStateFlow(isOnline())
    val online: StateFlow<Boolean> = _online
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = true }
        override fun onLost(network: Network) { _online.value = isOnline() }
    }

    init {
        runCatching { cm?.registerDefaultNetworkCallback(netCb) }
        // sinkron otomatis saat dibuka bila data sudah > 30 menit dan ada koneksi
        viewModelScope.launch {
            delay(400)
            val s = repo.snapshot.value
            if (repo.site.value != null && _online.value && (s == null || System.currentTimeMillis() / 1000 - s.syncedAt > STALE_SECONDS)) sync(quiet = true)
        }
    }

    override fun onCleared() { runCatching { cm?.unregisterNetworkCallback(netCb) } }

    // ---------- unduhan massal (WorkManager) ----------
    val download: StateFlow<DlState> = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Jobs.name())
        .map { list ->
            val w = list.firstOrNull() ?: return@map DlState()
            when (w.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DlState(
                    running = true, done = w.progress.getInt(DownloadWorker.P_DONE, 0), total = w.progress.getInt(DownloadWorker.P_TOTAL, 0),
                    name = w.progress.getString(DownloadWorker.P_NAME) ?: "", bytes = w.progress.getLong(DownloadWorker.P_BYTES, 0), bytesTotal = w.progress.getLong(DownloadWorker.P_BYTES_TOTAL, 0))
                WorkInfo.State.SUCCEEDED -> DlState(failed = w.outputData.getInt(DownloadWorker.O_FAILED, 0), error = w.outputData.getString(DownloadWorker.O_ERROR) ?: "")
                else -> DlState()
            }
        }.onEach { repo.bump() }.stateIn(viewModelScope, SharingStarted.Eagerly, DlState())

    /** Progres unduhan per file (0..1), kunci = Repo.fileKey. */
    private val _fileProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fileProgress: StateFlow<Map<String, Float>> = _fileProgress

    private fun fail(e: Throwable) { _message.value = e.message ?: e.javaClass.simpleName }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _busy.value = true
        try { withContext(Dispatchers.IO) { block() } } catch (e: Exception) { fail(e) } finally { _busy.value = false }
    }

    // ---------- login ----------
    private fun finishLogin(base: String, token: String) {
        val s = MoodleApi.siteInfo(base, token)  // verifikasi token sekaligus
        repo.saveSite(s)
        repo.sync()
        Jobs.scheduleReminders(getApplication(), repo.reminders)
    }

    /** Opsi login situs (penyedia SSO dll.) setelah pengguna mengisi alamat; null bila belum dicek. */
    private val _options = MutableStateFlow<PublicConfig?>(null)
    val loginOptions: StateFlow<PublicConfig?> = _options
    fun resetOptions() { _options.value = null }

    fun checkLogin(rawUrl: String) = launchBusy { _options.value = MoodleApi.publicConfig(MoodleApi.normalizeBase(rawUrl)) }

    /** Mulai SSO. [idp] = penyedia pilihan (null = halaman login Moodle biasa). Mengembalikan URL untuk dibuka di browser. */
    fun startSso(rawUrl: String, cfg: PublicConfig, idp: Idp?): String? = try {
        val base = MoodleApi.normalizeBase(rawUrl)
        val passport = (100000..999999).random().toString()
        repo.beginSso(base, cfg.wwwroot.trimEnd('/'), passport)
        Sso.launchUrl(cfg.launchUrl, passport, idp?.id)
    } catch (e: Exception) { fail(e); null }

    fun handleCallback(uri: Uri) {
        val st = repo.pendingSso() ?: run { _message.value = "Tidak ada login yang sedang berjalan; mulai dari tombol SSO"; return }
        launchBusy {
            val token = Sso.parseCallback(uri.toString(), st.sigBase, st.passport)
            repo.clearSso()
            finishLogin(st.base, token)
        }
    }

    fun loginToken(rawUrl: String, token: String) = launchBusy { finishLogin(MoodleApi.normalizeBase(rawUrl), token.trim()) }
    fun loginPassword(rawUrl: String, user: String, pass: String) = launchBusy {
        val base = MoodleApi.normalizeBase(rawUrl)
        finishLogin(base, MoodleApi.requestToken(base, user.trim(), pass))
    }

    // ---------- data ----------
    /** [quiet] = sinkron otomatis: galat hanya jadi banner, bukan snackbar. */
    fun sync(quiet: Boolean = false) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val s = withContext(Dispatchers.IO) { repo.sync() }
                _syncError.value = null
                if (!quiet && s.warnings.isNotEmpty()) _message.value = "Selesai dengan peringatan: " + s.warnings.joinToString("; ")
            } catch (e: Exception) { _syncError.value = e.message ?: e.javaClass.simpleName } finally { _busy.value = false }
        }
    }

    fun downloadAll() { if (repo.pending().isEmpty()) _message.value = "Semua file sudah diunduh" else Jobs.download(getApplication()) }
    fun downloadCourse(id: Long) { if (repo.pending(id).isEmpty()) _message.value = "Semua file kursus ini sudah diunduh" else Jobs.download(getApplication(), id) }
    fun pause() = Jobs.pause(getApplication())
    fun deleteDownloads(courseId: Long? = null) { Jobs.pause(getApplication()); repo.deleteDownloads(courseId) }

    /** Unduh satu file langsung (tanpa WorkManager) dengan progres per file. */
    fun downloadFile(c: Course, i: Item, f: FileRef) {
        val key = repo.fileKey(c.id, i.id, f)
        if (key in _fileProgress.value) return
        val site = repo.site.value ?: return
        viewModelScope.launch {
            _fileProgress.value = _fileProgress.value + (key to 0f)
            try {
                withContext(Dispatchers.IO) {
                    var got = 0L
                    repo.download(site, Pending(c, i, f), { false }) { n -> got += n; if (f.size > 0) _fileProgress.value = _fileProgress.value + (key to (got.toFloat() / f.size).coerceIn(0f, 1f)) }
                }
            } catch (e: Exception) { fail(e) } finally { _fileProgress.value = _fileProgress.value - key }
        }
    }

    fun logout() { Jobs.pause(getApplication()); Jobs.scheduleReminders(getApplication(), false); repo.logout() }
    fun setReminders(on: Boolean) { repo.reminders = on; Jobs.scheduleReminders(getApplication(), on) }
    fun setWifiOnly(on: Boolean) { repo.wifiOnly = on }
    fun setTheme(m: ThemeMode) = repo.setTheme(m)
    fun toggleDone(id: Long) = repo.setAssignmentDone(id, id !in repo.doneAssignments.value)

    // ---------- file & tautan ----------
    /** Buka file unduhan dengan aplikasi lain (PDF viewer, dll.) lewat FileProvider. */
    fun open(c: Course, i: Item, f: FileRef) {
        val ctx = getApplication<Application>()
        if (!repo.isDownloaded(c.id, i.id, f)) { _message.value = "File belum diunduh"; return }
        val file = repo.fileOf(c.id, i.id, f)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val mime = f.mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada aplikasi untuk membuka file ini" }
    }

    /**
     * Bagikan materi kursus (ringkasan teks .md + file yang sudah diunduh) lewat menu bagikan Android,
     * supaya bisa ditambahkan sebagai sumber di NotebookLM (atau Google Drive). NotebookLM membatasi jumlah sumber per notebook.
     */
    fun shareToNotebook(c: Course) {
        val ctx = getApplication<Application>()
        val md = java.io.File(repo.root, "${c.id}/${safeName(c.name)}.md").apply { parentFile!!.mkdirs(); writeText(AiMaterial.build(c)) }
        val files = c.items.flatMap { i -> i.files.filter { repo.isDownloaded(c.id, i.id, it) }.map { repo.fileOf(c.id, i.id, it) } }
        val all = listOf(md) + files
        val sent = all.take(NOTEBOOK_MAX_SOURCES)
        val uris = ArrayList(sent.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // clipData diperlukan agar izin baca ikut diteruskan ke aplikasi tujuan lewat chooser
        send.clipData = android.content.ClipData.newRawUri(null, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) } }
        try {
            ctx.startActivity(Intent.createChooser(send, "Kirim ke NotebookLM / Drive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (all.size > sent.size) _message.value = "Hanya $NOTEBOOK_MAX_SOURCES sumber pertama yang dikirim (batas NotebookLM)"
            else if (files.isEmpty()) _message.value = "Hanya ringkasan teks yang dikirim; unduh file kursus dulu untuk menyertakan PDF/slide"
        } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada aplikasi untuk menerima file" }
    }

    /** Tulis ZIP kursus ke lokasi pilihan pengguna (Storage Access Framework; tanpa izin penyimpanan). */
    fun exportZip(c: Course, uri: Uri) = launchBusy {
        val ctx = getApplication<Application>()
        val prefix = safeName(c.name)
        val entries = c.items.flatMap { i ->
            i.files.filter { repo.isDownloaded(c.id, i.id, it) }.map { f ->
                val path = listOf(prefix, i.section, i.title).filter { it.isNotEmpty() }.joinToString("/") + "/" + f.path.trim('/') + "/" + f.name
                path to repo.fileOf(c.id, i.id, f)
            }
        }
        val summary = AiMaterial.build(c) { i, f -> repo.textOf(c.id, i.id, f) }
        (ctx.contentResolver.openOutputStream(uri, "wt") ?: throw ApiError("Tidak bisa menulis ke lokasi itu")).use {
            ZipExport.write(it, entries, mapOf("$prefix/Ringkasan.md" to summary))
        }
        _message.value = "ZIP tersimpan (${entries.size} file + ringkasan)"
    }

    /**
     * Buka NotebookLM di Chrome Custom Tab. Google melarang login OAuth di WebView tertanam,
     * sedangkan Custom Tab memakai sesi Google di browser dan tampil menyatu dengan aplikasi.
     */
    fun openNotebookLm() {
        val ctx = getApplication<Application>()
        val tab = androidx.browser.customtabs.CustomTabsIntent.Builder()
            .setToolbarColor(0xFFE8590C.toInt()).setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { tab.launchUrl(ctx, Uri.parse("https://notebooklm.google.com")) }
        catch (e: ActivityNotFoundException) { _message.value = "Tidak ada browser untuk membuka NotebookLM" }
    }

    fun openLink(url: String) {
        val ctx = getApplication<Application>()
        val u = Uri.parse(url)
        if (u.scheme != "https" && u.scheme != "http") { _message.value = "Tautan tidak didukung"; return }
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada browser" }
    }
}
