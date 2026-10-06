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
        // Base URL only; InventarioSync appends "?tabla=<table>&key=..." per request and parses
        // each CSV straight into a Tabla (no workbook is assembled any more — see R7).
        buildConfigField("String", "DATOS_APP_URL", "\"$datosAppUrl\"")
        buildConfigField("String", "DATOS_APP_KEY", "\"$datosAppKey\"")
    }

    buildTypes {
        release {
            // Keep POI intact. Apache POI resolves a lot by reflection (OPCPackage parts, the XSSF
            // part factories), so shrinking would need a long list of keep rules to avoid failing
            // only at runtime, on a técnico's phone, when a workbook is opened.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Signed with the DEBUG keystore on purpose. This app is sideloaded to the técnicos'
            // phones, never published, and Android refuses to install an APK over an existing one
            // signed by a different key — so sharing one key with every build made so far keeps
            // updates installable without an uninstall, which would wipe filesDir: the per-técnico
            // consecutivos in app_config.json, the accepted Inventario.xlsx with its stock
            // movements, and the saved remisiones.
            //
            // Consequences, accepted by the owner: this cannot be published to Play, and
            // ~/.android/debug.keystore IS now the app's identity — BACK IT UP. If that file is
            // lost or regenerated (new machine, Studio reset) it comes back with a different key
            // and no later build will be able to update an existing install.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Host-side unit tests run against a stubbed android.jar whose methods throw by
            // default. Returning defaults instead lets tests cover code paths that log —
            // e.g. validateCredentials' missing-column warning and its exception branch —
            // without mocking android.util.Log at every call site. Affects unit tests only.
            isReturnDefaultValues = true
        }
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