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
    debugImplementation(project(":nearby-testing")) // here only so verifyNoNearbyTestingInRelease has something to catch
}

// The fake peer and fake host (nearby-testing) are for debug builds only. Fails if one reaches a release classpath.
// Games copy this task and list every build type that must stay free of test code (docs/ADAPTERS.md).
val verifyNoNearbyTestingInRelease by tasks.registering {
    val classpaths = listOf("releaseRuntimeClasspath").map { configurations.named(it) }
    doLast {
        classpaths.forEach { cp ->
            val found = cp.get().incoming.resolutionResult.allComponents.map { it.id.displayName }.filter { "nearby-testing" in it }
            check(found.isEmpty()) { "$found is in ${cp.name}; add nearby-testing with debugImplementation only" }
        }
    }
}
tasks.named("check") { dependsOn(verifyNoNearbyTestingInRelease) }

// ceo, 2026-11-03: nothing from nearby may run at app start. Owner rule, 2026-11-08: no SDK may slow app start-up.
// Fails if the merged app manifest (debug and release) has any content provider other than androidx.startup's, or
// any androidx.startup initializer other than ProfileInstallerInitializer (it waits for the first frame, then 5 s).
// dns-sd-kt's initializer is removed in nearby/src/androidMain/AndroidManifest.xml and runs in NearbyAndroid.init.
val verifyNoNearbyStartup by tasks.registering {
    val variants = listOf("debug", "release")
    dependsOn(variants.map { "process${it.replaceFirstChar(Char::uppercase)}MainManifest" })
    val manifests = variants.map {
        layout.buildDirectory.file("intermediates/merged_manifest/$it/process${it.replaceFirstChar(Char::uppercase)}MainManifest/AndroidManifest.xml")
    }
    doLast {
        val ns = "http://schemas.android.com/apk/res/android"
        val startup = "androidx.startup.InitializationProvider"
        fun org.w3c.dom.NodeList.elements() = (0 until length).map { item(it) as org.w3c.dom.Element }
        manifests.map { it.get().asFile }.forEach { file ->
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(file)
            val providers = doc.getElementsByTagName("provider").elements()
            val names = providers.map { it.getAttributeNS(ns, "name") }
            check(names.all { it == startup }) { "${names - startup} auto-start at app start (${file.path})" }
            val initializers = providers.flatMap { p ->
                p.getElementsByTagName("meta-data").elements().filter { it.getAttributeNS(ns, "value") == "androidx.startup" }
                    .map { it.getAttributeNS(ns, "name") }
            }
            val extra = initializers - "androidx.profileinstaller.ProfileInstallerInitializer"
            check(extra.isEmpty()) { "$extra run at app start; init after the first frame instead (${file.path})" }
        }
    }
}
tasks.named("check") { dependsOn(verifyNoNearbyStartup) }
