plugins {
    id("com.android.application")
}

// Release signing comes from environment variables (GitHub Actions secrets), never from the repo:
//   HDR_KEYSTORE (path), HDR_KEYSTORE_PASSWORD, HDR_KEY_ALIAS, HDR_KEY_PASSWORD
val ksPath: String? = System.getenv("HDR_KEYSTORE")

android {
    namespace = "com.mojahed.hdrstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mojahed.hdrstudio"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
    }

    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("HDR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HDR_KEY_ALIAS")
                keyPassword = System.getenv("HDR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            // same stable key as release when available, so test builds also update in place
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    androidResources {
        // the model must stay uncompressed-friendly and exact
        noCompress += listOf("bin")
    }

    lint {
        abortOnError = false
    }
}
