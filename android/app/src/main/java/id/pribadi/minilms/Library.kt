package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Sumber perpustakaan gratis tanpa kunci API. */
enum class LibSource(val label: String, val hint: String) {
    BOOKS("Buku", "Internet Archive: buku domain publik & terbuka, hampir semuanya berbahasa Inggris (PDF bisa diunduh)"),
    JOURNALS("Jurnal", "DOAJ: artikel jurnal akses terbuka, banyak berbahasa Indonesia. Ketuk unduh untuk PDF offline."),
    CAMPUS("Repositori PTM", "Skripsi, tesis, dan artikel dosen dari repositori perguruan tinggi Muhammadiyah. Sebagian hanya menampilkan abstrak/bab awal."),
}

/** Repositori EPrints perguruan tinggi Muhammadiyah. [verified] = sudah dicoba berhasil diakses dari aplikasi; lainnya mungkin menolak akses non-browser. */
data class Campus(val name: String, val host: String, val verified: Boolean = false)

/** Topik pencarian buku (koleksi Internet Archive hampir semuanya berbahasa Inggris). */
val ENGLISH_TOPICS = listOf("management", "marketing", "accounting", "economics", "entrepreneurship", "finance", "organizational behavior")

/** Topik jurusan manajemen untuk pencarian cepat. */
val MANAGEMENT_TOPICS = listOf("manajemen", "manajemen keuangan", "manajemen pemasaran", "manajemen sumber daya manusia", "kewirausahaan", "akuntansi", "perilaku organisasi", "bisnis")

/** Satu hasil pencarian. [id] = identifier Internet Archive (BOOKS) atau id DOAJ; [url] = halaman/teks lengkap untuk dibuka di browser. */
data class LibItem(val source: LibSource, val id: String, val title: String, val author: String, val year: String, val note: String, val url: String)

/** Buku yang sudah diunduh ke ponsel. */
data class LibSaved(val id: String, val title: String, val author: String, val file: String, val size: Long)

/** Lapisan GET yang bisa diganti di tes. Tanpa token Moodle: hanya data publik. */
fun interface HttpGet { fun get(url: String): String }

object LibraryApi {
    var http: HttpGet = HttpGet { PublicHttp.getText(it) }

    val campuses = listOf(
        Campus("UMS Surakarta", "eprints.ums.ac.id", true), Campus("UMG Gresik", "eprints.umg.ac.id", true), Campus("UM Surabaya", "repository.um-surabaya.ac.id", true),
        Campus("UMM Malang", "eprints.umm.ac.id"), Campus("UAD Yogyakarta", "eprints.uad.ac.id"), Campus("UMSIDA Sidoarjo", "eprints.umsida.ac.id"), Campus("UHAMKA Jakarta", "repository.uhamka.ac.id"),
    )

    /** Hasil pencarian halaman EPrints standar (daftar `ep_search_result`): judul, penulis, tahun, jenis; hanya tautan di host kampus itu sendiri. */
    fun parseEprints(html: String, host: String): List<LibItem> {
        val linkRe = Regex("""<a href="https?://${Regex.escape(host)}/(\d+)/"><em>(.*?)</em></a>(.*?)(?=</td>)""", RegexOption.DOT_MATCHES_ALL)
        return html.split("class=\"ep_search_result\"").drop(1).mapNotNull { row ->
            val m = linkRe.find(row) ?: return@mapNotNull null
            val authors = Regex("""person_name">([^<]*)<""").findAll(row.substring(0, m.range.first)).map { clean(it.groupValues[1]).trim(' ', ',') }.filter { it.isNotEmpty() }.joinToString(", ")
            val year = Regex("""\((\d{4})\)""").find(row.substring(0, m.range.first))?.groupValues?.get(1).orEmpty()
            LibItem(LibSource.CAMPUS, "$host/${m.groupValues[1]}", clean(m.groupValues[2]).trimEnd('.'), authors, year, clean(m.groupValues[3]).trim(' ', ',', '.'), "https://$host/${m.groupValues[1]}/")
        }.distinctBy { it.id }
    }

    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()

