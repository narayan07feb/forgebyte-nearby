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

// Test tools, not published: a demo game, a host app for the phone, and a Mac client (PLAN device test 4b).
include(":demo")
include(":sample-host")
include(":mac-client")
