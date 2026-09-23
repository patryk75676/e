plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.cyphr.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.cyphr.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"

        buildConfigField("String", "BASE_URL", "\"${project.findProperty("cyphr.baseUrl")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${project.findProperty("cyphr.googleWebClientId")}\"")
    }

    signingConfigs {
        // Jesli obok modulu lezy plik debug.keystore, uzywamy go — dzieki temu
        // kazda paczka ma ten sam odcisk SHA-1 (BE:B9:D6:34:...) i logowanie
        // Google dziala bez zmian w konsoli po nowym buildzie. Keystore NIE jest
        // w repozytorium; CI podklada go z sekretu DEBUG_KEYSTORE_B64. Gdy go
        // brak (np. czysty klon), zostaje domyslny klucz debugowy Androida i
        // build i tak przechodzi — tylko odcisk bedzie inny.
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.github.mwiede:jsch:0.2.18")

    testImplementation("junit:junit:4.13.2")
    // android.jar w testach ma tylko zaslepki JSONObject — podstawiamy prawdziwa implementacje.
    testImplementation("org.json:json:20231013")
}
