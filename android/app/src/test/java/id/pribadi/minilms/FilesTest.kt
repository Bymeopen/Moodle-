package id.pribadi.minilms

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, String>): File = tmp.newFile(name).also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }

    @Test fun docxTextExtraction() {
        val f = zip("a.docx", mapOf("word/document.xml" to """<w:document><w:body><w:p><w:r><w:t>Judul &amp; Bab</w:t></w:r></w:p><w:p><w:r><w:t>Isi</w:t></w:r><w:tab/><w:r><w:t>lanjut</w:t></w:r></w:p></w:body></w:document>"""))
        assertEquals("Judul & Bab\nIsi\tlanjut", FileText.docx(f))
    }

    @Test fun pptxSlidesInNumericOrder() {
        val f = zip("a.pptx", mapOf(
            "ppt/slides/slide10.xml" to "<p:sld><a:p><a:r><a:t>Sepuluh</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>Dua</a:t></a:r></a:p><a:p><a:r><a:t>Poin</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>Satu</a:t></a:r></a:p></p:sld>"))
        assertEquals("[Slide 1]\nSatu\n\n[Slide 2]\nDua\nPoin\n\n[Slide 10]\nSepuluh", FileText.pptx(f))
    }

    @Test fun extractDispatchAndFailuresReturnNull() {
        assertEquals("catatan", FileText.extract(tmp.newFile("n.txt").also { it.writeText("catatan") }, "n.txt"))
        assertNull(FileText.extract(tmp.newFile("x.pdf"), "x.pdf"))                         // PDF tidak diekstrak (dikirim sebagai dokumen)
        assertNull(FileText.extract(tmp.newFile("rusak.docx").also { it.writeText("bukan zip") }, "rusak.docx"))
        assertTrue(FileText.isPdf("Slide.PDF", null)); assertTrue(FileText.isPdf("x", "application/pdf")); assertFalse(FileText.isPdf("a.docx", null))
    }

    @Test fun materialIncludesExtractedFileTextAndListsUnread() {
        val c = Course(1, "K", "", listOf(Item(2, "link", "Bahan", "", "https://x", null, null,
            listOf(FileRef("catatan.docx", "/", "u1", 1, null, 1), FileRef("slide.pdf", "/", "u2", 1, null, 1)))), emptyList())
        val t = AiMaterial.build(c) { _, f -> if (f.name.endsWith("docx")) "ISI DOCX" else null }
        assertTrue(t.contains("#### Isi file catatan.docx\nISI DOCX")); assertTrue(t.contains("File terlampir (isi tidak dibaca): slide.pdf"))
    }

    @Test fun claudeBodyAttachesPdfDocuments() {
        val b = ClaudeClient.body("claude-opus-5-5", "s", listOf(ChatMsg("user", "ringkas", listOf(Doc("a.pdf", "QUJD")))), stream = false)
        val content = b.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        val doc = content.getJSONObject(0)
        assertEquals("document", doc.getString("type")); assertEquals("a.pdf", doc.getString("title"))
        assertEquals("application/pdf", doc.getJSONObject("source").getString("media_type")); assertEquals("QUJD", doc.getJSONObject("source").getString("data"))
        assertEquals("text", content.getJSONObject(1).getString("type")); assertEquals("ringkas", content.getJSONObject(1).getString("text"))
        // tanpa lampiran: content tetap string biasa
        assertTrue(ClaudeClient.body("m", "s", listOf(ChatMsg("user", "hai")), false).getJSONArray("messages").getJSONObject(0).get("content") is String)
    }

    @Test fun zipExportSanitizesAndDeduplicates() {
        val a = tmp.newFile("a.pdf").also { it.writeText("AAA") }; val b = tmp.newFile("b.pdf").also { it.writeText("BBB") }
        val out = ByteArrayOutputStream()
        ZipExport.write(out, listOf("Kursus/../../evil/Slide.pdf" to a, "Kursus/../../evil/Slide.pdf" to b), mapOf("Kursus/Ringkasan.md" to "# ringkas"))
        val got = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { got[it.name] = z.readBytes().decodeToString() } }
        assertEquals("# ringkas", got["Kursus/Ringkasan.md"])
        assertEquals(setOf("Kursus/Ringkasan.md", "Kursus/_/_/evil/Slide.pdf", "Kursus/_/_/evil/Slide (2).pdf"), got.keys)  // ".." dinetralkan, nama bentrok diberi nomor
        assertEquals("AAA", got["Kursus/_/_/evil/Slide.pdf"]); assertEquals("BBB", got["Kursus/_/_/evil/Slide (2).pdf"])
        assertTrue(got.keys.none { it.contains("..") })
    }
}
