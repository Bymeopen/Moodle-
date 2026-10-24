package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------- model netral percakapan agen (tiap penyedia mengubahnya ke format sendiri) ----------

/** Alat yang boleh dipanggil model. [params] = JSON Schema objek. */
data class ToolSpec(val name: String, val description: String, val params: JSONObject)
data class ToolCall(val id: String, val name: String, val args: JSONObject)

sealed interface AgentMsg {
    data class User(val text: String, val docs: List<Doc> = emptyList()) : AgentMsg
    /** [raw] = isi asli dari penyedia (Gemini: parts, Claude: content) yang harus diputar ulang apa adanya (mis. thoughtSignature Gemini). */
    data class Assistant(val text: String, val calls: List<ToolCall>, val raw: JSONArray? = null) : AgentMsg
    data class Results(val results: List<Pair<ToolCall, String>>) : AgentMsg
}

data class AgentReply(val text: String, val calls: List<ToolCall>, val raw: JSONArray?, val usage: Usage = Usage())

// ---------- aksi kendali aplikasi (diisi MainViewModel; dipisah agar alat bisa dites) ----------

interface LmsActions {
    /** Sinkron Moodle sekarang (memblokir); mengembalikan ringkasan. */
    fun sync(): String
    fun downloadCourse(courseId: Long): String
    fun markDone(itemId: Long, done: Boolean)
    fun openPage(url: String): String
    fun addCards(courseId: Long, cards: List<Draft>): String
    fun librarySearch(source: LibSource, query: String): List<LibItem>
    /** Siapkan paket belajar kursus (teks per bagian + tautan + PDF) dan buka menu bagikan. */
    fun exportPack(courseId: Long): String
}

/**
 * Alat LMS untuk agen. Baca: daftar kursus, tenggat, isi item (termasuk forum dan tinjauan tes), nilai, cari materi, file terunduh, perpustakaan.
 * Kendali (lokal dan bisa dibatalkan): sinkron, unduh kursus, tandai tugas selesai, buat kartu, buka halaman.
 * Sengaja TIDAK ada: mengirim tugas, mengerjakan tes, atau memposting di forum atas nama pengguna.
 * Hasil alat berisi teks dari Moodle/pengguna lain: diperlakukan sebagai data oleh model dan dipangkas ukurannya.
 */
