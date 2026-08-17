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
                // Plain embedded SQLite for the desktop target — same file-based, no-server
                // database story as Room/SQLite already gives the Android app. Full Room
                // multiplatform (reusing the Android app's 45 migrations as-is) lands in a later
                // batch; this gets a real, working desktop database shipping today.
                implementation("org.xerial:sqlite-jdbc:3.46.1.3")
                // Same JSON library the Android app's backup format is built on (plain-JVM
                // reference implementation, not the Android-bundled fork) — lets the desktop
                // backup code follow the same JSONObject/JSONArray shape.
                implementation("org.json:json:20240303")
                // Android's PDF code (android.graphics.pdf.PdfDocument) doesn't exist on desktop
                // JVM — Apache PDFBox is the standard replacement for generating invoice PDFs here.
                implementation("org.apache.pdfbox:pdfbox:3.0.3")
            }
        }
    }
}
