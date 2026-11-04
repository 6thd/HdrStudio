plugins {
    id("com.android.application")
}

// Release signing comes from environment variables (GitHub Actions secrets), never from the repo:
//   HDR_KEYSTORE (path), HDR_KEYSTORE_PASSWORD, HDR_KEY_ALIAS, HDR_KEY_PASSWORD
// NCNN_DIR is the directory that contains ncnnConfig.cmake (arm64-v8a package of ncnn 20250503).
val ksPath: String? = System.getenv("HDR_KEYSTORE")
val ncnnDir: String? = System.getenv("NCNN_DIR")

android {
    namespace = "com.mojahed.hdrstudio"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mojahed.hdrstudio"
        minSdk = 29
        targetSdk = 34
        versionCode = 16
        versionName = "2.3.9"
        if (ncnnDir != null) {
            ndk { abiFilters += listOf("arm64-v8a") }
            externalNativeBuild {
                cmake {
                    arguments += listOf("-Dncnn_DIR=$ncnnDir", "-DANDROID_STL=c++_static")
                    cppFlags += listOf("-std=c++17")
                }
            }
        }
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

    if (ncnnDir != null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    androidResources {
        // the model must stay uncompressed-friendly and exact
        noCompress += listOf("bin")
    }

    lint {
        abortOnError = false
    }
}
