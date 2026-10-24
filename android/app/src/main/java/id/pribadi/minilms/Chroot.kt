package id.pribadi.minilms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShResult(val exit: Int, val out: String)

/** Menjalankan skrip shell sebagai root. Diganti di tes. */
fun interface RootRunner { fun run(script: String, timeoutSec: Long): ShResult }

/** Root lewat `su` (Magisk/KernelSU dll.): skrip dikirim lewat stdin, keluaran digabung dan dibatasi ukurannya. */
class SuRunner : RootRunner {
    override fun run(script: String, timeoutSec: Long): ShResult {
        val p = try { ProcessBuilder("su").redirectErrorStream(true).start() } catch (e: IOException) { return ShResult(127, "su tidak ditemukan: ponsel belum root") }
        val out = StringBuffer()
        val reader = thread(isDaemon = true) {
            try { p.inputStream.bufferedReader().use { r -> val b = CharArray(4096); while (true) { val n = r.read(b); if (n < 0) break; if (out.length < 400_000) out.append(b, 0, n) } } } catch (e: IOException) {}
        }
        try { p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") } } catch (e: IOException) {}
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(1000); return ShResult(124, out.toString() + "\n(waktu habis)") }
        reader.join(2000)
        return ShResult(p.exitValue(), out.toString())
    }
}

/**
 * Linux (Alpine) di dalam chroot, hanya untuk ponsel yang sudah root. Semua skrip dibangun dari konstanta dan argumen yang di-quote;
 * perintah pengguna tidak pernah digabung mentah ke skrip. Murni agar dites.
 */
object Chroot {
    const val ROOT = "/data/local/minilms-chroot"
    private const val PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Quote POSIX: aman untuk spasi, kutip, `$`, backtick, dan baris baru. */
    fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun abiToAlpine(abis: List<String>): String? = when {
        "arm64-v8a" in abis -> "aarch64"; "armeabi-v7a" in abis -> "armv7"; "x86_64" in abis -> "x86_64"; "x86" in abis -> "x86"; else -> null
    }

    data class Release(val file: String, val sha256: String, val size: Long, val url: String)
    fun releasesBase(arch: String) = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch/"

