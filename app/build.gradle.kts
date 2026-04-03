plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.agudelojm.remisionesconagro"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.agudelojm.remisionesconagro"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("org.apache.poi:poi:5.5.1")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
}