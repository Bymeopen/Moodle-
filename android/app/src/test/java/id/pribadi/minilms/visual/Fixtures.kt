package id.pribadi.minilms.visual

import id.pribadi.minilms.*
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Data contoh bentuknya meniru Moodle kampus; waktu "sekarang" dikunci agar tangkapan layar stabil. */
object Fx {
    val now: Long = LocalDateTime.of(2026, 10, 23, 20, 41).toEpochSecond(ZoneOffset.UTC)
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = LocalDateTime.of(y, m, d, h, min).toEpochSecond(ZoneOffset.UTC)
    private fun file(n: String, size: Long, mime: String? = null) = FileRef(n, "/", "https://lms.kampus.ac.id/webservice/pluginfile.php/$n", size, mime, 1)

    val aik = Course(11, "Al-Islam dan Kemuhammadiyahan 1 - Fabira - GANJIL 2026-2027", "Mata kuliah dasar keislaman dan kemuhammadiyahan.", listOf(
        Item(1, "page", "Pengantar", "Pertemuan 1", "Selamat datang di AIK 1. Pelajari silabus dan kontrak perkuliahan.", null, null, emptyList()),
        Item(2, "link", "Materi Pertemuan 1", "Pertemuan 1", "https://lms/mod/resource/view.php?id=2", null, null, listOf(file("Slide-Pertemuan-1.pptx", 3_400_000), file("Modul-AIK-1.pdf", 5_800_000, "application/pdf"))),
        Item(3, "assignment", "Kemandirian 01", "Pertemuan 1", "Tulis refleksi satu halaman.", at(2027, 1, 10, 23, 59), 100.0, emptyList()),
        Item(4, "assignment", "Kuis Pertemuan 03", "Pertemuan 3", "Kuis singkat.", now + 5 * 3600, 100.0, emptyList()),
        Item(5, "assignment", "Kemandirian 02", "Pertemuan 2", "Refleksi.", at(2027, 1, 10, 23, 59), 100.0, emptyList()),
    ), listOf(
        Grade("Forum Diskusi 2 whole forum", null, 100.0), Grade("Presensi Pertemuan 01", 100.0, 100.0), Grade("Presensi Pertemuan 02", 100.0, 100.0),
        Grade("Presensi Pertemuan 03", null, 100.0), Grade("Kemandirian 01", null, 100.0), Grade("Kuis Pertemuan 02", 90.0, 100.0), Grade("Quiz Pertemuan 1", 100.0, 100.0),
        Grade("Soal UCP 1", null, 100.0), Grade("Total kursus", 96.0, 100.0),
    ))
    val akuntansi = Course(12, "Pengantar Akuntansi - GANJIL 2026/2027", "", listOf(
        Item(10, "assignment", "Penugasan 05", "Minggu 5", "Kerjakan soal latihan bab 5.", at(2027, 1, 8, 1, 28), 100.0, emptyList()),
        Item(11, "assignment", "Penugasan 04", "Minggu 4", "Latihan bab 4.", now + 26 * 3600, 100.0, emptyList()),
        Item(12, "assignment", "Penugasan 03", "Minggu 3", "Latihan bab 3.", now - 2 * 86400, 100.0, emptyList()),
        Item(13, "link", "Buku Ajar", "Minggu 1", "https://x", null, null, listOf(file("Buku-Ajar-Akuntansi.pdf", 12_000_000, "application/pdf"))),
    ), listOf(Grade("Penugasan 01", 82.0, 100.0), Grade("Penugasan 02", 71.0, 100.0), Grade("Penugasan 03", null, 100.0), Grade("Total kursus", 76.5, 100.0)))
    val bindo = Course(13, "Bahasa Indonesia - GANJIL 2026-2027", "Menulis ilmiah.", listOf(
        Item(20, "assignment", "TUGAS 1: PENULISAN PARAGRAF ARGUMENTASI (ORISINAL HASIL PEMIKIRAN SENDIRI)", "Tugas", "Tulis paragraf argumentasi.", at(2027, 1, 10, 23, 59), 100.0, emptyList()),
        Item(21, "page", "Panduan EYD", "Materi", "Ejaan yang disempurnakan.", null, null, emptyList()),
    ), listOf(Grade("Tugas 1", null, 100.0), Grade("Total kursus", null, 100.0)))
    val bing = Course(14, "Bahasa Inggris (Fabira) - GANJIL 2026-2027", "", listOf(
        Item(30, "page", "Unit 1", "Unit 1", "Greetings and introductions.", null, null, emptyList()),
    ), listOf(Grade("Quiz 1", 55.0, 100.0), Grade("Total kursus", 55.0, 100.0)))

