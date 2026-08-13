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
        versionCode = 11
        versionName = "0.7.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val uploadStoreFile = System.getenv("FUJARA_UPLOAD_STORE_FILE")
    val uploadStorePassword = System.getenv("FUJARA_UPLOAD_STORE_PASSWORD")
    val uploadKeyAlias = System.getenv("FUJARA_UPLOAD_KEY_ALIAS")
    val uploadKeyPassword = System.getenv("FUJARA_UPLOAD_KEY_PASSWORD")

    signingConfigs {
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
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}