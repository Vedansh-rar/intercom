import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.intercom.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.intercom.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-phase1"

        ndk {
            // The two ABIs real phones ship with; keeps the native build and the APK small.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing comes from keystore.properties (gitignored) or environment
    // variables, never from the repository:
    //   keystore.properties: storeFile=..., storePassword=..., keyAlias=..., keyPassword=...
    //   environment:         RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD,
    //                        RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
    // When neither is present the release build is left unsigned and a warning says so.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    fun signingValue(propKey: String, envKey: String): String? =
        (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

    val releaseStoreFile = signingValue("storeFile", "RELEASE_STORE_FILE")
    val releaseStorePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
    val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 plus resource shrinking is also what makes Compose fast: the debug build
            // carries instrumentation that a release build does not.
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "WARNING: no release signing config (keystore.properties or RELEASE_* " +
                        "environment variables). The release APK will be UNSIGNED and cannot " +
                        "be installed until it is signed. See RELEASING.md.",
                )
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
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
        buildConfig = true
        prefab = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    // Play services pulls in an old Fragment; registerForActivityResult needs 1.3.0 or newer
    // and release lint (lintVitalRelease) fails the build without it.
    implementation(libs.androidx.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.media)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.oboe)
    // Draws the ride-code QR on the host's own screen. Pure Java, no network.
    implementation(libs.zxing.core)
    // Scans it on the joiner's phone. Google Play services runs the camera UI, so the app
    // itself needs no camera permission; where Play services is missing the rider types the code.
    implementation(libs.play.services.code.scanner)
    // Group rides only: Nearby Connections carries the bytes between riders' phones. It is
    // a transport, nothing more - authentication and encryption are our own (security/).
    // It talks to Play services on the phone over Bluetooth and Wi-Fi; no internet needed.
    implementation(libs.play.services.nearby)
    // Rider position (Phase 5), only while "Share my location" is on. The fused client gives
    // a better fix for less battery than the raw GPS; with no Play services the app falls
    // back to the platform LocationManager. Nothing here uses the network.
    implementation(libs.play.services.location)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
}
