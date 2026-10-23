package id.pribadi.minilms

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Kartu hafalan. [due] berformat yyyy-MM-dd (tanggal lokal). */
data class Card(val id: String, val courseId: Long, val front: String, val back: String, val ease: Double, val interval: Int, val reps: Int, val due: String)

object Srs {
    val ratings = listOf("again", "hard", "good", "easy")
    private val quality = mapOf("again" to 1, "hard" to 3, "good" to 4, "easy" to 5)

    /** SM-2: lupa -> ulang hari ini; selain itu interval 1 -> 6 -> interval*ease. */
    fun review(c: Card, rating: String, today: LocalDate = LocalDate.now()): Card {
        val q = quality[rating] ?: throw IllegalArgumentException("rating tidak dikenal: $rating")
        var reps = c.reps; var interval = c.interval
        if (q < 3) { reps = 0; interval = 0 }
        else { interval = when (reps) { 0 -> 1; 1 -> 6; else -> Math.round(interval * c.ease).toInt() }; reps += 1 }
        val ease = Math.round(maxOf(1.3, c.ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)) * 100) / 100.0
        return c.copy(ease = ease, interval = interval, reps = reps, due = today.plusDays(interval.toLong()).toString())
    }

    fun isDue(c: Card, today: LocalDate = LocalDate.now()) = c.due <= today.toString()
}

fun Card.toJson() = JSONObject().put("id", id).put("courseId", courseId).put("front", front).put("back", back)
    .put("ease", ease).put("interval", interval).put("reps", reps).put("due", due)
fun JSONObject.toCard() = Card(getString("id"), getLong("courseId"), getString("front"), getString("back"), getDouble("ease"), getInt("interval"), getInt("reps"), getString("due"))
fun List<Card>.toJsonArray() = JSONArray(map { it.toJson() })
fun JSONArray.toCards() = (0 until length()).map { getJSONObject(it).toCard() }
