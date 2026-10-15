// Release signing comes from Gradle properties (~/.gradle/gradle.properties) or
// environment variables of the same name — never from files in this repo.
fun signingValue(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nordlys.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nordlys.camera"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.0-beta4"
    }

    signingConfigs {
        val storePath = signingValue("NORDLYS_KEYSTORE_PATH")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingValue("NORDLYS_KEYSTORE_PASSWORD")
                keyAlias = signingValue("NORDLYS_KEY_ALIAS")
                keyPassword = signingValue("NORDLYS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Unsigned when no keystore is configured (e.g. contributors' local builds).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
