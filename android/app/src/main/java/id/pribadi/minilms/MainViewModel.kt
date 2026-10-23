package id.pribadi.minilms

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DlState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val name: String = "", val bytes: Long = 0, val bytesTotal: Long = 0,
                   val failed: Int = 0, val error: String = "")

const val NOTEBOOK_MAX_SOURCES = 50

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repo.get(app)
    val site = repo.site
    val snapshot = repo.snapshot
    val tick = repo.tick
    val ai = AiController(app, repo, viewModelScope)
    val cards = repo.cards

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun clearMessage() { _message.value = null }

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

    /** Mulai SSO: kembalikan URL yang harus dibuka di browser. */
    fun startSso(rawUrl: String): String? = try {
        val base = MoodleApi.normalizeBase(rawUrl)
        val passport = (100000..999999).random().toString()
        repo.beginSso(base, passport)
        Sso.launchUrl(base, passport)
    } catch (e: Exception) { fail(e); null }

    fun handleCallback(uri: Uri) {
        val (base, passport) = repo.pendingSso() ?: run { _message.value = "Tidak ada login yang sedang berjalan; mulai dari tombol SSO"; return }
        launchBusy {
            val token = Sso.parseCallback(uri.toString(), base, passport)
            repo.clearSso()
            finishLogin(base, token)
        }
    }

    fun loginToken(rawUrl: String, token: String) = launchBusy { finishLogin(MoodleApi.normalizeBase(rawUrl), token.trim()) }
    fun loginPassword(rawUrl: String, user: String, pass: String) = launchBusy {
        val base = MoodleApi.normalizeBase(rawUrl)
        finishLogin(base, MoodleApi.requestToken(base, user.trim(), pass))
    }

    // ---------- data ----------
    fun sync() = launchBusy { val s = repo.sync(); if (s.warnings.isNotEmpty()) _message.value = "Selesai dengan peringatan: " + s.warnings.joinToString("; ") }
    fun downloadAll() { if (repo.pending().isEmpty()) _message.value = "Semua file sudah diunduh" else Jobs.download(getApplication()) }
    fun downloadCourse(id: Long) { if (repo.pending(id).isEmpty()) _message.value = "Semua file kursus ini sudah diunduh" else Jobs.download(getApplication(), id) }
    fun pause() = Jobs.pause(getApplication())
    fun deleteDownloads(courseId: Long? = null) { Jobs.pause(getApplication()); repo.deleteDownloads(courseId) }
    fun logout() { Jobs.pause(getApplication()); Jobs.scheduleReminders(getApplication(), false); repo.logout() }
    fun setReminders(on: Boolean) { repo.reminders = on; Jobs.scheduleReminders(getApplication(), on) }
    fun setWifiOnly(on: Boolean) { repo.wifiOnly = on }

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

    fun openLink(url: String) {
        val ctx = getApplication<Application>()
        val u = Uri.parse(url)
        if (u.scheme != "https" && u.scheme != "http") { _message.value = "Tautan tidak didukung"; return }
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: ActivityNotFoundException) { _message.value = "Tidak ada browser" }
    }
}
