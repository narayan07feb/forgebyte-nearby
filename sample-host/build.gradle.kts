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
    // Release build only to measure the app size the library adds (R8 on, debug key); never uploaded. No rules of
    // its own: it builds only on nearby's consumer rules, the same as a game would (check for ceo, 2026-11-07).
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

// ceo, 2026-11-03: nothing from nearby may run at app start. Fails if the merged app manifest still registers
// dns-sd-kt's initializer or appcompat's emoji2 initializer (appcompat is excluded from dns-sd-kt).
val verifyNoNearbyStartup by tasks.registering {
    dependsOn("processDebugMainManifest")
    val manifest = layout.buildDirectory.file("intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
    doLast {
        val text = manifest.get().asFile.readText()
        listOf("com.appstractive.dnssd.AppContextInitializer", "androidx.emoji2.text.EmojiCompatInitializer").forEach {
            check(it !in text) { "$it runs at app start; nearby must stay lazy (see nearby/src/androidMain/AndroidManifest.xml)" }
        }
    }
}
tasks.named("check") { dependsOn(verifyNoNearbyStartup) }
