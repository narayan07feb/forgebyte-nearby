import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    id("maven-publish")
}

// Test fixtures for the games' debug builds: a fake second player (FakePeer) and a fake host (FakeHost) on the same
// phone. Games add it with debugImplementation only, so it never reaches a release build (ADAPTERS.md).
kotlin {
    explicitApi()

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
        publishLibraryVariants("release")
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    sourceSets {
        commonMain.dependencies { api(project(":nearby")) }
        jvmTest.dependencies {
            implementation(project(":demo"))
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.forgebyte.nearby.testing"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
