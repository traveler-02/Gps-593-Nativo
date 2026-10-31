plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ABIs a empaquetar. Tu radio (RK3326 con Android de 32 bits) solo necesita armeabi-v7a.
// Para otras: gradle :app:assembleRelease -Pabis=armeabi-v7a,arm64-v8a
val abis = ((project.findProperty("abis") as String?) ?: "armeabi-v7a")
    .split(",").map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "ec.gps593.nativo"
    compileSdk = 34

    defaultConfig {
        applicationId = "ec.gps593.nativo"
        minSdk = 21
        // 29: en radios con Android 10/11 mantiene el acceso clásico a /storage/XXXX-XXXX (pendrive)
        targetSdk = 29
        versionCode = 3
        versionName = "0.3-prototipo"
        ndk { abiFilters.addAll(abis) }
    }

    buildTypes {
        release {
            isMinifyEnabled = false                       // más adelante: true (R8) cuando todo funcione
            signingConfig = signingConfigs.getByName("debug")   // firma de prueba, instalable directo
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Los assets (estilo y fuentes) sin comprimir: se leen más rápido
    androidResources { noCompress += listOf("json", "pbf") }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Motor de mapas vectoriales nativo (OpenGL ES). Lee .pmtiles directamente.
    implementation("org.maplibre.gl:android-sdk:11.11.0")
}
