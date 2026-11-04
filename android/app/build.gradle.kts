import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Penandatanganan rilis: isi android/keystore.properties (tidak di-commit), lihat README.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "id.pribadi.minilms"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.pribadi.minilms"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "2.17"
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")   // null (tanpa tanda tangan) bila keystore.properties tidak ada
        }
    }
}

// ---- Rootfs Alpine dibundel ke APK (aset alpine/): diunduh saat build dari mirror resmi; SHA-256 harus cocok dengan pin di android/alpine.properties.
// Bila unduhan gagal (offline), APK tetap dibangun tanpa bundel dan aplikasi mengunduh sendiri saat pemasangan. Hash yang tidak cocok = build gagal.
// Disimpan sebagai rootfs.bin: AAPT2 membongkar otomatis aset berakhiran .gz (isi APK jadi .tar, hash tidak cocok).
val alpineProps = Properties().apply { rootProject.file("alpine.properties").inputStream().use { load(it) } }
val alpineAssets = layout.buildDirectory.dir("alpine/assets")
val fetchAlpineRootfs by tasks.registering {
    val file = alpineProps.getProperty("file"); val sha = alpineProps.getProperty("sha256"); val url = alpineProps.getProperty("url")
    val outDir = alpineAssets.get().dir("alpine").asFile
    inputs.properties("file" to file, "sha256" to sha)
    outputs.dir(alpineAssets)
    val asset = "rootfs.bin"
    outputs.upToDateWhen { File(outDir, asset).exists() }
    doLast {
        fun hash(f: File) = MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        outDir.mkdirs()
        val dest = File(outDir, asset)
        if (!dest.exists() || hash(dest) != sha) {
            val tmp = File(outDir, "$asset.part")
            try {
                URL(url).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            } catch (e: IOException) {
                tmp.delete(); outDir.listFiles()?.forEach { it.delete() }
                logger.warn("PERINGATAN: rootfs Alpine tidak bisa diunduh (${e.message}); APK dibangun TANPA bundel.")
                return@doLast
            }
            val got = hash(tmp)
            if (got != sha) { tmp.delete(); throw GradleException("SHA-256 rootfs Alpine ($got) tidak cocok dengan pin alpine.properties; build dihentikan.") }
            if (!tmp.renameTo(dest)) throw GradleException("gagal menyimpan rootfs Alpine")
        }
        outDir.listFiles()?.filter { it.name != asset }?.forEach { it.delete() }
        File(outDir, "manifest.properties").writeText("file=$file\nasset=$asset\nsha256=$sha\nversion=${alpineProps.getProperty("version")}\narch=${alpineProps.getProperty("arch")}\n")
    }
}
android.sourceSets["main"].assets.srcDir(alpineAssets)
tasks.named("preBuild") { dependsOn(fetchAlpineRootfs) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.browser:browser:1.8.0")

    implementation("androidx.compose.material:material-icons-extended")

    // render Compose di JVM (tanpa emulator) untuk uji visual
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json asli untuk tes JVM (di Android sudah bawaan)
}
