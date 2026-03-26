plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// Version – read from version.properties when present (written by CI).
// Falls back to a local dev value so the project builds on developer machines
// without any extra setup.
// ---------------------------------------------------------------------------
val versionPropsFile = rootProject.file("version.properties")
val versionProps = java.util.Properties().also { props ->
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { props.load(it) }
    }
}
val appVersionCode: Int = versionProps.getProperty("VERSION_CODE", "1").toInt()
val appVersionName: String = versionProps.getProperty("VERSION_NAME", "0.0.0-dev")

android {
    namespace = "io.github.carstenleue.sshfsprovider"
    compileSdk = 34

    // -------------------------------------------------------------------------
    // Signing – populated from environment variables injected by CI.
    // When the variables are absent (local dev, unsigned PR builds) the release
    // variant is left unsigned and Gradle produces app-release-unsigned.apk.
    // -------------------------------------------------------------------------
    val signingKeyPath: String? = System.getenv("SIGNING_KEY_PATH")

    if (signingKeyPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeyPath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.carstenleue.sshfsprovider"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Apply the signing config only when the key path is available.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.security.crypto)
    implementation(libs.jsch)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)
}
