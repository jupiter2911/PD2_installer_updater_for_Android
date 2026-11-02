plugins {
    id("com.android.application")
}

// The release key comes from the environment (tools/build-release.ps1 sets it), never from the repo.
val releaseKeystore: String? = System.getenv("PD2_KEYSTORE_PATH")

fun requiredSigningValue(name: String): String =
    System.getenv(name) ?: throw GradleException("PD2_KEYSTORE_PATH is set but $name is not")

android {
    namespace = "com.pd2updater"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pd2updater"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.1"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                keyAlias = requiredSigningValue("PD2_KEY_ALIAS")
                storePassword = requiredSigningValue("PD2_KEYSTORE_PASSWORD")
                keyPassword = requiredSigningValue("PD2_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release key the build still works, for testing only, with the debug key.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests (android.jar has only stubs).
    testImplementation("org.json:json:20240303")
}
