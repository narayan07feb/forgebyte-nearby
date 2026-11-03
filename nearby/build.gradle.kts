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
            implementation(libs.dns.sd)
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
