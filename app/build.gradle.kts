plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (set by GitHub Actions secrets).
// Without them the release build falls back to the debug key, so it still installs.
val ksFile = System.getenv("SCP_KEYSTORE_FILE")
val hasReleaseKey = !ksFile.isNullOrBlank() && file(ksFile).exists()

android {
    namespace = "com.artemdnk.steamchatplus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.artemdnk.steamchatplus"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = System.getenv("SCP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCP_KEY_ALIAS")
                keyPassword = System.getenv("SCP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("io.coil-kt:coil:2.7.0")
}
