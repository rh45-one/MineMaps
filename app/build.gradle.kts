import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun prop(key: String, default: String): String = localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "app.minemaps"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "app.minemaps"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "TILEJSON_URL", "\"${prop("minemaps.tilejson", "https://tiles.openfreemap.org/planet")}\"")
        buildConfigField("String", "PHOTON_URL", "\"${prop("minemaps.photon", "https://photon.komoot.io")}\"")
        buildConfigField("String", "USER_AGENT", "\"MineMaps/$versionName (Android)\"")
    }

    val releaseStoreFile = prop("minemaps.release.storeFile", "")
    signingConfigs {
        if (releaseStoreFile.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = prop("minemaps.release.storePassword", "")
                keyAlias = prop("minemaps.release.keyAlias", "")
                keyPassword = prop("minemaps.release.keyPassword", "")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the debug keystore when no release signing is configured in local.properties.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":mapengine"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    debugImplementation(libs.leakcanary)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.car.app.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
