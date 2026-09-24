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
        versionName = "0.1.0"

        buildConfigField("String", "BACKEND_URL", "\"${backendUrl.trimEnd('/')}\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "TEST_CUSTOMER_PHONE", "\"$testCustomerPhone\"")
        }
        release {
            buildConfigField("String", "TEST_CUSTOMER_PHONE", "\"\"")
            // Phones only: the emulator ABIs (x86, x86_64) stay in debug builds.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
