plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.streamclient3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.streamclient3"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true

    }
}

dependencies {
    implementation(libs.androidx.core.ktx.v1120) // update
    implementation(libs.androidx.appcompat) // update
    implementation(libs.material) // update
    implementation(libs.androidx.constraintlayout) // update

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource)  // Contains UdpDataSource
    implementation(libs.androidx.media3.exoplayer.hls)
}
