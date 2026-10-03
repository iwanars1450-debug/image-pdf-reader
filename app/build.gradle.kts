plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "jp.local.imagepdf"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "jp.local.imagepdf"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // The local installed build keeps its key; clones use Android's generated debug key.
    signingConfigs { getByName("debug") {
        val localKey = rootProject.file("debug.keystore")
        if (localKey.exists()) { storeFile = localKey; storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    } }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
