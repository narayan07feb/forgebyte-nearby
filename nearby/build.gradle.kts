import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    id("maven-publish")
}

// Android for the games; JVM for the Mac loopback tests and the Mac test client; iOS (device and Apple-silicon
// simulator) from week 3 (docs/PLAN.md), compiled only so far: no Swift app yet.
kotlin {
    explicitApi()

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        publishLibraryVariants("release")
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { ios ->
        // The CryptoKit provider is Swift: binaries (the test runner too) load its runtime from the system Swift folder.
        ios.binaries.all { linkerOpts("-rpath", "/usr/lib/swift") }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            // dns-sd-kt lists appcompat but never uses it; appcompat would add emoji2 and other start-up initializers
            // to every game. Its code needs only androidx.core (added in androidMain).
            implementation(libs.dns.sd.get().toString()) { exclude(group = "androidx.appcompat") }
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.cryptography.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // Code shared by the two JVM-based targets (java.security, java.net); see the hierarchy below.
        getByName("jvmSharedMain").dependencies { implementation(libs.cryptography.provider.jdk) }
        iosMain.dependencies { implementation(libs.cryptography.provider.cryptokit) }
        androidMain.dependencies {
            implementation(libs.androidx.startup)
            implementation(libs.androidx.core)
        }
    }
}

android {
    namespace = "com.forgebyte.nearby"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