    val snapshot = Snapshot(now - 5 * 60, listOf(aik, akuntansi, bindo, bing), emptyList())
    val stats = mapOf(
        11L to Repo.CourseStats(2, 2, 9_200_000, 9_200_000), 12L to Repo.CourseStats(1, 0, 12_000_000, 0),
        13L to Repo.CourseStats(0, 0, 0, 0), 14L to Repo.CourseStats(0, 0, 0, 0),
    )
}

object FxAi {
    val md = """## Konsep inti: Debit dan Kredit

Dalam **akuntansi**, setiap transaksi dicatat di dua sisi:

1. **Debit** — sisi kiri akun
2. **Kredit** — sisi kanan akun

Persamaan dasarnya: `Aset = Kewajiban + Ekuitas`.

- Kas bertambah → *debit*
- Utang bertambah → *kredit*

| Akun | Saldo normal |
|---|---|
| Kas | Debit |
| Utang | Kredit |

> Tips: hafalkan dengan "DEAD CLIC".

```text
Kas      Rp 1.000.000  (D)
Modal    Rp 1.000.000  (K)
```"""

    val chat = listOf(
        ChatMsg("user", "Jelaskan debit dan kredit dengan contoh sederhana", docs = listOf(Doc("Buku-Ajar-Akuntansi.pdf", ""))),
        ChatMsg("assistant", md, usage = Usage(input = 1200, cacheRead = 8400, output = 420)),
        ChatMsg("user", "Bagaimana dengan akun pendapatan?"),
        ChatMsg("assistant", ""),
    )

    fun state(mode: AiMode, hasKey: Boolean = true, chat: List<ChatMsg> = emptyList(), busy: Boolean = false, error: String? = null, courseId: Long? = 12L) = AiState(
        hasKey, Provider.GEMINI, setOf(Provider.GEMINI), "gemini-3.8-flash", mode, Fx.snapshot.courses, courseId, false, chat, busy, error, if (mode == AiMode.SUMMARY) md else "",
        if (mode == AiMode.QUIZ) quiz else emptyList(), if (mode == AiMode.CARDS) drafts else emptyList(), cards, emptyList(), emptySet(), Usage(2400, 16800, 0, 900),
        listOf("Ringkas poin terpenting kursus ini", "Jelaskan konsep tersulit dengan contoh sederhana", "Apa yang paling mungkin keluar di ujian?"),
    )

    val quiz = listOf(
        Quiz("Apa saldo normal akun Kas?", listOf("Debit", "Kredit", "Nol", "Bergantung periode"), 0, "Kas adalah aset; aset bertambah di sisi debit sehingga saldo normalnya debit."),
        Quiz("Persamaan dasar akuntansi adalah…", listOf("Aset = Kewajiban + Ekuitas", "Aset = Pendapatan − Beban", "Ekuitas = Aset + Kewajiban", "Kas = Modal"), 0, "Seluruh aset dibiayai oleh kewajiban dan ekuitas pemilik."),
    )
    val drafts = listOf(Draft("Apa itu debit?", "Sisi kiri akun; menambah aset dan beban."), Draft("Apa itu kredit?", "Sisi kanan akun; menambah kewajiban, ekuitas, dan pendapatan."))
    val cards = listOf(Card("1", 12, "Apa itu debit?", "Sisi kiri akun", 2.5, 0, 0, "2026-01-01"), Card("2", 12, "Persamaan akuntansi?", "A = K + E", 2.5, 1, 1, "2099-01-01"))
}
