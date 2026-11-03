import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val media3 = "1.5.1"
val javacpp = "1.5.11"
val ffmpeg = "7.1-$javacpp"

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Test-only: lets tests control Dispatchers.Main and virtual time for the ViewModel.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        // Tests that need the JDK (the project file store) run in both the Android unit tests and the desktop tests.
        matching { it.name == "androidUnitTest" || it.name == "desktopTest" }
            .configureEach { kotlin.srcDir("src/jvmSharedTest/kotlin") }

        // JDK code that is identical on Android and Windows (files, ids, dates) is compiled into both.
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation("androidx.core:core-ktx:1.15.0")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.media3:media3-common:$media3")
                implementation("androidx.media3:media3-exoplayer:$media3")
                implementation("androidx.media3:media3-transformer:$media3")
                implementation("androidx.media3:media3-effect:$media3")
                implementation("androidx.media3:media3-ui:$media3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            }
        }
        desktopMain.kotlin.srcDir("src/jvmShared/kotlin")
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
            // FFmpeg for Windows: decoding for preview + the ffmpeg program for export (GPL build has libx264).
            implementation("org.bytedeco:javacv:$javacpp") {
                // Only FFmpeg is needed; skip the other camera/vision libraries JavaCV lists.
                listOf(
                    "opencv", "openblas", "flycapture", "libdc1394", "libfreenect", "libfreenect2",
                    "librealsense", "librealsense2", "videoinput", "artoolkitplus", "flandmark",
                    "leptonica", "tesseract",
                ).forEach { exclude(group = "org.bytedeco", module = it) }
            }
            implementation("org.bytedeco:javacpp:$javacpp")
            implementation("org.bytedeco:javacpp:$javacpp:windows-x86_64")
            implementation("org.bytedeco:ffmpeg:$ffmpeg")
            implementation("org.bytedeco:ffmpeg:$ffmpeg:windows-x86_64-gpl")
        }
    }
}

android {
    namespace = "com.reelcut.app"
    compileSdk = 35

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    defaultConfig {
        applicationId = "com.reelcut.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // One shared signing key for every build, so new APKs install over old ones.
    signingConfigs {
        create("shared") {
            storeFile = file("keystore/reelcut-dev.jks")
            storePassword = "reelcut"
            keyAlias = "reelcut"
            keyPassword = "reelcut"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("shared") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.reelcut.app.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dorg.bytedeco.javacpp.platform.extension=-gpl")
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "Reelcut"
            packageVersion = "1.0.$buildNumber"
            description = "Reelcut video editor"
            vendor = "Reelcut"
            includeAllModules = true
            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = true
                menuGroup = "Reelcut"
                upgradeUuid = "6f6a1c2e-3d0b-4c3e-9a51-7c2f0e8b5d41"
                iconFile.set(project.file("icons/reelcut.ico"))
            }
        }
    }
}
