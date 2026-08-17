// Kotlin Multiplatform module: business logic shared between the Android app (:app) and the
// Windows desktop app (:desktopApp). Starts with a single desktop JVM target — androidTarget()
// is added once the desktop side of the pipeline (this module + :desktopApp + jpackage installer)
// is confirmed working end to end, to keep each verification step small.
plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(17)
    jvm("desktop")

    sourceSets {
        val commonMain by getting
        val desktopMain by getting {
            dependencies {
            }
        }
    }
}
