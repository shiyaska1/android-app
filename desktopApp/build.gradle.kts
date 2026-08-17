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
}

compose.desktop {
    application {
        mainClass = "com.billing.pos.desktop.MainKt"
        nativeDistributions {
            // jpackage bundles its own Java runtime into the installer — end users run the
            // resulting .exe/.msi with nothing else to install, no separate Java setup.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "POS Billing"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "POS Billing"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
