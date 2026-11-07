import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

// "Make a network" (docs/LOCAL-HOTSPOT.md): the host starts an Android local-only hotspot, the joiner joins it.
// Opt-in artifact com.github.narayan07feb.forgebyte-nearby:nearby-hotspot, so only games that use it get its permissions.
kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

android {
    namespace = "com.forgebyte.nearby.hotspot"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":nearby"))
    implementation(libs.androidx.core) // @RequiresApi and @RequiresPermission, for the games' lint
    testImplementation(libs.kotlin.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "nearby-hotspot"
            afterEvaluate { from(components["release"]) }
        }
    }
}