    fun searchCampus(c: Campus, query: String): List<LibItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return parseEprints(http.get("https://${c.host}/cgi/search/archive/simple?q=${enc(q)}&_action_search=Search&order=${enc("-date/creators_name/title")}"), c.host).take(25)
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(source: LibSource, query: String, campus: Campus = campuses[0]): List<LibItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return when (source) {
            LibSource.CAMPUS -> searchCampus(campus, q)
            LibSource.BOOKS -> parseArchive(http.get("https://archive.org/advancedsearch.php?q=${enc("(${luceneSafe(q)}) AND mediatype:texts AND access-restricted-item:false")}&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year&sort[]=downloads+desc&rows=25&output=json"))
            LibSource.JOURNALS -> parseDoaj(http.get("https://doaj.org/api/search/articles/${enc(q)}?pageSize=25"))
        }
    }

    /** Buang operator Lucene dari input pengguna (tanda kurung/kutip tak berpasangan membuat Internet Archive membalas galat). */
    fun luceneSafe(q: String): String = q.replace(Regex("""[+\-&|!(){}\[\]^"~*?:\\/]"""), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "a" }

    fun parseArchive(json: String): List<LibItem> {
        val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return docs.objects().mapNotNull { d ->
            val id = d.optString("identifier").takeIf { it.matches(ID_RE) } ?: return@mapNotNull null     // hanya id aman (dipakai membentuk URL)
            LibItem(LibSource.BOOKS, id, d.optString("title", id).ifBlank { id }, d.flat("creator"), d.flat("year"), "", "https://archive.org/details/$id")
        }.distinctBy { it.id }.toList()
    }

    fun parseDoaj(json: String): List<LibItem> {
        val rs = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return rs.objects().mapNotNull { r ->
            val b = r.optJSONObject("bibjson") ?: return@mapNotNull null
            val link = b.optJSONArray("link")?.objects()?.firstOrNull { it.optString("type") == "fulltext" }?.optString("url")
                ?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && PublicHttp.publicHost(WebPolicy.hostOf(it) ?: "") } ?: return@mapNotNull null
            val authors = b.optJSONArray("author")?.objects()?.map { it.optString("name") }?.filter { it.isNotBlank() }?.joinToString(", ").orEmpty()
            if (r.optString("id").isBlank() || b.optString("title").isBlank()) return@mapNotNull null
            LibItem(LibSource.JOURNALS, r.optString("id"), b.optString("title"), authors, b.optString("year"), b.optJSONObject("journal")?.optString("title").orEmpty(), link)
        }.distinctBy { it.id }.toList()
    }

    /** Pilih satu file PDF dari metadata Internet Archive (yang terkecil di atas 10 KB, agar bukan sampul). Null bila tidak ada PDF. */
    fun pickPdf(metadataJson: String): Pair<String, Long>? {
        val files = JSONObject(metadataJson).optJSONArray("files") ?: return null
        return files.objects().filter { it.optString("name").endsWith(".pdf", true) && !it.optString("name").contains('/') }
            .map { it.getString("name") to (it.optString("size").toLongOrNull() ?: 0L) }
            .filter { it.second > 10_000 }.minByOrNull { it.second }
    }

    fun pdfFor(id: String): Pair<String, Long>? = pickPdf(http.get("https://archive.org/metadata/$id"))

    fun downloadUrl(id: String, name: String) = "https://archive.org/download/$id/${enc(name).replace("+", "%20")}"

    val ID_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}")
    private fun JSONObject.flat(k: String): String = when (val v = opt(k)) { is JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }; null, JSONObject.NULL -> ""; else -> v.toString() }
    private fun JSONArray.objects() = (0 until length()).asSequence().mapNotNull { optJSONObject(it) }
}

