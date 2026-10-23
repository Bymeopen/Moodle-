package id.pribadi.minilms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Notif {
    const val CH_DOWNLOAD = "downloads"
    const val CH_DEADLINE = "deadlines"

    fun ensureChannels(ctx: Context) {
        val m = ctx.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel(CH_DOWNLOAD, "Unduhan materi", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(CH_DEADLINE, "Pengingat tenggat tugas", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** Unduh semua file yang belum ada (atau satu kursus: KEY_COURSE). Dilanjutkan otomatis: file selesai tidak diulang. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_COURSE = "course"
        const val P_DONE = "done"; const val P_TOTAL = "total"; const val P_NAME = "name"; const val P_BYTES = "bytes"; const val P_BYTES_TOTAL = "bytes_total"
        const val O_FAILED = "failed"; const val O_ERROR = "error"
    }

    private fun info(text: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, Notif.CH_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Mengunduh materi").setContentText(text)
            .setProgress(total, done, false).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(Notif.openApp(applicationContext)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(1, n)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = Repo.get(applicationContext)
        val site = repo.site.value ?: return@withContext Result.failure()
        Notif.ensureChannels(applicationContext)
        val course = inputData.getLong(KEY_COURSE, 0L).takeIf { it != 0L }
        val todo = repo.pending(course)
        if (todo.isEmpty()) return@withContext Result.success()
        runCatching { setForeground(info("Menyiapkan…", 0, todo.size)) }  // gagal bila izin FGS ditolak; unduhan tetap jalan

        val totalBytes = todo.sumOf { it.file.size }
        var doneBytes = 0L; var done = 0; var failed = 0; var lastError = ""; var lastPush = 0L
        for (p in todo) {
            if (!isActive) break
            var ok = false
            for (attempt in 1..2) {                       // satu kali coba ulang untuk gangguan jaringan sesaat
                try {
                    repo.download(site, p, shouldStop = { !isActive }) { n ->
                        doneBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastPush > 700) {
                            lastPush = now
                            setProgressAsync(workDataOf(P_DONE to done, P_TOTAL to todo.size, P_NAME to p.file.name, P_BYTES to doneBytes, P_BYTES_TOTAL to totalBytes))
                        }
                    }
                    ok = true; break
                } catch (e: InterruptedException) { return@withContext Result.success() }
                catch (e: Exception) { lastError = "${p.file.name}: ${e.message}" }
            }
            if (ok) done++ else failed++
            runCatching { setForeground(info(p.file.name, done + failed, todo.size)) }
        }
        Result.success(workDataOf(O_FAILED to failed, O_ERROR to lastError, P_DONE to done, P_TOTAL to todo.size))
    }
}

/** Berkala: sinkron senyap lalu ingatkan tugas yang jatuh tempo dalam 24 jam (sekali per tugas). */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = Repo.get(applicationContext)
        if (repo.site.value == null || !repo.reminders) return@withContext Result.success()
        try { repo.sync() } catch (e: Exception) { /* offline: tetap pakai data lama */ }
        if (!Notif.canPost(applicationContext)) return@withContext Result.success()
        Notif.ensureChannels(applicationContext)
        val now = System.currentTimeMillis() / 1000
        val prefs = applicationContext.getSharedPreferences("reminded", Context.MODE_PRIVATE)
        for (c in repo.snapshot.value?.courses ?: emptyList()) for (i in c.items) {
            val due = i.dueAt ?: continue
            val key = "${i.id}:$due"
            if (i.id in repo.doneAssignments.value) continue   // sudah ditandai selesai
            if (!i.isTask || due < now || due - now > 24 * 3600 || prefs.getBoolean(key, false)) continue
            val n = NotificationCompat.Builder(applicationContext, Notif.CH_DEADLINE).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("Tenggat < 24 jam: ${i.title}").setContentText(c.name).setAutoCancel(true).setContentIntent(Notif.openApp(applicationContext)).build()
            NotificationManagerCompat.from(applicationContext).notify(key.hashCode(), n)
            prefs.edit().putBoolean(key, true).apply()
        }
        Result.success()
    }
}

object Jobs {
    private const val DL = "download"
    fun download(ctx: Context, courseId: Long? = null) {
        val repo = Repo.get(ctx)
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (repo.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInputData(workDataOf(DownloadWorker.KEY_COURSE to (courseId ?: 0L)))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(DL, ExistingWorkPolicy.REPLACE, req)   // permintaan baru (mis. kursus lain) menggantikan, bukan dibuang; file selesai tidak diulang
    }
    fun pause(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(DL) }
    fun name() = DL

    fun scheduleReminders(ctx: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled) { wm.cancelUniqueWork("reminders"); return }
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
