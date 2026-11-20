import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.fujara.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.fujara.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 50
        versionName = "1.8.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val uploadStoreFile = System.getenv("FUJARA_UPLOAD_STORE_FILE")
    val uploadStorePassword = System.getenv("FUJARA_UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = System.getenv("FUJARA_UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = System.getenv("FUJARA_UPLOAD_KEY_PASSWORD")

    signingConfigs {
        // Staly klucz debug w repo: kazdy build z GitHub Actions ma ten sam podpis,
        // wiec aktualizacja instaluje sie NA istniejaca wersje bez kasowania danych.
        // (Klucz debug nie jest tajny – wersja Google Play uzywa osobnego klucza upload.)
        getByName("debug") {
            storeFile = rootProject.file("signing/fujara-debug.p12")
            storePassword = "android"
            keyAlias = "fujaradebug"
            keyPassword = "android"
            storeType = "pkcs12"
        }
        create("release") {
            if (!uploadStoreFile.isNullOrBlank()) {
                storeFile = file(uploadStoreFile)
            }

            storePassword = uploadStorePassword
            keyAlias = uploadKeyAlias
            keyPassword = uploadKeyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false

            if (!uploadStoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}