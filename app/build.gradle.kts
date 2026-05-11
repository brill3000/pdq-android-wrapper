plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "co.ke.hiduka.pdq"
    compileSdk = 34

    defaultConfig {
        applicationId = "co.ke.hiduka.pdq"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Default points at production. Override per build type below.
        buildConfigField("String", "WEB_APP_URL", "\"https://pdq.hiduka.co.ke\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("String", "WEB_APP_URL", "\"https://pdq.hiduka.co.ke\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Newland NDK SDK lives in app/libs/ once you have the .aar from
    // Newland. Uncomment and adjust the artefact name when adding it.
    // implementation(files("libs/nlsdk-aidl-x.y.z.aar"))
}
