package id.pribadi.minilms.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SmokeShotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun smoke() {
        rule.setContent { MaterialTheme { Column(Modifier.fillMaxSize().background(Color(0xFFFFF3E8)).padding(24.dp)) { Text("Halo Mini LMS", style = MaterialTheme.typography.headlineLarge); Button({}) { Text("Tombol") } } } }
        rule.shot("smoke")
    }
}
