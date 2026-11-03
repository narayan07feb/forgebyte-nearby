import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    id("maven-publish")
}

// Android for the games; JVM for the Mac loopback tests and the Mac test client. iOS comes in week 3 (docs/PLAN.md).
kotlin {
    explicitApi()

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        publishLibraryVariants("release")
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
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
            // It is also built with Kotlin 2.3.20 and would lift every game's kotlin-stdlib to 2.3.20, which AGP 8.9 Lint
            // cannot read. All 33 stdlib methods it calls exist in 2.2.20 (checked with reflection, 2026-11-03), so the
            // games' own stdlib serves it.
            implementation(libs.dns.sd.get().toString()) {
                exclude(group = "androidx.appcompat")
                exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
            }
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
