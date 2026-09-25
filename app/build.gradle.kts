import java.util.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.mediacenter.jf"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mediacenter.jf"
        // Android 6.0: as far back as the AndroidX and Media3 libraries go, so older Android TV
        // boxes (and Fire TV) run it too.
        minSdk = 23
        targetSdk = 36
        versionCode = 37
        versionName = "0.9.9"
    }

    // Release signing comes from signing/keystore.properties, which stays out of git.
    val keystoreProps = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // A release build (shrunk and optimised the same way) that installs next to the real app
        // and can be inspected, for testing playback on the emulator without touching real data.
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time and friends on Android 6 and 7, which don't have them built in.
        isCoreLibraryDesugaringEnabled = true
    }

    // One APK per processor type (smaller downloads for low-storage boxes), plus a universal
    // one that installs anywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Optional local sound overrides (e.g. your own Media Center sounds). The folder is
    // git-ignored; when it's absent the app uses its synthesized sounds. Builds for
    // publishing leave it out even when it's there: ./gradlew assembleRelease -Ppublic
    sourceSets {
        getByName("main") {
            if (!project.hasProperty("public")) res.srcDir("src/localres")
        }
    }

    // Store code uncompressed so Android maps it directly: a larger APK, but faster
    // startup and less memory than unpacking compressed code on the device.
    packaging {
        dex.useLegacyPackaging = false
        jniLibs.useLegacyPackaging = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.bundles.media3)
    implementation(libs.bundles.coil)
    implementation(libs.bundles.ktor)
}
