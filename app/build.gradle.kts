plugins {
    id("com.android.application")
}

android {
    namespace = "gt.mamachana.comandaprint"
    compileSdk = 34

    defaultConfig {
        applicationId = "gt.mamachana.comandaprint"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
