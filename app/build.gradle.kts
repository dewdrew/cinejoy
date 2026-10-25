plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One fixed signing key for every build. Android only installs an update whose
// signature matches the installed copy, and without this each CI run signs with a
// throwaway debug key generated on the spot, forcing an uninstall per build.
//
// CI restores the keystore from secrets and points CINEJOY_KEYSTORE_PATH at it.
// Locally it is picked up from signing/ (git-ignored) when the password is set.
val cinejoyKeystore = (System.getenv("CINEJOY_KEYSTORE_PATH")
    ?.let { file(it) }
    ?: rootProject.file("signing/cinejoy-release.jks"))
    .takeIf { it.isFile }
val cinejoyKeystorePassword = System.getenv("CINEJOY_KEYSTORE_PASSWORD")
    ?.takeIf { it.isNotBlank() }

android {
    namespace = "pk.cinejoy.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pk.cinejoy.app"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (cinejoyKeystore != null && cinejoyKeystorePassword != null) {
            create("cinejoy") {
                storeFile = cinejoyKeystore
                storePassword = cinejoyKeystorePassword
                keyAlias = "cinejoy"
                // PKCS12 keystores share one password between store and key.
                keyPassword = cinejoyKeystorePassword
            }
        }
    }

    // Without the key (a fresh clone, or a pull request from a fork), fall back to the
    // local debug key: the build still works, it just cannot update existing installs.
    val appSigning = signingConfigs.findByName("cinejoy") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            isMinifyEnabled = false
            // The debug APK is the one being sideloaded, so it needs the fixed key too.
            signingConfig = appSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = appSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
}
