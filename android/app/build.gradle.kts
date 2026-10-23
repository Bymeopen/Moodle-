plugins { id("com.android.application") }

android {
    namespace = "id.pribadi.minilms"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.pribadi.minilms"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
// tanpa dependensi pihak ketiga: hanya framework Android (WebView, DownloadManager)
