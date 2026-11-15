plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val apiBaseUrl = providers.gradleProperty("AUTOCLIP_API_BASE_URL")
    .orElse(providers.environmentVariable("AUTOCLIP_API_BASE_URL"))
    .orElse("https://rezqclip-c85d5.containers.snapdeploy.app/")

android {
    namespace = "com.example.autoclipai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.autoclipai"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
        buildConfigField("String", "API_BASE_URL", "\"${apiBaseUrl.get()}\"")
        manifestPlaceholders["usesCleartextTraffic"] =
            (!apiBaseUrl.get().startsWith("https://")).toString()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