class LmsTools(
    private val snapshot: () -> Snapshot?, private val done: () -> Set<Long>,
    private val textOf: (Course, Item, FileRef) -> String?, private val isDownloaded: (Course, Item, FileRef) -> Boolean,
    private val actions: LmsActions, private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        const val MAX_RESULT = 7000
        private fun obj(vararg props: Pair<String, String>, required: List<String> = emptyList()) = JSONObject(
            """{"type":"object","properties":{${props.joinToString(",") { "\"${it.first}\":${it.second}" }}},"required":${JSONArray(required)}}""")
        private val int = """{"type":"integer"}"""
        private val str = """{"type":"string"}"""
        private fun s(desc: String) = """{"type":"string","description":"$desc"}"""
        private fun i(desc: String) = """{"type":"integer","description":"$desc"}"""
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec("list_courses", "Daftar kursus pengguna: id, nama, jumlah item/tugas/file, file terunduh, persen nilai.", obj()),
        ToolSpec("list_deadlines", "Tugas dan tes dengan tenggat: kursus, id item, judul, waktu tenggat, sisa jam, status selesai. Termasuk yang terlambat dan belum selesai.",
            obj("days" to i("Jangkauan hari ke depan (default 14)"), "include_done" to """{"type":"boolean","description":"Sertakan yang sudah ditandai selesai"}""")),
        ToolSpec("get_course", "Isi satu kursus per bagian: id, jenis, judul item, tenggat, dan daftar file (indeks, ukuran, sudah diunduh atau belum).", obj("course_id" to int, required = listOf("course_id"))),
        ToolSpec("read_item", "Baca lengkap satu item: teks halaman/instruksi tugas, isi forum, info tes beserta riwayat percobaan dan soal-jawaban yang tersimpan.",
            obj("course_id" to int, "item_id" to int, required = listOf("course_id", "item_id"))),
        ToolSpec("read_file", "Baca teks file yang SUDAH diunduh (docx, pptx, txt, md, csv). PDF tidak bisa dibaca lewat alat ini.",
            obj("course_id" to int, "item_id" to int, "file_index" to i("Indeks file dari get_course/read_item"), required = listOf("course_id", "item_id", "file_index"))),
        ToolSpec("get_grades", "Nilai pengguna per kursus (semua kursus bila course_id kosong).", obj("course_id" to int)),
        ToolSpec("search_materials", "Cari kata di judul dan isi materi (halaman, tugas, forum, tes).", obj("query" to str, "course_id" to int, required = listOf("query"))),
        ToolSpec("library_search", "Cari di perpustakaan gratis. source: journals (DOAJ), campus (repositori kampus Muhammadiyah), books (Internet Archive, umumnya Inggris).",
            obj("source" to """{"type":"string","enum":["journals","campus","books"]}""", "query" to str, required = listOf("source", "query"))),
        ToolSpec("sync_now", "Sinkronkan data dari Moodle sekarang (kursus, tugas, tes, forum, nilai).", obj()),
        ToolSpec("download_course", "Mulai mengunduh semua file kursus yang belum diunduh untuk dipakai offline.", obj("course_id" to int, required = listOf("course_id"))),
        ToolSpec("mark_task_done", "Tandai tugas/tes selesai atau belum selesai di aplikasi (hanya penanda lokal, tidak mengirim apa pun ke Moodle).",
            obj("item_id" to int, "done" to """{"type":"boolean"}""", required = listOf("item_id", "done"))),
        ToolSpec("open_page", "Buka halaman Moodle sebuah item (tes, forum, tugas, rekaman) di dalam aplikasi untuk dikerjakan pengguna sendiri.",
            obj("course_id" to int, "item_id" to int, required = listOf("course_id", "item_id"))),
        ToolSpec("export_study_pack", "Siapkan paket belajar sebuah kursus (sumber teks per bagian, daftar tautan, PDF terunduh) dan buka menu bagikan Android agar pengguna mengirimnya ke NotebookLM (podcast/Audio Overview, panduan belajar), Gemini, Claude, atau Drive.",
            obj("course_id" to int, required = listOf("course_id"))),
        ToolSpec("create_flashcards", "Simpan kartu hafalan (maks 30) ke deck pengguna untuk sebuah kursus. Tiap kartu: front (pertanyaan singkat) dan back (jawaban ringkas).",
            obj("course_id" to int, "cards" to """{"type":"array","items":{"type":"object","properties":{"front":$str,"back":$str},"required":["front","back"]}}""", required = listOf("course_id", "cards"))),
    )

    /** Kalimat singkat untuk jejak aksi di chat. */
    fun describe(c: ToolCall): String = when (c.name) {
        "list_courses" -> "Melihat daftar kursus"; "list_deadlines" -> "Memeriksa tenggat"; "get_course" -> "Membuka kursus ${courseName(c.args.optLong("course_id"))}"
        "read_item" -> "Membaca ${findItem(c.args.optLong("course_id"), c.args.optLong("item_id"))?.second?.title ?: "item"}"
        "read_file" -> "Membaca file materi"; "get_grades" -> "Memeriksa nilai"; "search_materials" -> "Mencari \"${c.args.optString("query").take(40)}\" di materi"
        "library_search" -> "Mencari di perpustakaan: ${c.args.optString("query").take(40)}"; "sync_now" -> "Sinkron dari Moodle"
        "download_course" -> "Mengunduh kursus ${courseName(c.args.optLong("course_id"))}"
        "mark_task_done" -> (if (c.args.optBoolean("done", true)) "Menandai selesai: " else "Menandai belum selesai: ") + (items().firstOrNull { it.second.id == c.args.optLong("item_id") }?.second?.title ?: "tugas")
        "open_page" -> "Membuka halaman di aplikasi"; "export_study_pack" -> "Menyiapkan paket belajar ${courseName(c.args.optLong("course_id"))}"; "create_flashcards" -> "Membuat ${c.args.optJSONArray("cards")?.length() ?: 0} kartu hafalan"
        else -> c.name
    }

    private fun courses() = snapshot()?.courses.orEmpty()
    private fun items() = courses().flatMap { c -> c.items.map { c to it } }
    private fun findItem(courseId: Long, itemId: Long) = courses().firstOrNull { it.id == courseId }?.let { c -> c.items.firstOrNull { it.id == itemId }?.let { c to it } }
    private fun courseName(id: Long) = courses().firstOrNull { it.id == id }?.name ?: "?"
    private fun iso(sec: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

    private fun fileJson(c: Course, i: Item, f: FileRef, idx: Int) = JSONObject().put("index", idx).put("name", f.name).put("size_bytes", f.size).put("downloaded", isDownloaded(c, i, f))

    private fun itemBrief(c: Course, i: Item) = JSONObject().put("id", i.id).put("type", i.type).put("title", i.title).apply {
        i.dueAt?.let { put("due", iso(it)) }
        if (i.files.isNotEmpty()) put("files", JSONArray(i.files.mapIndexed { n, f -> fileJson(c, i, f, n) }))
    }

    /** Jalankan alat. Selalu mengembalikan teks (galat juga berupa teks) agar model bisa memperbaiki panggilannya; tidak pernah melempar. */
    fun execute(name: String, a: JSONObject): String = try { run(name, a).take(MAX_RESULT) } catch (e: Exception) { "Galat: ${e.message ?: e.javaClass.simpleName}" }

    private fun run(name: String, a: JSONObject): String {
        fun reqLong(k: String): Long = if (a.has(k) && !a.isNull(k)) a.optLong(k, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: throw IllegalArgumentException("parameter $k harus angka") else throw IllegalArgumentException("parameter $k wajib")
        fun course(): Course = courses().firstOrNull { it.id == reqLong("course_id") } ?: throw IllegalArgumentException("kursus tidak ditemukan; panggil list_courses")
        if (snapshot() == null && name !in setOf("library_search", "sync_now")) return "Belum ada data kursus. Panggil sync_now dulu."
        return when (name) {
            "list_courses" -> JSONArray(courses().map { c ->
                val files = c.items.sumOf { it.files.size }; val got = c.items.sumOf { i -> i.files.count { isDownloaded(c, i, it) } }
                JSONObject().put("id", c.id).put("name", c.name).put("items", c.items.size).put("tasks", c.items.count { it.isTask }).put("files", files).put("files_downloaded", got)
                    .put("grade_percent", Logic.gradeSummary(c).percent?.let { Math.round(it) } ?: JSONObject.NULL)
            }).toString()
            "list_deadlines" -> {
                val now = nowSec(); val days = a.optInt("days", 14).coerceIn(1, 120); val doneSet = done(); val withDone = a.optBoolean("include_done", false)
                JSONArray(items().filter { (_, i) -> i.isTask && i.dueAt != null && (withDone || i.id !in doneSet) && i.dueAt!! <= now + days * 86400L && i.dueAt!! >= now - 7 * 86400L }
                    .sortedBy { it.second.dueAt }.take(40).map { (c, i) ->
                        JSONObject().put("course_id", c.id).put("course", c.name).put("item_id", i.id).put("type", i.type).put("title", i.title).put("due", iso(i.dueAt!!))
                            .put("hours_left", (i.dueAt!! - now) / 3600).put("done", i.id in doneSet).apply { if (i.type == "quiz") put("finished_attempts", i.attempts.count { it.state == "finished" }) }
                    }).toString()
            }
            "get_course" -> {
                val c = course()
                JSONObject().put("id", c.id).put("name", c.name).put("summary", c.summary.take(400)).put("sections", JSONArray(c.items.groupBy { it.section }.map { (sec, list) ->
                    JSONObject().put("section", sec.ifBlank { "Umum" }).put("items", JSONArray(list.map { itemBrief(c, it) }))
                })).toString()
            }
            "read_item" -> {
                val (c, i) = findItem(reqLong("course_id"), reqLong("item_id")) ?: throw IllegalArgumentException("item tidak ditemukan; panggil get_course")
                JSONObject().put("id", i.id).put("type", i.type).put("title", i.title).put("section", i.section).put("content", if (i.type == "link") "" else i.content.take(5000))
                    .apply {
                        if (i.type == "link" && i.content.startsWith("http")) put("link", i.content)
                        i.dueAt?.let { put("due", iso(it)) }; i.maxGrade?.let { put("max_grade", it) }; put("done", i.id in done())
                        if (i.files.isNotEmpty()) put("files", JSONArray(i.files.mapIndexed { n, f -> fileJson(c, i, f, n) }))
                        if (i.attempts.isNotEmpty()) put("attempts", JSONArray(i.attempts.map { t ->
                            JSONObject().put("number", t.number).put("state", t.state).put("grade", t.grade ?: JSONObject.NULL).put("max", t.max ?: JSONObject.NULL).put("review", t.review.take(2500))
                        }))
                    }.toString()
            }
            "read_file" -> {
                val (c, i) = findItem(reqLong("course_id"), reqLong("item_id")) ?: throw IllegalArgumentException("item tidak ditemukan")
                val f = i.files.getOrNull(reqLong("file_index").toInt()) ?: throw IllegalArgumentException("indeks file tidak ada")
                if (!isDownloaded(c, i, f)) "File belum diunduh. Gunakan download_course dulu."
                else textOf(c, i, f) ?: "Isi file ini tidak bisa dibaca sebagai teks (PDF/format lain). Minta pengguna melampirkan PDF di chat."
            }
            "get_grades" -> JSONArray((if (a.has("course_id") && !a.isNull("course_id")) listOf(course()) else courses()).map { c ->
                val g = Logic.gradeSummary(c)
                JSONObject().put("course_id", c.id).put("course", c.name).put("percent", g.percent?.let { Math.round(it) } ?: JSONObject.NULL)
                    .put("rows", JSONArray(c.grades.take(30).map { JSONObject().put("name", it.name).put("grade", it.grade ?: JSONObject.NULL).put("max", it.max ?: JSONObject.NULL) }))
            }).toString()
            "search_materials" -> {
                val q = a.optString("query").trim(); if (q.length < 2) throw IllegalArgumentException("kata pencarian terlalu pendek")
                val scope = if (a.has("course_id") && !a.isNull("course_id")) listOf(course()) else courses()
                JSONArray(scope.flatMap { c -> c.items.mapNotNull { i ->
                    val hay = i.title + "\n" + i.content
                    val at = hay.indexOf(q, ignoreCase = true)
                    if (at < 0) null else JSONObject().put("course_id", c.id).put("course", c.name).put("item_id", i.id).put("title", i.title).put("snippet", hay.substring(maxOf(0, at - 60), minOf(hay.length, at + 140)).replace('\n', ' '))
                } }.take(15)).toString()
            }
            "library_search" -> {
                val src = when (a.optString("source")) { "journals" -> LibSource.JOURNALS; "campus" -> LibSource.CAMPUS; "books" -> LibSource.BOOKS; else -> throw IllegalArgumentException("source harus journals, campus, atau books") }
                JSONArray(actions.librarySearch(src, a.optString("query")).take(8).map { JSONObject().put("title", it.title).put("author", it.author).put("year", it.year).put("url", it.url) }).toString()
            }
            "sync_now" -> actions.sync()
            "download_course" -> actions.downloadCourse(course().id)
            "mark_task_done" -> {
                val id = reqLong("item_id"); val (_, it) = items().firstOrNull { p -> p.second.id == id && p.second.isTask } ?: throw IllegalArgumentException("tugas/tes dengan id itu tidak ada")
                val d = a.optBoolean("done", true); actions.markDone(id, d); "OK: \"${it.title}\" ditandai ${if (d) "selesai" else "belum selesai"} (lokal)"
            }
            "open_page" -> {
                val (_, i) = findItem(reqLong("course_id"), reqLong("item_id")) ?: throw IllegalArgumentException("item tidak ditemukan")
                if (i.url.isEmpty()) "Item ini tidak punya halaman Moodle." else actions.openPage(i.url)
            }
            "export_study_pack" -> actions.exportPack(course().id)
            "create_flashcards" -> {
                val c = course(); val arr = a.optJSONArray("cards") ?: throw IllegalArgumentException("cards wajib")
                val cards = (0 until minOf(arr.length(), 30)).mapNotNull { arr.optJSONObject(it) }.map { Draft(it.optString("front").trim(), it.optString("back").trim()) }
                    .filter { it.front.isNotEmpty() && it.back.isNotEmpty() && it.front.length <= 2000 && it.back.length <= 2000 }
                if (cards.isEmpty()) throw IllegalArgumentException("tidak ada kartu valid") else actions.addCards(c.id, cards)
            }
            else -> throw IllegalArgumentException("alat tidak dikenal: $name")
        }
    }
}

/**
 * Loop agen: model memanggil alat, hasilnya dikembalikan, berulang sampai model menjawab. Dibatasi [maxSteps] putaran dan [maxCalls] panggilan alat
 * agar biaya dan efek tidak lepas kendali; setelah batas, model diminta menjawab dari data yang ada.
 */
class AgentRunner(private val backend: AiBackend, private val tools: LmsTools, private val maxSteps: Int = 8, private val maxCalls: Int = 16) {
    data class Result(val text: String, val usage: Usage, val calls: Int)

    fun run(system: String, history: List<AgentMsg>, shouldStop: () -> Boolean = { false }, onTrace: (String) -> Unit = {}, onUsage: (Usage) -> Unit = {}): Result {
        val msgs = history.toMutableList(); var usage = Usage(); var calls = 0
        for (step in 0..maxSteps) {
            if (shouldStop()) return Result("", usage, calls)
            val last = step == maxSteps
            if (last) msgs += AgentMsg.User("Batas langkah alat tercapai. Berikan jawaban akhir sekarang dari data yang sudah kamu kumpulkan, tanpa memanggil alat.")
            val r = backend.step(system, msgs, tools.specs)
            usage += r.usage; onUsage(usage)
            if (r.calls.isEmpty() || last) return Result(r.text, usage, calls)
            msgs += AgentMsg.Assistant(r.text, r.calls, r.raw)
            val results = r.calls.map { c ->
                if (shouldStop()) return Result("", usage, calls)
                val out = if (calls >= maxCalls) "Dilewati: batas panggilan alat tercapai." else { calls++; onTrace(tools.describe(c)); tools.execute(c.name, c.args) }
                c to out
            }
            msgs += AgentMsg.Results(results)
        }
        return Result("", usage, calls)
    }
}