    /** Cari minirootfs [arch] di latest-releases.yaml; hanya nama berkas dan checksum yang sahih diterima. */
    fun parseRelease(yaml: String, arch: String): Release? {
        for (block in yaml.split(Regex("(?m)^-\\s*$"))) {
            val f = block.lines().mapNotNull { l -> l.trim().takeIf { it.contains(':') }?.let { it.substringBefore(':').trim() to it.substringAfter(':').trim() } }.toMap()
            if (f["flavor"] != "alpine-minirootfs" || f["arch"] != arch) continue
            val file = f["file"] ?: continue; val sha = f["sha256"] ?: continue
            if (!Regex("alpine-minirootfs-[0-9][0-9.]*-${Regex.escape(arch)}\\.tar\\.gz").matches(file) || !Regex("[0-9a-f]{64}").matches(sha)) continue
            return Release(file, sha, f["size"]?.toLongOrNull() ?: 0L, releasesBase(arch) + file)
        }
        return null
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        file.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val HEAD = "R=$ROOT\n[ \"\$R\" = \"$ROOT\" ] || exit 9\n"

    fun checkScript() = HEAD + """
echo "UID=${'$'}(id -u)"
[ -x ${'$'}R/bin/sh ] && echo INSTALLED=1 || echo INSTALLED=0
grep -q " ${'$'}R/dev " /proc/mounts && echo MOUNTED=1 || echo MOUNTED=0
[ -f ${'$'}R/etc/alpine-release ] && echo "VER=${'$'}(cat ${'$'}R/etc/alpine-release)"
"""

    /**
     * Pasang dari folder staging yang sudah diekstrak aplikasi ([TarGz]): pindahkan ke ROOT (atau salin bila beda filesystem),
     * jadikan milik root (chown -h agar symlink absolut tidak diikuti ke sistem Android asli), lalu terapkan izin dari [modes].
     * Menolak bila ROOT sudah ada (mv akan memasukkan staging ke dalamnya).
     */
    fun installScript(stage: String, modes: String) = HEAD + """
if [ -x ${'$'}R/bin/sh ]; then echo SUDAH_TERPASANG; exit 0; fi
[ -e ${'$'}R ] && { echo ROOT_SUDAH_ADA; exit 4; }
[ -d ${shQuote(stage)} ] || { echo STAGE_HILANG; exit 2; }
mv ${shQuote(stage)} ${'$'}R 2>/dev/null || { mkdir -p ${'$'}R && cp -dpR ${shQuote(stage)}/. ${'$'}R/ || { echo GAGAL_SALIN; exit 3; }; }
chown -hR 0:0 ${'$'}R
while read -r m p; do chmod ${'$'}m "${'$'}R/${'$'}p"; done < ${shQuote(modes)}
chmod 755 ${'$'}R
echo OK
"""

    /** Mount idempotent. Materi LMS dan perpustakaan hanya-baca; bila remount read-only gagal, mount itu dibatalkan (tidak dibiarkan bisa ditulis). */
    fun mountScript(courses: String, library: String) = HEAD + """
mkdir -p ${'$'}R/proc ${'$'}R/sys ${'$'}R/dev ${'$'}R/mnt/sdcard ${'$'}R/mnt/lms ${'$'}R/mnt/library ${'$'}R/etc
grep -q " ${'$'}R/proc " /proc/mounts || mount -t proc proc ${'$'}R/proc
grep -q " ${'$'}R/sys " /proc/mounts || mount -t sysfs sys ${'$'}R/sys
grep -q " ${'$'}R/dev " /proc/mounts || mount --bind /dev ${'$'}R/dev
grep -q " ${'$'}R/mnt/sdcard " /proc/mounts || mount --bind /sdcard ${'$'}R/mnt/sdcard
if ! grep -q " ${'$'}R/mnt/lms " /proc/mounts; then
  mount --bind ${shQuote(courses)} ${'$'}R/mnt/lms && { mount -o remount,ro,bind ${'$'}R/mnt/lms || { umount ${'$'}R/mnt/lms; echo "materi LMS tidak di-mount (gagal read-only)"; }; }
fi
if ! grep -q " ${'$'}R/mnt/library " /proc/mounts; then
  mkdir -p ${shQuote(library)}
  mount --bind ${shQuote(library)} ${'$'}R/mnt/library && { mount -o remount,ro,bind ${'$'}R/mnt/library || { umount ${'$'}R/mnt/library; echo "perpustakaan tidak di-mount (gagal read-only)"; }; }
fi
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > ${'$'}R/etc/resolv.conf
"""

    /** Lepas semua mount berurutan terbalik; mengeluarkan MASIH_ADA_MOUNT bila ada yang gagal. */
    fun unmountScript() = HEAD + """
for d in mnt/library mnt/lms mnt/sdcard dev sys proc; do grep -q " ${'$'}R/${'$'}d " /proc/mounts && umount ${'$'}R/${'$'}d; done
if grep -q " ${'$'}R/" /proc/mounts; then echo MASIH_ADA_MOUNT; exit 3; fi
echo OK
"""

    /** Jalankan [cmd] di dalam chroot dengan lingkungan bersih. [cmd] hanya lewat shQuote. */
    fun execScript(cmd: String, courses: String, library: String) = mountScript(courses, library) +
        "chroot ${'$'}R /usr/bin/env -i HOME=/root TERM=xterm PATH=$PATH /bin/sh -c ${shQuote(cmd)} 2>&1\n"

    /** Hapus Linux: menolak bila masih ada mount di bawah ROOT (rm -rf lewat bind-mount akan menghapus penyimpanan asli). */
    fun removeScript() = HEAD + """
for d in mnt/library mnt/lms mnt/sdcard dev sys proc; do grep -q " ${'$'}R/${'$'}d " /proc/mounts && umount ${'$'}R/${'$'}d; done
if grep -q " ${'$'}R/" /proc/mounts; then echo MASIH_ADA_MOUNT_DIBATALKAN; exit 3; fi
rm -rf ${'$'}R && echo OK
"""
}

data class LinuxState(
    val rootOk: Boolean? = null, val installed: Boolean = false, val mounted: Boolean = false, val version: String = "",
    val busy: Boolean = false, val step: String = "", val progress: Float? = null, val log: List<String> = emptyList(),
)

/**
 * Pengontrol Linux chroot. Hanya dijalankan oleh tombol/terminal di layar Lab Linux; sengaja TIDAK terhubung ke Agen AI
 * (teks Moodle yang tak tepercaya tidak boleh bisa memicu perintah root).
 */
class LinuxController(
    private val scope: CoroutineScope, private val cacheDir: File, private val coursesDir: File, private val libraryDir: File,
    private val runner: RootRunner = SuRunner(),
    private val fetch: (String) -> String = { PublicHttp.getText(it, 400_000) },
    private val download: (String, File, Long, (Long) -> Unit) -> Unit = { url, dest, max, onBytes -> PublicHttp.download(url, dest, max, { false }, onBytes) },
    private val abis: () -> List<String> = { android.os.Build.SUPPORTED_ABIS.toList() },
    private val extract: (File, File) -> List<Pair<String, Int>> = { src, dest -> TarGz.extract(src, dest) },
) {
    private val _state = MutableStateFlow(LinuxState())
    val state: StateFlow<LinuxState> = _state
    private val lock = Any()

    private fun log(vararg lines: String) = _state.update { s -> s.copy(log = (s.log + lines.flatMap { it.lines() }.map { it.take(500) }).takeLast(400)) }
    private fun step(text: String, p: Float? = null) = _state.update { it.copy(step = text, progress = p) }

    /** Jalankan [block] sekali pada satu waktu; tindakan lain ditolak selagi sibuk. */
    private fun task(block: () -> Unit): Job? {
        synchronized(lock) { if (_state.value.busy) { log("Masih ada proses berjalan; tunggu selesai."); return null }; _state.update { it.copy(busy = true) } }
        return scope.launch(Dispatchers.IO) {
            try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { log("Galat: ${e.message ?: e.javaClass.simpleName}") }
            finally { _state.update { it.copy(busy = false, step = "", progress = null) } }
        }
    }

    private fun statusNow() {
        val r = runner.run(Chroot.checkScript(), 20)
        val kv = r.out.lines().mapNotNull { l -> l.trim().takeIf { it.contains('=') }?.let { it.substringBefore('=') to it.substringAfter('=') } }.toMap()
        val root = kv["UID"] == "0"
        val inst = root && kv["INSTALLED"] == "1"
        _state.update { it.copy(rootOk = root, installed = inst, mounted = inst && kv["MOUNTED"] == "1", version = kv["VER"].orEmpty()) }          // "mount aktif" hanya berarti bila Linux memang terpasang
        if (!root) log("Root tidak tersedia." + if (r.out.isNotBlank()) " (${r.out.trim().take(200)})" else "")
    }

    fun refresh(): Job? = task { step("Memeriksa root…"); statusNow() }

    fun install(): Job? = task {
        step("Memeriksa root…"); statusNow()
        if (_state.value.rootOk != true) return@task
        if (_state.value.installed) { log("Linux sudah terpasang."); return@task }
        // sisa pemasangan yang gagal sebelumnya dibersihkan lewat skrip hapus yang aman (menolak bila masih ada mount)
        val clean = runner.run(Chroot.removeScript(), 120)
        if (clean.exit != 0) { log("Sisa pemasangan lama tidak bisa dibersihkan: ${clean.out.trim()}"); return@task }
        val arch = Chroot.abiToAlpine(abis()) ?: run { log("Arsitektur CPU tidak didukung: ${abis()}"); return@task }
        step("Mengambil indeks rilis Alpine…")
        val rel = Chroot.parseRelease(fetch(Chroot.releasesBase(arch) + "latest-releases.yaml"), arch) ?: run { log("Indeks rilis Alpine tidak dikenali; dibatalkan."); return@task }
        log("Rilis: ${rel.file} (${rel.size / 1024} KB)")
        val tar = File(cacheDir, "alpine-rootfs.tar.gz")
        try {
            step("Mengunduh rootfs…", 0f)
            download(rel.url, tar, 40L * 1024 * 1024) { b -> if (rel.size > 0) _state.update { it.copy(progress = (b.toFloat() / rel.size).coerceIn(0f, 1f)) } }
            step("Memverifikasi checksum…")
            if (Chroot.sha256(tar) != rel.sha256) { log("Checksum SHA-256 tidak cocok; berkas dibuang dan tidak dipasang."); return@task }
            log("Checksum cocok.")
            step("Mengekstrak arsip…")
            val stage = File(cacheDir, "alpine-stage"); val modesFile = File(cacheDir, "alpine-modes.txt")
            try {
                val modes = extract(tar, stage)
                modesFile.writeText(TarGz.modeManifest(modes))
                log("Diekstrak: ${modes.size} berkas/folder (symlink absolut dipertahankan).")
                step("Memasang sebagai root…")
                val r = runner.run(Chroot.installScript(stage.absolutePath, modesFile.absolutePath), 240)
                log(r.out.trim()); if (r.exit != 0) { log("Pemasangan gagal (kode ${r.exit})."); return@task }
            } finally { runCatching { TarGz.deleteTree(stage.toPath()) }; modesFile.delete() }
            step("Menyiapkan jaringan dan mount…")
            val m = runner.run(Chroot.mountScript(coursesDir.absolutePath, libraryDir.absolutePath), 60); if (m.out.isNotBlank()) log(m.out.trim())
            log("Alpine terpasang.")
        } finally { tar.delete() }
        statusNow()
    }

    /** Paket pengolah materi: Python, pypdf, poppler (teks PDF). */
    fun installTools(): Job? = exec("apk update && apk add --no-cache python3 py3-pip py3-pypdf poppler-utils && echo SELESAI", timeoutSec = 900, label = "Memasang Python & alat PDF…")

    fun exec(cmd: String, timeoutSec: Long = 90, label: String = "Menjalankan…"): Job? {
        val c = cmd.trim(); if (c.isEmpty() || c.length > 4000) return null
        return task {
            if (_state.value.rootOk != true) { statusNow(); if (_state.value.rootOk != true) return@task }
            if (!_state.value.installed) { log("Linux belum terpasang."); return@task }
            step(label); log("$ ${c.take(300)}")
            val r = runner.run(Chroot.execScript(c, coursesDir.absolutePath, libraryDir.absolutePath), timeoutSec)
            if (r.out.isNotBlank()) log(r.out.trimEnd()); if (r.exit != 0) log("(kode keluar ${r.exit})")
            statusNow()
        }
    }

    fun mount(): Job? = task { step("Mount…"); val r = runner.run(Chroot.mountScript(coursesDir.absolutePath, libraryDir.absolutePath), 60); log(r.out.trim().ifEmpty { "Mount siap: /mnt/lms (materi, read-only), /mnt/library, /mnt/sdcard" }); statusNow() }
    fun unmount(): Job? = task { step("Melepas mount…"); val r = runner.run(Chroot.unmountScript(), 60); log(r.out.trim()); statusNow() }
    fun remove(): Job? = task {
        step("Menghapus Linux…"); val r = runner.run(Chroot.removeScript(), 180)
        log(if (r.exit == 0) "Linux dihapus." else "Dibatalkan: ${r.out.trim()}"); statusNow()
    }
}
