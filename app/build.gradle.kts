plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.rodrigo.autolauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "cl.rodrigo.autolauncher"
        minSdk = 21
        targetSdk = 34
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "1.0.$run"
    }

    // Llave fija: todas las versiones se firman igual, así Android permite actualizar encima
    signingConfigs {
        create("fija") {
            storeFile = file("release.jks")
            storePassword = "bca65fb681afa88afdafd74fab088cc5"
            keyAlias = "autolauncher"
            keyPassword = "bca65fb681afa88afdafd74fab088cc5"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
