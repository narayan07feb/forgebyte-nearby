pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "forgebyte-nearby"

include(":nearby")
// Opt-in, Android only: "Make a network" (docs/LOCAL-HOTSPOT.md). Kept apart so its location and Nearby devices
// permissions reach only the games that use it.
include(":nearby-hotspot")

// Test tools, not published: a demo game, a host app for the phone, and a Mac client (PLAN device test 4b).
include(":demo")
include(":sample-host")
include(":mac-client")
