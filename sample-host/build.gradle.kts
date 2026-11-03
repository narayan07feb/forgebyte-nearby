plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Hosts a demo room on a phone for device tests (PLAN test 4). Auto-accepts requests, starts when everyone is
// ready, and plays the host seat by itself. Debug builds only; never shipped.
android {
    namespace = "com.forgebyte.nearby.samplehost"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.forgebyte.nearby.samplehost"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    // Release build only to measure the app size the library adds (R8 on, debug key); never uploaded.
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

dependencies {
    implementation(project(":demo"))
    implementation(libs.kotlinx.coroutines.android)
}
