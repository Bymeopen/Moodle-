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

private const val STALE_SECONDS = 30 * 60L

data class DlState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val name: String = "", val bytes: Long = 0, val bytesTotal: Long = 0,
                   val failed: Int = 0, val error: String = "", val waiting: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private companion object { const val SESSION_FRESH_MS = 90L * 60 * 1000 }
    val repo = Repo.get(app)
    val site = repo.site
    val snapshot = repo.snapshot
    val tick = repo.tick
    /** Lab Linux (chroot, ponsel root). Sengaja tidak terhubung ke Agen AI. */
    val linux = LinuxController(viewModelScope, app.cacheDir, java.io.File(app.filesDir, "courses"), java.io.File(app.filesDir, "library"))
    val ai = AiController(app, repo, viewModelScope).also { ctl ->
        ctl.lms = object : LmsActions {
            override fun sync(): String = try {
                val s = repo.sync()
                "Sinkron selesai: ${s.courses.size} kursus" + if (s.warnings.isEmpty()) "" else "; peringatan: " + s.warnings.take(3).joinToString("; ")
            } catch (e: Exception) { "Gagal sinkron: ${e.message ?: e.javaClass.simpleName}" }
            override fun downloadCourse(courseId: Long): String {
                val n = repo.pending(courseId).size
                if (n == 0) return "Semua file kursus ini sudah diunduh"
                Jobs.download(getApplication(), courseId); return "Unduhan dimulai ($n file); progres terlihat di Beranda"
            }
            override fun markDone(itemId: Long, done: Boolean) = repo.setAssignmentDone(itemId, done)
            override fun openPage(url: String): String { openMoodle(url); return "Halaman dibuka di aplikasi" }
            override fun addCards(courseId: Long, cards: List<Draft>): String = try {
                val today = java.time.LocalDate.now().toString()
                repo.addCards(cards.map { Card(java.util.UUID.randomUUID().toString(), courseId, it.front, it.back, 2.5, 0, 0, today) }); "OK: ${cards.size} kartu disimpan ke deck"
            } catch (e: java.io.IOException) { "Gagal menyimpan kartu: ${e.message}" }
            override fun librarySearch(source: LibSource, query: String): List<LibItem> = LibraryApi.search(source, query, _lib.value.campus)
            override fun exportPack(courseId: Long): String { val c = repo.snapshot.value?.courses?.firstOrNull { it.id == courseId } ?: return "Kursus tidak ditemukan"; return sharePackNow(c) }
        }
    }
    val cards = repo.cards
    val doneAssignments = repo.doneAssignments
    val theme = repo.theme

    /** Statistik unduhan per kursus; dihitung di thread IO (menyentuh disk) dan tidak lagi di composition. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val stats: StateFlow<Map<Long, Repo.CourseStats>> = combine(repo.snapshot, repo.tick) { s, _ -> s }
        .conflate().mapLatest { s -> s?.courses.orEmpty().associate { it.id to repo.stats(it) } }
        .flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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
        if (repo.site.value != null) Jobs.scheduleReminders(getApplication(), repo.reminders)      // idempoten (KEEP); jangan bergantung pada sinkron pertama
        // sinkron otomatis saat dibuka bila data sudah > 30 menit dan ada koneksi
        viewModelScope.launch {
            delay(400)
            val s = repo.snapshot.value
            if (repo.site.value != null && _online.value && (s == null || System.currentTimeMillis() / 1000 - s.syncedAt > STALE_SECONDS)) sync(quiet = true)
        }
    }

    override fun onCleared() { runCatching { cm?.unregisterNetworkCallback(netCb) }; speaker.shutdown() }

    // ---------- unduhan massal (WorkManager) ----------
    val download: StateFlow<DlState> = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Jobs.name())
        .map { list ->
            val w = list.firstOrNull() ?: return@map DlState()
            when (w.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DlState(
                    running = true, waiting = w.state != WorkInfo.State.RUNNING,     // menunggu Wi-Fi/koneksi, belum mulai done = w.progress.getInt(DownloadWorker.P_DONE, 0), total = w.progress.getInt(DownloadWorker.P_TOTAL, 0),
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
    private fun finishLogin(base: String, token: String, privateToken: String = "") {
        val s = MoodleApi.siteInfo(base, token, privateToken)  // verifikasi token sekaligus
        repo.site.value?.let { old -> if (old.userId != s.userId || old.url != s.url) repo.logout() }     // akun/situs lain: jangan campur data akun lama
        repo.saveSite(s)
        Jobs.scheduleReminders(getApplication(), repo.reminders)
        repo.sync()
    }

    /** Opsi login situs (penyedia SSO dll.) setelah pengguna mengisi alamat; null bila belum dicek. */
    private val _options = MutableStateFlow<PublicConfig?>(null)
    val loginOptions: StateFlow<PublicConfig?> = _options
    fun resetOptions() { _options.value = null }

    fun checkLogin(rawUrl: String) = launchBusy { _options.value = MoodleApi.publicConfig(MoodleApi.normalizeBase(rawUrl)) }

    /** Mulai SSO. [idp] = penyedia pilihan (null = halaman login Moodle biasa). Mengembalikan URL untuk dibuka di browser. */
    fun startSso(rawUrl: String, cfg: PublicConfig, idp: Idp?): String? = try {
        val base = MoodleApi.normalizeBase(rawUrl)
        val passport = java.util.UUID.randomUUID().toString().replace("-", "")     // 128 bit acak (bukan 6 digit yang bisa ditebak)
        repo.beginSso(base, cfg.wwwroot.trimEnd('/'), passport)
        Sso.launchUrl(cfg.launchUrl, passport, idp?.id)
    } catch (e: Exception) { fail(e); null }

    fun handleCallback(uri: Uri) {
        val st = repo.pendingSso() ?: run { _message.value = "Tidak ada login yang sedang berjalan; mulai dari tombol SSO"; return }
        launchBusy {
            val res = try { Sso.parseCallbackFull(uri.toString(), st.sigBase, st.passport) }
                catch (e: ApiError) { if (repo.noteSsoFailure() >= 3) repo.clearSso(); throw e }
            repo.clearSso()
            finishLogin(st.base, res.token, res.privateToken)
        }
    }

    fun loginToken(rawUrl: String, token: String) = launchBusy { finishLogin(MoodleApi.normalizeBase(rawUrl), token.trim()) }
    fun loginPassword(rawUrl: String, user: String, pass: String) = launchBusy {
        val base = MoodleApi.normalizeBase(rawUrl)
        val r = MoodleApi.requestTokens(base, user.trim(), pass)
        finishLogin(base, r.token, r.privateToken)
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

    fun logout() { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.WebStorage.getInstance().deleteAllData(); autologinAt = 0; _web.value = null; _viewing.value = null; _playing.value = null; Jobs.pause(getApplication()); Jobs.scheduleReminders(getApplication(), false); repo.logout() }
    fun setReminders(on: Boolean) { repo.reminders = on; Jobs.scheduleReminders(getApplication(), on) }
    fun setWifiOnly(on: Boolean) { repo.wifiOnly = on }
    fun setTheme(m: ThemeMode) = repo.setTheme(m)
    /** Nilai kartu dan simpan; galat disk jadi pesan, bukan crash. Kartu hasil jadwal baru tetap dikembalikan untuk antrean sesi. */
    fun rateCard(card: Card, rating: String): Card {
        val next = Srs.review(card, rating)
        try { repo.updateCard(next) } catch (e: java.io.IOException) { _message.value = "Gagal menyimpan kartu: ${e.message}" }
        return next
    }
    fun deleteCard(id: String) { try { repo.deleteCard(id) } catch (e: java.io.IOException) { _message.value = "Gagal menghapus kartu: ${e.message}" } }

    fun toggleDone(id: Long) = repo.setAssignmentDone(id, id !in repo.doneAssignments.value)

    // ---------- file & tautan ----------
    /** Buka file unduhan Moodle di dalam aplikasi (PDF/Word/gambar/video); jenis lain lewat aplikasi lain. */
    fun open(c: Course, i: Item, f: FileRef) {
        if (!repo.isDownloaded(c.id, i.id, f)) { _message.value = "File belum diunduh"; return }
        view(repo.fileOf(c.id, i.id, f), f.name, f.mime)
    }

    /** Dokumen yang sedang dibaca di dalam aplikasi; null = tidak ada. */
    private val _viewing = MutableStateFlow<ViewDoc?>(null)
    val viewing: StateFlow<ViewDoc?> = _viewing
    fun closeViewer() { _viewing.value = null }
    fun openViewingExternal() { _viewing.value?.let { openExternal(it.file, null) } }

    fun view(file: java.io.File, title: String, mime: String?) {
        val kind = FileKind.of(title, mime)
        when {
            kind == FileKind.VIDEO -> _playing.value = file                                  // putar di dalam aplikasi
            canViewInApp(title, mime) -> _viewing.value = ViewDoc(file, title, kind)
            else -> openExternal(file, mime)
        }
    }

    /** Buka file dengan aplikasi lain lewat FileProvider (jalan terakhir untuk jenis file yang tak didukung pembaca dalam aplikasi). */
    fun openExternal(file: java.io.File, mime: String?) {
        val ctx = getApplication<Application>()
        val uri = try { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file) } catch (e: IllegalArgumentException) { _message.value = "File tidak bisa dibuka"; return }
        val type = mime ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada aplikasi untuk membuka file ini" }
    }

    // ---------- paket belajar (untuk diolah di NotebookLM/Gemini/Claude: podcast, audio, bahan ajar) ----------
    data class PackSummary(val texts: Int, val chars: Int, val pdfs: Int, val links: Int, val otherFiles: Int, val notDownloaded: Int, val sources: Int, val omitted: Int)

    private class PackFiles(val dir: java.io.File, val texts: List<PackText>, val links: PackText?, val pdfs: List<java.io.File>, val otherFiles: Int, val notDownloaded: Int)

    private fun packFiles(c: Course): PackFiles {
        val texts = StudyPack.texts(c, fileText = { i, f -> repo.textOf(c.id, i.id, f) })
        val files = c.items.flatMap { i -> i.files.map { f -> Triple(i, f, repo.isDownloaded(c.id, i.id, f)) } }
        val pdfs = files.filter { it.third && FileText.isPdf(it.second.name, it.second.mime) }.map { repo.fileOf(c.id, it.first.id, it.second) }
        val other = files.count { it.third && !FileText.isPdf(it.second.name, it.second.mime) }
        return PackFiles(java.io.File(repo.root, "${c.id}/_paket"), texts, StudyPack.links(c), pdfs, other, files.count { !it.third })
    }

    private val _pack = MutableStateFlow<PackSummary?>(null)
    val pack: StateFlow<PackSummary?> = _pack
    fun closePack() { _pack.value = null }

    /** Hitung isi paket (tanpa menulis berkas) untuk ditampilkan di dialog. */
    fun preparePack(c: Course) {
        _pack.value = null
        viewModelScope.launch {
            _pack.value = try { withContext(Dispatchers.IO) {
                val p = packFiles(c); val (sent, omitted) = StudyPack.limit(p.texts, listOfNotNull(p.links), p.pdfs)
                PackSummary(p.texts.size, p.texts.sumOf { it.content.length }, p.pdfs.size, if (p.links == null) 0 else c.items.count { it.type == "link" && it.content.startsWith("http") && it.content != it.url },
                    p.otherFiles, p.notDownloaded, sent.size, omitted)
            } } catch (e: java.io.IOException) { _message.value = "Gagal menyiapkan paket: ${e.message}"; null }
        }
    }

    /** Tulis paket ke penyimpanan aplikasi lalu buka menu bagikan Android. Dipanggil dari UI maupun agen; mengembalikan ringkasan teks. */
    fun sharePackNow(c: Course): String {
        val ctx = getApplication<Application>()
        val p = try { packFiles(c) } catch (e: java.io.IOException) { return "Gagal menyiapkan paket: ${e.message}" }
        if (p.texts.isEmpty() && p.links == null && p.pdfs.isEmpty()) return "Kursus ini belum punya materi untuk dibagikan; sinkron atau unduh file dulu."
        try {
            p.dir.deleteRecursively(); p.dir.mkdirs()
            val written = (p.texts + listOfNotNull(p.links)).map { t -> java.io.File(p.dir, safeName(t.name)).also { it.writeText(t.content) } }
            val (sent, omitted) = StudyPack.limit(written, emptyList(), p.pdfs)
            val uris = ArrayList(sent.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri(null, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) } }     // izin baca ikut lewat chooser
            ctx.startActivity(Intent.createChooser(send, "Bagikan paket belajar ke NotebookLM, Gemini, Claude, atau Drive").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Paket belajar siap: ${sent.size} sumber (${p.texts.size} teks, ${if (p.links != null) 1 else 0} tautan, ${p.pdfs.size.coerceAtMost(sent.size)} PDF)" + if (omitted > 0) "; $omitted sumber tidak muat batas ${StudyPack.MAX_SOURCES}" else ""
        } catch (e: java.io.IOException) { return "Gagal menulis paket: ${e.message}" }
        catch (e: ActivityNotFoundException) { return "Tidak ada aplikasi untuk menerima file" }
    }

    fun sharePack(c: Course) { viewModelScope.launch { val r = withContext(Dispatchers.IO) { sharePackNow(c) }; _message.value = r; _pack.value = null } }

    // ---------- suara (Text-to-Speech) ----------
    val speaker = Speaker(app) { _message.value = it }
    val speaking = speaker.speaking

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
     * Buka situs di Chrome Custom Tab (NotebookLM, claude.ai, gemini.google.com). Google dan Anthropic tidak mengizinkan login OAuth/SSO
     * di WebView tertanam, sedangkan Custom Tab memakai sesi login di browser dan tampil menyatu dengan aplikasi.
     */
    fun openWeb(url: String) {
        val ctx = getApplication<Application>()
        val tab = androidx.browser.customtabs.CustomTabsIntent.Builder().setToolbarColor(0xFFE8590C.toInt()).setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { tab.launchUrl(ctx, Uri.parse(url)) } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada browser untuk membuka situs ini" }
    }
    fun openNotebookLm() = openWeb("https://notebooklm.google.com")

    /** Bagikan laporan diagnostik + jejak crash terakhir (sudah disamarkan) lewat menu bagikan; tidak dikirim otomatis. */
    fun shareDiagnostics() {
        val ctx = getApplication<Application>()
        val text = CrashLog.diagnostics(ctx, repo, ai.provider.value.label, ai.model.value) + (CrashLog.last(ctx)?.let { "\n--- Crash terakhir ---\n$it" } ?: "\n(tidak ada crash tercatat)")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Laporan Mini LMS").putExtra(Intent.EXTRA_TEXT, text)
        try { ctx.startActivity(Intent.createChooser(send, "Bagikan laporan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada aplikasi untuk membagikan teks" }
    }
    fun versionName(): String = runCatching { getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0).versionName }.getOrNull() ?: "?"
    fun hasCrash(): Boolean = CrashLog.last(getApplication()) != null
    fun clearCrash() = CrashLog.clear(getApplication())

    // ---------- perpustakaan online gratis ----------
    data class LibState(
        val source: LibSource = LibSource.JOURNALS, val campus: Campus = LibraryApi.campuses[0], val query: String = "", val results: List<LibItem> = emptyList(), val searching: Boolean = false,
        val searched: Boolean = false, val error: String? = null, val saved: List<LibSaved> = emptyList(),
        val downloading: Map<String, Float> = emptyMap(),      // id -> 0..1 (-1 = belum diketahui)
    )
    private val libStore = LibraryStore(java.io.File(getApplication<Application>().filesDir, "library"))
    private val _lib = MutableStateFlow(LibState())
    val library: StateFlow<LibState> = _lib
    private var libJob: kotlinx.coroutines.Job? = null

    fun libraryLoad() { viewModelScope.launch { val l = withContext(Dispatchers.IO) { libStore.list() }; _lib.update { it.copy(saved = l) } } }
    fun libraryCampus(c: Campus) { _lib.update { it.copy(campus = c, results = emptyList(), searched = false, error = null) }; librarySearch(_lib.value.query.ifBlank { "manajemen" }) }
    fun librarySource(s: LibSource) { _lib.update { it.copy(source = s, results = emptyList(), searched = false, error = null) }; librarySearch(_lib.value.query.ifBlank { defaultQuery(s) }) }
    private fun defaultQuery(s: LibSource) = if (s == LibSource.BOOKS) "management" else "manajemen"
    /** Dipanggil saat layar dibuka: langsung isi dengan topik manajemen agar tidak kosong. */
    fun libraryStart() { val st = _lib.value; if (!st.searched && !st.searching && st.results.isEmpty()) librarySearch(st.query.ifBlank { defaultQuery(st.source) }) }

    fun librarySearch(q: String) {
        libJob?.cancel()
        val src = _lib.value.source; val campus = _lib.value.campus
        _lib.update { it.copy(query = q, searching = true, error = null) }
        libJob = viewModelScope.launch {
            val r = try { withContext(Dispatchers.IO) { LibraryApi.search(src, q, campus) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { _lib.update { it.copy(searching = false, error = "Pencarian gagal: ${e.message ?: "periksa internet"}") }; return@launch }
            _lib.update { it.copy(results = r, searching = false, searched = true) }
        }
    }

    /** Unduh PDF ke penyimpanan lokal: buku Internet Archive, atau PDF jurnal/skripsi yang dicari dari halamannya. */
    fun libraryDownload(item: LibItem) {
        if (item.id in _lib.value.downloading) return
        _lib.update { it.copy(downloading = it.downloading + (item.id to -1f)) }
        viewModelScope.launch {
            var openInstead: String? = null
            try {
                withContext(Dispatchers.IO) {
                    val (url, size) = if (item.source == LibSource.BOOKS) {
                        val (name, sz) = LibraryApi.pdfFor(item.id) ?: throw ApiError("Buku ini tidak punya PDF yang bisa diunduh")
                        LibraryApi.downloadUrl(item.id, name) to sz
                    } else (try { PdfResolver.resolve(item.url) } catch (e: Exception) { null } ?: run { openInstead = item.url; throw ApiError("PDF tidak tersedia untuk diunduh (mungkin dikunci); dibuka sebagai halaman") }) to 0L
                    val dest = java.io.File(java.io.File(getApplication<Application>().filesDir, "library"), safeName("${item.id}.pdf"))
                    PublicHttp.download(url, dest, 300L * 1024 * 1024, { false }) { b -> if (size > 0) _lib.update { it.copy(downloading = it.downloading + (item.id to (b.toFloat() / size).coerceIn(0f, 1f))) } }
                    val head = dest.inputStream().use { i -> ByteArray(5).also { i.read(it) } }
                    if (String(head) != "%PDF-") { dest.delete(); throw ApiError("File yang diunduh bukan PDF (penyedia membatasi akses)") }
                    libStore.add(LibSaved(item.id, item.title, item.author, dest.name, dest.length()))
                }
                _message.value = "Tersimpan: ${item.title}"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _message.value = e.message ?: "Gagal mengunduh"; openInstead?.let { openInApp(it) } }
            _lib.update { it.copy(downloading = it.downloading - item.id) }
            libraryLoad()
        }
    }

    fun libraryDelete(s: LibSaved) { viewModelScope.launch { withContext(Dispatchers.IO) { libStore.remove(s.id) }; libraryLoad() } }

    fun libraryOpen(s: LibSaved) = view(libStore.fileOf(s), s.title.take(80) + ".pdf", "application/pdf")

    /** Video yang sedang diputar di dalam aplikasi (file unduhan lokal); null = tidak ada. */
    private val _playing = MutableStateFlow<java.io.File?>(null)
    val playing: StateFlow<java.io.File?> = _playing
    fun stopPlaying() { _playing.value = null }

    /** Halaman web yang sedang dibuka di dalam aplikasi (WebView); null = tidak ada. */
    /** [url] = halaman tujuan (aman dimuat ulang); [launch] = URL autologin sekali pakai, dipakai hanya untuk pemuatan pertama lalu dibuang. */
    data class WebTarget(val url: String, val launch: String? = null)
    private val _web = MutableStateFlow<WebTarget?>(null)
    val web: StateFlow<WebTarget?> = _web
    fun closeWeb() { _web.value = null }
    fun consumeLaunch() { _web.update { it?.copy(launch = null) } }
    /** Buka tautan (YouTube, rekaman, dll.) di dalam aplikasi; hanya http(s). */
    fun openInApp(url: String) {
        if (WebPolicy.hostOf(url) == null) { _message.value = "Tautan tidak didukung"; return }
        _web.value = WebTarget(if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url)        // lalu lintas teks biasa diblokir Android
    }

    // ---------- sesi Moodle di WebView ----------
    private val _needReauth = MutableStateFlow(false)
    val needReauth: StateFlow<Boolean> = _needReauth
    fun dismissReauth() { _needReauth.value = false }
    private var autologinAt = 0L            // kapan login otomatis terakhir berhasil; cookie sesi WebView masih berlaku beberapa jam
    private var lastMoodleUrl = ""

    /**
     * Buka halaman Moodle (tes, tugas, forum, dll.) di dalam aplikasi. Kunci autologin dibatasi Moodle (sekali tiap beberapa menit),
     * jadi selama cookie sesi masih segar halaman dibuka langsung; bila belum ada private token (login lama), pengguna diminta masuk ulang sekali.
     */
    fun openMoodle(url: String) {
        val site = repo.site.value
        if (site == null) { openInApp(url); return }
        if (site.privateToken.isEmpty()) { _needReauth.value = true; return }
        if (WebPolicy.hostOf(url) == null) { _message.value = "Tautan tidak didukung"; return }
        lastMoodleUrl = url
        viewModelScope.launch {
            val fresh = System.currentTimeMillis() - autologinAt < SESSION_FRESH_MS
            val auto = if (fresh) null else withContext(Dispatchers.IO) { MoodleApi.autologinUrl(site, url) }
            if (auto != null) autologinAt = System.currentTimeMillis()
            if (repo.site.value == null) return@launch               // logout terjadi selagi menunggu kunci
            _web.value = WebTarget(url, auto)
        }
    }

    /** Dipanggil WebView saat sesi Moodle berakhir (halaman login muncul): paksa minta kunci autologin baru. */
    fun retryAutologin() { autologinAt = 0; if (lastMoodleUrl.isNotEmpty()) { _web.value = null; openMoodle(lastMoodleUrl) } }

    /** Masuk ulang lewat SSO (tanpa logout) agar aplikasi mendapat private token untuk login otomatis. */
    fun reauthorize() {
        _needReauth.value = false
        val site = repo.site.value ?: return
        launchBusy {
            val cfg = MoodleApi.publicConfig(site.url)
            val u = startSso(site.url, cfg, cfg.providers.firstOrNull()) ?: return@launchBusy
            getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Buka video di aplikasi YouTube resmi (di sana unduhan offline resmi tersedia); bila tidak terpasang, putar di dalam aplikasi. */
    fun openYoutubeApp(url: String) {
        if (!WebPolicy.isYoutube(url)) { openInApp(url); return }
        try { getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: ActivityNotFoundException) { openInApp(url) }
    }

    fun openLink(url: String) {
        val ctx = getApplication<Application>()
        val u = Uri.parse(url)
        if (u.scheme != "https" && u.scheme != "http") { _message.value = "Tautan tidak didukung"; return }
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada browser" }
    }
}
