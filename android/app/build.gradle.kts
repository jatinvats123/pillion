import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// Emulator reaches the host's localhost via 10.0.2.2. Override with -PPILLION_BACKEND_URL=... or in
// android/local.properties: one URL, or several comma-separated, tried in order at each ride start
// (scripts/phone.ps1 passes the laptop's Wi-Fi address, adb reverse and the tunnel).
val backendUrl: String = providers.gradleProperty("PILLION_BACKEND_URL").orNull
    ?: localProperties.getProperty("PILLION_BACKEND_URL")
    ?: "http://10.0.2.2:3000"

// Release builds talk to one hosted backend (render.yaml). Override in android/local.properties.
val releaseBackendUrl: String = providers.gradleProperty("PILLION_RELEASE_BACKEND_URL").orNull
    ?: localProperties.getProperty("PILLION_RELEASE_BACKEND_URL")
    ?: "https://pillion-backend.onrender.com"

// The hosted backend's APP_KEY (public mode). A speed bump, not a secret: it ships in the APK. Kept
// out of git in android/local.properties (or the PILLION_APP_KEY environment variable).
val appKey: String = localProperties.getProperty("PILLION_APP_KEY") ?: System.getenv("PILLION_APP_KEY").orEmpty()

// Release signing: the keystore lives outside the repo; path and passwords come from
// android/local.properties or the environment, never from git. Without them the release is unsigned.
fun signingValue(name: String): String? = localProperties.getProperty(name) ?: System.getenv(name)
val releaseKeystore = signingValue("PILLION_KEYSTORE")?.let(::file)?.takeIf { it.exists() }

// Debug builds: the seeded order's customer number for SMS/call tests, kept out of git in
// android/local.properties. Digits and + only.
val testCustomerPhone: String = localProperties.getProperty("PILLION_TEST_CUSTOMER_PHONE").orEmpty()
    .filter { it.isDigit() || it == '+' }

android {
    namespace = "app.pillion"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.pillion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "APP_KEY", "\"$appKey\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("PILLION_KEYSTORE_PASSWORD")
                keyAlias = signingValue("PILLION_KEY_ALIAS") ?: "pillion"
                keyPassword = signingValue("PILLION_KEY_PASSWORD") ?: signingValue("PILLION_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "BACKEND_URL", "\"${backendUrl.trimEnd('/')}\"")
            buildConfigField("String", "TEST_CUSTOMER_PHONE", "\"$testCustomerPhone\"")
        }
        release {
            buildConfigField("String", "BACKEND_URL", "\"${releaseBackendUrl.trimEnd('/')}\"")
            buildConfigField("String", "TEST_CUSTOMER_PHONE", "\"\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Phones only: the emulator ABIs (x86, x86_64) stay in debug builds.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            // R8 stays off: the APK is mostly Agora's and ML Kit's native libraries (it would save a few
            // MB of dex), against keep-rule risk for Agora's JNI, org.json and the GL shaders.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // RTC and RTM both ship Agora's shared runtime library.
        jniLibs.pickFirsts += "**/libaosl.so"
    }
}

dependencies {
    implementation(libs.agora.rtc.voice)
    implementation(libs.agora.rtc.ains)
    implementation(libs.agora.rtc.aiaec)
    implementation(libs.agora.rtm.lite)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
