plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Joins a demo room from the Mac (PLAN device test 4b): finds it with JmDNS or uses a join code, plays the seat,
// and prints move round-trip times. Run: ./gradlew :mac-client:run --args="[--code host:port:code] [--name Mac]"
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

application { mainClass.set("com.forgebyte.nearby.macclient.MainKt") }

dependencies { implementation(project(":demo")) }
