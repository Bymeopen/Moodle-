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
        val p = try { ProcessBuilder("su").redirectErrorStream(true).start() } catch (e: IOException) { return ShResult(127, "su tidak ditemukan: ponsel belum root, atau aplikasi ini belum diberi izin root (KernelSU/APatch menyembunyikan su sampai Mini LMS diizinkan di manajer root)") }
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

    private fun validRelease(file: String, sha: String, arch: String) =
        Regex("alpine-minirootfs-[0-9][0-9.]*-${Regex.escape(arch)}\\.tar\\.gz").matches(file) && Regex("[0-9a-f]{64}").matches(sha)

    /** Cari minirootfs [arch] di latest-releases.yaml; hanya nama berkas dan checksum yang sahih diterima. */
    fun parseRelease(yaml: String, arch: String): Release? {
        for (block in yaml.split(Regex("(?m)^-\\s*$"))) {
            val f = block.lines().mapNotNull { l -> l.trim().takeIf { it.contains(':') }?.let { it.substringBefore(':').trim() to it.substringAfter(':').trim() } }.toMap()
            if (f["flavor"] != "alpine-minirootfs" || f["arch"] != arch) continue
            val file = f["file"] ?: continue; val sha = f["sha256"] ?: continue
            if (!validRelease(file, sha, arch)) continue
            return Release(file, sha, f["size"]?.toLongOrNull() ?: 0L, releasesBase(arch) + file)
        }
        return null
    }

    /** Rootfs yang dibundel di APK (aset `alpine/`, ditulis task Gradle `fetchAlpineRootfs` dari pin `android/alpine.properties`). [open] membuka arsipnya. */
    data class Bundle(val release: Release, val open: () -> java.io.InputStream)

    /** Baca `alpine/manifest.properties`; null bila arsitektur beda atau isinya tidak sahih (pemasangan lalu memakai jalur unduh). */
    fun parseManifest(text: String, arch: String): Release? {
        val kv = text.lines().mapNotNull { l -> l.trim().takeIf { it.contains('=') && !it.startsWith('#') }?.let { it.substringBefore('=').trim() to it.substringAfter('=').trim() } }.toMap()
        if (kv["arch"] != arch) return null
        val file = kv["file"] ?: return null; val sha = kv["sha256"] ?: return null; val asset = kv["asset"] ?: return null
        if (!validRelease(file, sha, arch) || !Regex("[a-z0-9][a-z0-9._-]*").matches(asset)) return null
        return Release(file, sha, 0L, "asset:alpine/$asset")                                  // nama aset bukan .gz: AAPT2 membongkar aset .gz
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        file.inputStream().use { i -> val b = ByteArray(64 * 1024); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Linux terpasang bila berkas biasa ini ada. Jangan tes `bin/sh`: itu symlink absolut `/bin/busybox` yang di host menunjuk ke Android asli (tidak ada). */
    fun installedTest(r: String = "\$R") = "[ -f $r/etc/alpine-release ] && [ -f $r/bin/busybox ]"

    private const val HEAD = "R=$ROOT\n[ \"\$R\" = \"$ROOT\" ] || exit 9\n"

    fun checkScript() = HEAD + """
echo "UID=${'$'}(id -u)"
${installedTest()} && echo INSTALLED=1 || echo INSTALLED=0
grep -q " ${'$'}R/dev " /proc/mounts && echo MOUNTED=1 || echo MOUNTED=0
[ -f ${'$'}R/etc/alpine-release ] && echo "VER=${'$'}(cat ${'$'}R/etc/alpine-release)"
"""

    /**
     * Pasang dari folder staging yang sudah diekstrak aplikasi ([TarGz]): pindahkan ke ROOT (atau salin bila beda filesystem),
     * jadikan milik root (chown -h agar symlink absolut tidak diikuti ke sistem Android asli), lalu terapkan izin dari [modes].
     * Menolak bila ROOT sudah ada (mv akan memasukkan staging ke dalamnya).
     */
    fun installScript(stage: String, modes: String) = HEAD + """
if ${installedTest()}; then echo SUDAH_TERPASANG; exit 0; fi
[ -e ${'$'}R ] && { echo ROOT_SUDAH_ADA; exit 4; }
[ -d ${shQuote(stage)} ] || { echo STAGE_HILANG; exit 2; }
mv ${shQuote(stage)} ${'$'}R 2>/dev/null || { mkdir -p ${'$'}R && cp -dpR ${shQuote(stage)}/. ${'$'}R/ || { echo GAGAL_SALIN; exit 3; }; }
chown -hR 0:0 ${'$'}R
while read -r m p; do chmod ${'$'}m "${'$'}R/${'$'}p"; done < ${shQuote(modes)}
chmod 755 ${'$'}R
echo OK
"""

    /**
     * Mount idempotent. Materi LMS dan perpustakaan dibuat hanya-baca lewat `busybox mount -o remount,ro,bind` **di dalam chroot**:
     * `mount` Android (toybox) menangani remount+bind dengan me-remount seluruh partisi /data, jadi tidak boleh dipakai di sisi host.
     * Bila read-only gagal, mount itu dibatalkan (tidak dibiarkan bisa ditulis). Folder sumber dibuat oleh aplikasi, bukan root.
     */
    fun mountScript(courses: String, library: String) = HEAD + """
mkdir -p ${'$'}R/proc ${'$'}R/sys ${'$'}R/dev ${'$'}R/mnt/sdcard ${'$'}R/mnt/lms ${'$'}R/mnt/library ${'$'}R/etc
grep -q " ${'$'}R/proc " /proc/mounts || mount -t proc proc ${'$'}R/proc
grep -q " ${'$'}R/sys " /proc/mounts || mount -t sysfs sys ${'$'}R/sys
for n in null zero urandom random; do grep -q " ${'$'}R/dev/${'$'}n " /proc/mounts && umount ${'$'}R/dev/${'$'}n; done
grep -q " ${'$'}R/dev " /proc/mounts || mount --bind /dev ${'$'}R/dev
grep -q " ${'$'}R/mnt/sdcard " /proc/mounts || mount --bind /sdcard ${'$'}R/mnt/sdcard
if ! grep -q " ${'$'}R/mnt/lms " /proc/mounts; then
  if [ -d ${shQuote(courses)} ]; then
    mount --bind ${shQuote(courses)} ${'$'}R/mnt/lms && { chroot ${'$'}R /bin/busybox mount -o remount,ro,bind /mnt/lms || { umount ${'$'}R/mnt/lms; echo "materi LMS tidak di-mount (gagal read-only)"; }; }
  else echo "folder materi belum ada; unduh kursus dulu"; fi
fi
if ! grep -q " ${'$'}R/mnt/library " /proc/mounts; then
  if [ -d ${shQuote(library)} ]; then
    mount --bind ${shQuote(library)} ${'$'}R/mnt/library && { chroot ${'$'}R /bin/busybox mount -o remount,ro,bind /mnt/library || { umount ${'$'}R/mnt/library; echo "perpustakaan tidak di-mount (gagal read-only)"; }; }
  else echo "folder perpustakaan belum ada"; fi
fi
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > ${'$'}R/etc/resolv.conf
"""

    // ---------- sandbox untuk Agen AI: pengguna terbatas, tanpa root ----------
    const val AI_UID = 5099
    const val AI_WORK = "/home/ai/work"
    const val AI_MAX_COPY_KB = 800_000L

    /** Perintah yang dijalankan sebagai pengguna `ai`: masuk folder kerja lalu `timeout` mematikan paksa. [cmd] hanya lewat shQuote. */
    fun aiInner(cmd: String, timeoutSec: Int = 50) = "cd $AI_WORK && exec timeout -s KILL ${timeoutSec.coerceIn(1, 120)} /bin/sh -c ${shQuote(cmd)}"

    /** Siapkan pengguna `ai` (uid 5099, bukan root) dan folder kerjanya; opsional salin materi kursus (root-owned, hanya-baca bagi `ai`) ke /home/ai/lms. */
    fun aiSetupScript(courses: String, copyMaterials: Boolean) = HEAD + """
grep -q '^ai:' ${'$'}R/etc/passwd || echo 'ai:x:$AI_UID:$AI_UID:AI sandbox:/home/ai:/bin/sh' >> ${'$'}R/etc/passwd
grep -q '^ai:' ${'$'}R/etc/group || echo 'ai:x:$AI_UID:' >> ${'$'}R/etc/group
mkdir -p ${'$'}R/home/ai/work ${'$'}R/home/ai/bridge ${'$'}R/usr/local/bin ${'$'}R/usr/local/share
chown $AI_UID:$AI_UID ${'$'}R/home/ai ${'$'}R/home/ai/work
chmod 755 ${'$'}R/home/ai ${'$'}R/home/ai/work
chown 0:0 ${'$'}R/home/ai/bridge; chmod 755 ${'$'}R/home/ai/bridge
cat > ${'$'}R/usr/local/bin/moodle <<'MOODLE_CLI_EOF'
${MoodleBridge.cliScript()}MOODLE_CLI_EOF
chown 0:0 ${'$'}R/usr/local/bin/moodle; chmod 755 ${'$'}R/usr/local/bin/moodle
cat > ${'$'}R/usr/local/share/moodle-ops.txt <<'MOODLE_OPS_EOF'
${MoodleBridge.help()}
MOODLE_OPS_EOF
chmod 644 ${'$'}R/usr/local/share/moodle-ops.txt
mkdir -p ${'$'}R/usr/local/lib
cat > ${'$'}R/usr/local/lib/moodle.py <<'MOODLE_PY_EOF'
${MoodleBridge.pythonModule().trimStart()}MOODLE_PY_EOF
chown 0:0 ${'$'}R/usr/local/lib/moodle.py; chmod 644 ${'$'}R/usr/local/lib/moodle.py
""" + (if (copyMaterials) """
rm -rf ${'$'}R/home/ai/lms
mkdir -p ${'$'}R/home/ai/lms
if [ -d ${shQuote(courses)} ]; then
  KB=${'$'}(du -sk ${shQuote(courses)} | cut -f1)
  if [ "${'$'}KB" -le $AI_MAX_COPY_KB ]; then
    cp -R ${shQuote(courses)}/. ${'$'}R/home/ai/lms/ && chown -hR 0:0 ${'$'}R/home/ai/lms && chmod -R a+rX,go-w ${'$'}R/home/ai/lms && echo MATERI_DISALIN
  else echo MATERI_TERLALU_BESAR; fi
fi
""" else "") + "echo AI_SIAP\n"

    /**
     * Jalankan [cmd] **bukan sebagai root**: `su ai` (hak diturunkan, grup tambahan dibuang sehingga tanpa grup inet Android), di namespace jaringan kosong
     * (`unshare -n`) bila kernel mendukung. /dev hanya null/zero/urandom/random bila /dev penuh tidak sedang di-mount; tanpa /sdcard dan tanpa data aplikasi.
     */
    fun aiExecScript(cmd: String, timeoutSec: Int = 50, bridgeDir: String? = null) = HEAD + """
mkdir -p ${'$'}R/dev ${'$'}R/etc
if ! grep -q " ${'$'}R/dev " /proc/mounts; then
  for n in null zero urandom random; do
    [ -e ${'$'}R/dev/${'$'}n ] || : > ${'$'}R/dev/${'$'}n
    grep -q " ${'$'}R/dev/${'$'}n " /proc/mounts || mount --bind /dev/${'$'}n ${'$'}R/dev/${'$'}n
  done
fi
NS=""
if chroot ${'$'}R /usr/bin/unshare -n /bin/true 2>/dev/null; then NS="/usr/bin/unshare -n"; else echo "PERINGATAN: isolasi jaringan tidak tersedia di kernel ini"; fi
""" + (if (bridgeDir != null) """
mkdir -p ${'$'}R/home/ai/bridge
grep -q " ${'$'}R/home/ai/bridge " /proc/mounts || mount --bind ${shQuote(bridgeDir)} ${'$'}R/home/ai/bridge || echo "PERINGATAN: jembatan Moodle tidak aktif"
""" else "") + """
chroot ${'$'}R /usr/bin/env -i HOME=/home/ai TERM=dumb PATH=$PATH PYTHONPATH=/usr/local/lib ${'$'}NS /bin/su -s /bin/sh -c ${shQuote(aiInner(cmd, timeoutSec))} ai 2>&1
""" + (if (bridgeDir != null) """RC=${'$'}?
grep -q " ${'$'}R/home/ai/bridge " /proc/mounts && umount ${'$'}R/home/ai/bridge
exit ${'$'}RC
""" else "")

    /** Lepas semua mount berurutan terbalik; mengeluarkan MASIH_ADA_MOUNT bila ada yang gagal. */
    fun unmountScript() = HEAD + """
for d in home/ai/bridge mnt/library mnt/lms mnt/sdcard dev dev/null dev/zero dev/urandom dev/random sys proc; do grep -q " ${'$'}R/${'$'}d " /proc/mounts && umount ${'$'}R/${'$'}d; done
if grep -q " ${'$'}R/" /proc/mounts; then echo MASIH_ADA_MOUNT; exit 3; fi
echo OK
"""

    /** Jalankan [cmd] di dalam chroot dengan lingkungan bersih. [cmd] hanya lewat shQuote. */
    fun execScript(cmd: String, courses: String, library: String) = mountScript(courses, library) +
        "chroot ${'$'}R /usr/bin/env -i HOME=/root TERM=xterm PATH=$PATH /bin/sh -c ${shQuote(cmd)} 2>&1\n"

    /** Hapus Linux: menolak bila masih ada mount di bawah ROOT (rm -rf lewat bind-mount akan menghapus penyimpanan asli). */
    fun removeScript() = HEAD + """
for d in home/ai/bridge mnt/library mnt/lms mnt/sdcard dev dev/null dev/zero dev/urandom dev/random sys proc; do grep -q " ${'$'}R/${'$'}d " /proc/mounts && umount ${'$'}R/${'$'}d; done
if grep -q " ${'$'}R/" /proc/mounts; then echo MASIH_ADA_MOUNT_DIBATALKAN; exit 3; fi
rm -rf ${'$'}R && echo OK
"""
}

data class LinuxState(
    val rootOk: Boolean? = null, val installed: Boolean = false, val mounted: Boolean = false, val version: String = "",
    /** Rootfs untuk CPU ini ada di dalam APK (pemasangan tanpa unduhan). */
    val bundled: Boolean = false,
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
    /** Spool jembatan Moodle baca-saja untuk perintah AI; null bila belum login. Token tetap di aplikasi (lihat [MoodleBridge]). */
    private val bridge: () -> BridgeSpool? = { null },
    /** Rootfs terbundel di APK untuk arsitektur Alpine yang diminta; null bila tidak ada (lalu diunduh). */
    private val bundle: (arch: String) -> Chroot.Bundle? = { null },
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

    /** Folder sumber mount dibuat oleh aplikasi sendiri; kalau dibuat root, pemiliknya jadi root dan aplikasi tak bisa menulis ke sana. */
    private fun prepareDirs() { coursesDir.mkdirs(); libraryDir.mkdirs() }

    private fun statusNow() {
        val r = runner.run(Chroot.checkScript(), 20)
        val kv = r.out.lines().mapNotNull { l -> l.trim().takeIf { it.contains('=') }?.let { it.substringBefore('=') to it.substringAfter('=') } }.toMap()
        val root = kv["UID"] == "0"
        val inst = root && kv["INSTALLED"] == "1"
        val bundled = Chroot.abiToAlpine(abis())?.let { bundle(it) } != null
        _state.update { it.copy(rootOk = root, installed = inst, mounted = inst && kv["MOUNTED"] == "1", version = kv["VER"].orEmpty(), bundled = bundled) }   // "mount aktif" hanya berarti bila Linux memang terpasang
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
        // Rootfs dari bundel APK bila ada (tanpa jaringan); checksum tetap wajib cocok dengan pin build, tanpa fallback diam-diam ke unduhan.
        val bundled = bundle(arch)
        val rel = if (bundled != null) bundled.release.also { log("Rootfs dari bundel aplikasi: ${it.file}") } else {
            step("Mengambil indeks rilis Alpine…")
            Chroot.parseRelease(fetch(Chroot.releasesBase(arch) + "latest-releases.yaml"), arch)?.also { log("Rilis: ${it.file} (${it.size / 1024} KB)") }
                ?: run { log("Indeks rilis Alpine tidak dikenali; dibatalkan."); return@task }
        }
        val tar = File(cacheDir, "alpine-rootfs.tar.gz")
        try {
            if (bundled != null) { step("Menyalin rootfs dari bundel…"); bundled.open().use { i -> tar.outputStream().use { o -> i.copyTo(o) } } }
            else { step("Mengunduh rootfs…", 0f); download(rel.url, tar, 40L * 1024 * 1024) { b -> if (rel.size > 0) _state.update { it.copy(progress = (b.toFloat() / rel.size).coerceIn(0f, 1f)) } } }
            step("Memverifikasi checksum…")
            if (Chroot.sha256(tar) != rel.sha256) { log("Checksum SHA-256 tidak cocok${if (bundled != null) " dengan pin bundel" else ""}; berkas dibuang dan tidak dipasang."); return@task }
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
            prepareDirs(); val m = runner.run(Chroot.mountScript(coursesDir.absolutePath, libraryDir.absolutePath), 60); if (m.out.isNotBlank()) log(m.out.trim())
            log("Alpine terpasang.")
        } finally { tar.delete() }
        statusNow()
    }

    /** Paket pengolah materi: Python, pypdf, poppler (teks PDF), jq (olah JSON keluaran `moodle`). */
    fun installTools(): Job? = exec("apk update && apk add --no-cache python3 py3-pip py3-pypdf poppler-utils jq && echo SELESAI", timeoutSec = 900, label = "Memasang Python, PDF & jq…")

    fun exec(cmd: String, timeoutSec: Long = 90, label: String = "Menjalankan…"): Job? {
        val c = cmd.trim(); if (c.isEmpty() || c.length > 4000) return null
        return task {
            if (_state.value.rootOk != true) { statusNow(); if (_state.value.rootOk != true) return@task }
            if (!_state.value.installed) { log("Linux belum terpasang."); return@task }
            step(label); log("$ ${c.take(300)}"); prepareDirs()
            val r = runner.run(Chroot.execScript(c, coursesDir.absolutePath, libraryDir.absolutePath), timeoutSec)
            if (r.out.isNotBlank()) log(r.out.trimEnd()); if (r.exit != 0) log("(kode keluar ${r.exit})")
            statusNow()
        }
    }

    @Volatile private var aiSetupAt = 0L
    private fun aiSetup(copy: Boolean): String {
        prepareDirs()
        val r = runner.run(Chroot.aiSetupScript(coursesDir.absolutePath, copy), 600)
        if (r.out.contains("AI_SIAP")) aiSetupAt = System.currentTimeMillis()
        return r.out.trim()
    }

    /** Segarkan salinan materi kursus yang dilihat AI (/home/ai/lms). Salinan dibuat ulang; maksimal ${Chroot.AI_MAX_COPY_KB / 1000} MB. */
    fun refreshAiMaterials(): Job? = task { step("Menyalin materi untuk AI…"); log(aiSetup(true).lines().joinToString("\n") { when (it) { "MATERI_DISALIN" -> "Salinan materi untuk AI diperbarui."; "MATERI_TERLALU_BESAR" -> "Materi terlalu besar untuk disalin; AI hanya punya /home/ai/work."; "AI_SIAP" -> "Sandbox AI siap."; else -> it } }) }

    /**
     * Dipakai Agen AI **setelah persetujuan** (lihat [CommandGate]): menjalankan [cmd] sebagai pengguna terbatas `ai`, bukan root. Sinkron.
     * Mengembalikan keluaran (dipangkas). Setiap perintah dan hasilnya juga dicatat di terminal Lab Linux.
     */
    fun runForAi(cmd: String): String {
        val c = cmd.trim()
        if (c.isEmpty() || c.length > 2000) return "Perintah kosong atau lebih dari 2000 karakter; tidak dijalankan."
        synchronized(lock) { if (_state.value.busy) return "Lab Linux sedang dipakai proses lain; coba lagi sebentar."; _state.update { it.copy(busy = true, step = "Perintah AI berjalan…") } }
        try {
            if (_state.value.rootOk != true || !_state.value.installed) statusNow()
            if (_state.value.rootOk != true || !_state.value.installed) return "Linux belum terpasang atau root tidak tersedia."
            val fresh = System.currentTimeMillis() - aiSetupAt < 30 * 60_000L
            if (!fresh) { val s = aiSetup(copy = aiSetupAt == 0L); if (!s.contains("AI_SIAP")) return "Sandbox AI gagal disiapkan: ${s.take(300)}" }
            log("[AI] $ ${c.take(300)}")
            val spool = bridge()
            val r = if (spool == null) runner.run(Chroot.aiExecScript(c), 75)
                    else spool.serve { runner.run(Chroot.aiExecScript(c, bridgeDir = spool.dir.absolutePath), 75) }
            val out = r.out.trim()
            log(out.take(2000).ifEmpty { "(tanpa keluaran)" })
            // jejak jembatan: setiap operasi Moodle yang dilayani selama perintah ini terlihat di terminal dan di balasan ke model
            val served = spool?.served?.toList().orEmpty()
            val trace = if (served.isEmpty()) "" else "\n[jembatan Moodle: ${served.joinToString(", ")}]".also { log("[jembatan Moodle] " + served.joinToString(", ")) }
            return (out.take(6000).ifEmpty { "(tanpa keluaran)" }) + (if (r.exit != 0) "\n(kode keluar ${r.exit})" else "") + trace
        } finally { _state.update { it.copy(busy = false, step = "") } }
    }

    fun mount(): Job? = task { step("Mount…"); prepareDirs(); val r = runner.run(Chroot.mountScript(coursesDir.absolutePath, libraryDir.absolutePath), 60); log(r.out.trim().ifEmpty { "Mount siap: /mnt/lms (materi, read-only), /mnt/library, /mnt/sdcard" }); statusNow() }
    fun unmount(): Job? = task { step("Melepas mount…"); val r = runner.run(Chroot.unmountScript(), 60); log(r.out.trim()); statusNow() }
    fun remove(): Job? = task {
        step("Menghapus Linux…"); val r = runner.run(Chroot.removeScript(), 180)
        log(if (r.exit == 0) "Linux dihapus." else "Dibatalkan: ${r.out.trim()}"); statusNow()
    }
}
