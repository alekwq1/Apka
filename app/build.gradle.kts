android {
    namespace = "pl.fujara.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.fujara.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.1"

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