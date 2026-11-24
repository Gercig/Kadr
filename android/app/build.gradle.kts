plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ru.kadr.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ru.kadr.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 38
        versionName = "2.4.13"

        // Адрес сервера берётся из gradle.properties (kadr.serverUrl)
        val serverUrl = (project.findProperty("kadr.serverUrl") as String?) ?: "http://127.0.0.1:8000/"
        buildConfigField("String", "BASE_URL", "\"$serverUrl\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)   // сканер QR-кодов для контролёра
    debugImplementation(libs.androidx.compose.ui.tooling)
}
