import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Load secrets (e.g. INVENTARIO_API_KEY) from local.properties. Not committed to VCS.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val inventarioApiKey: String = localProperties.getProperty("INVENTARIO_API_KEY", "")
// R5 — Datos App database endpoint (one CSV table per request). Blank URL = feature off.
val datosAppUrl: String = localProperties.getProperty("DATOS_APP_URL", "")
val datosAppKey: String = localProperties.getProperty("DATOS_APP_KEY", "")

android {
    namespace = "io.github.agudelojm.remisionesconagro"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.agudelojm.remisionesconagro"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- Inventory web sync (R1) ---
        buildConfigField("String", "INVENTARIO_BASE_URL", "\"https://conagro.com.co/reportes-api/get_reporte.php\"")
        buildConfigField("String", "INVENTARIO_API_KEY", "\"$inventarioApiKey\"")
        // --- Datos App web sync (R5) ---
        // Base URL only; InventarioSync appends "?tabla=<table>&key=..." per request and
        // reassembles the four CSVs into the "Datos App.xlsx" workbook.
        buildConfigField("String", "DATOS_APP_URL", "\"$datosAppUrl\"")
        buildConfigField("String", "DATOS_APP_KEY", "\"$datosAppKey\"")
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

    buildFeatures {
        buildConfig = true
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
    implementation("com.github.gcacace:signature-pad:1.3.1")
}