package id.pribadi.minilms.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import java.io.File

/**
 * Simpan tangkapan layar Compose sebagai PNG ke -Dshot.dir (default build/shots).
 * Menggambar decorView langsung (lebih andal di Robolectric daripada captureToImage).
 */
fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.shot(name: String) {
    waitForIdle()
    val dir = File(System.getProperty("shot.dir") ?: "build/shots").apply { mkdirs() }
    val view = activity.window.decorView
    val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bmp))
    File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
