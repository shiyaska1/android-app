import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

compose.desktop {
    application {
        mainClass = "com.billing.pos.desktop.MainKt"
        nativeDistributions {
            // jpackage bundles its own Java runtime into the installer — end users run the
            // resulting .exe/.msi with nothing else to install, no separate Java setup.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "POS Billing"
            packageVersion = "1.0.3"
            // jlink's automatic module detection missed java.sql (needed by the SQLite JDBC
            // driver) — every screen that touched the database threw NoClassDefFoundError on
            // java.sql.Connection at runtime. Hand-picking a module list risks the same failure
            // mode for something else later (e.g. TLS/crypto modules the new backup push/pull
            // feature needs), so bundle the complete JDK instead — a larger installer, but no
            // more silent "missing module" crashes as more JVM libraries get added over time.
            includeAllModules = true
            windows {
                menuGroup = "POS Billing"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
