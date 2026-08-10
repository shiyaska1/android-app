plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jobsearch.india"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jobsearch.india"
        minSdk = 26
        targetSdk = 36
        // CI sets VERSION_CODE per build (see .github/workflows/build-jobapp.yml and
        // release-jobapp.yml) so every APK/AAB gets a unique, always-increasing code.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // Play Store upload key — provided by CI via env vars (kept out of git). Falls back to the
    // committed "stable" key for local/debug builds so a plain ./gradlew still works.
    val uploadStoreFile = System.getenv("UPLOAD_STORE_FILE")
    val uploadStorePassword = System.getenv("UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = System.getenv("UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
    val hasUploadKey = !uploadStoreFile.isNullOrBlank() && !uploadStorePassword.isNullOrBlank()

    signingConfigs {
        create("stable") {
            storeFile = file("keystore.jks")
            storePassword = "jobkey123"
            keyAlias = "indianjobs"
            keyPassword = "jobkey123"
        }
        if (hasUploadKey) {
            create("upload") {
                storeFile = file(uploadStoreFile!!)
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Sign with the committed stable key so customers can update without data loss.
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Use the Play upload key when CI provides it, else the local stable key.
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Async image loading from URLs for the job-post image gallery.
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Play in-app updates: force onto the latest version as soon as one is published,
    // so future changes reach every customer instead of them staying on an old build.
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