/** HTTP GET publik: hanya https, alihan diikuti manual maksimal 4 kali dan hanya ke host tepercaya. */
object PublicHttp {
    /** Boleh host publik apa pun (jurnal/repositori tersebar di banyak domain), tetapi bukan IP, localhost, atau jaringan internal. Tanpa token Moodle. */
    fun publicHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (!h.contains('.') || h == "localhost" || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".localhost")) return false
        if (Regex("[0-9.]+").matches(h) || h.contains(':') || h.startsWith("[")) return false                    // IPv4/IPv6 literal
        return true
    }

    /** Alamat jaringan internal (loopback, privat, link-local) tidak boleh dijangkau walau namanya tampak publik. */
    fun blockedAddress(a: java.net.InetAddress) = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
        (a.address.size == 16 && (a.address[0].toInt() and 0xFE) == 0xFC)                                           // fc00::/7 (ULA)

    private fun open(start: String): HttpURLConnection {
        var url = start
        repeat(5) {
            val u = URL(url)
            if (u.protocol != "https" || !publicHost(u.host)) throw ApiError("Alamat tidak diizinkan: ${u.host}")
            if (java.net.InetAddress.getAllByName(u.host).any { blockedAddress(it) }) throw ApiError("Alamat tidak diizinkan: ${u.host}")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "MiniLMS/2.4"); c.setRequestProperty("Accept", "application/json,*/*")
            val code = c.responseCode
            if (code in 300..399) { url = URL(u, c.getHeaderField("Location") ?: throw ApiError("Alihan tanpa tujuan")).toString(); c.disconnect(); return@repeat }
            if (code != 200) { c.disconnect(); throw ApiError("Server perpustakaan membalas HTTP $code") }
            return c
        }
        throw ApiError("Terlalu banyak alihan")
    }

    /** Baca teks (HTML/JSON) maksimal [maxChars] karakter. */
    fun getText(url: String, maxChars: Int = 3_000_000): String { val c = open(url); try { return c.inputStream.bufferedReader().use { r -> val b = CharArray(maxChars + 1); var n = 0; while (n < b.size) { val k = r.read(b, n, b.size - n); if (k < 0) break; n += k }; String(b, 0, minOf(n, maxChars)) } } finally { c.disconnect() } }

    /** Unduh ke [dest] lewat file .part; berhenti dan hapus bila [shouldStop]. */
    fun download(url: String, dest: File, maxBytes: Long, shouldStop: () -> Boolean, onBytes: (Long) -> Unit) {
        val c = open(url)
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            dest.parentFile!!.mkdirs()
            var total = 0L
            c.inputStream.use { i -> part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (shouldStop()) throw ApiError("Dibatalkan")
                    val n = i.read(buf); if (n < 0) break
                    total += n; if (total > maxBytes) throw ApiError("File terlalu besar")
                    o.write(buf, 0, n); onBytes(total)
                }
            } }
            if (!part.renameTo(dest)) throw ApiError("Gagal menyimpan file")
        } finally { c.disconnect(); if (part.exists()) part.delete() }
    }
}

/** Mencari alamat PDF dari halaman artikel/skripsi: meta Highwire (OJS, banyak jurnal), meta EPrints, atau tautan .pdf. */
object PdfResolver {
    private val metaRes = listOf(
        Regex("""<meta[^>]+name=["']citation_pdf_url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+name=["']citation_pdf_url["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=["']eprints\.document_url["'][^>]+content=["']([^"']+\.pdf)["']""", RegexOption.IGNORE_CASE),
        Regex("""<a[^>]+href=["']([^"']+\.pdf)["']""", RegexOption.IGNORE_CASE),
    )

    fun isDirectPdf(url: String) = try { java.net.URI(url).path.lowercase().endsWith(".pdf") } catch (e: Exception) { false }

    /** Alamat PDF (mutlak, https) dari [html] halaman [pageUrl]; null bila tidak ketemu. */
    fun find(html: String, pageUrl: String): String? {
        for (re in metaRes) {
            val raw = re.find(html)?.groupValues?.get(1)?.replace("&amp;", "&") ?: continue
            val abs = try { java.net.URI(pageUrl).resolve(raw).toString() } catch (e: Exception) { continue }
            val u = if (abs.startsWith("http://")) "https://" + abs.removePrefix("http://") else abs
            if (u.startsWith("https://") && PublicHttp.publicHost(WebPolicy.hostOf(u) ?: "")) return u
        }
        return null
    }

    /** PDF untuk halaman [pageUrl]: tautan langsung, atau cari di isi halamannya. */
    fun resolve(pageUrl: String, fetch: (String) -> String = { PublicHttp.getText(it) }): String? =
        if (isDirectPdf(pageUrl)) pageUrl else find(fetch(pageUrl), pageUrl)
}

/** Indeks buku perpustakaan yang tersimpan di filesDir/library (publik, tidak dihapus saat logout). */
class LibraryStore(private val dir: File) {
    private val index = File(dir, "index.json")
    fun fileOf(s: LibSaved) = File(dir, s.file)

    @Synchronized fun list(): List<LibSaved> = try {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { LibSaved(it.getString("id"), it.getString("title"), it.optString("author"), it.getString("file"), it.optLong("size")) }
            .filter { File(dir, it.file).exists() }
    } catch (e: Exception) { emptyList() }      // belum ada/rusak: anggap kosong

    @Synchronized fun add(s: LibSaved) {
        val all = list().filter { it.id != s.id } + s
        dir.mkdirs()
        index.writeText(JSONArray(all.map { JSONObject().put("id", it.id).put("title", it.title).put("author", it.author).put("file", it.file).put("size", it.size) }).toString())
    }

    @Synchronized fun remove(id: String) {
        val all = list(); all.firstOrNull { it.id == id }?.let { File(dir, it.file).delete() }
        index.writeText(JSONArray(all.filter { it.id != id }.map { JSONObject().put("id", it.id).put("title", it.title).put("author", it.author).put("file", it.file).put("size", it.size) }).toString())
    }
}
