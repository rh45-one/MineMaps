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

fun git(vararg args: String): String? = runCatching {
    val out = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() } else null
}.getOrNull()

val semver = Regex("""^(\d+)\.(\d+)\.(\d+)(-rc\.\d+)?$""")

val appVersion: String = run {
    val explicit = (findProperty("minemaps.version") as String?)?.takeIf { it.isNotBlank() } ?: System.getenv("MINEMAPS_VERSION")?.takeIf { it.isNotBlank() }
    val fromTag = git("describe", "--tags", "--abbrev=0", "--match", "v*")?.removePrefix("v")
    val base = explicit ?: fromTag ?: "0.0.0"
    if (!semver.matches(base)) throw GradleException("Invalid version '$base': expected MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-rc.N")
    val exact = git("describe", "--tags", "--exact-match", "--match", "v*") != null
    if (explicit == null && fromTag != null && !exact) "$base-dev+${git("rev-parse", "--short", "HEAD") ?: "unknown"}" else base
}

val appVersionCode: Int = semver.matchEntire(appVersion.substringBefore("-dev"))!!.groupValues.let { (it[1].toInt() * 1_000_000 + it[2].toInt() * 1_000 + it[3].toInt()).coerceAtLeast(1) }

class SigningCreds(val file: File, val storePassword: String, val alias: String, val keyPassword: String)

val releaseCreds: SigningCreds? = run {
    val env = System.getenv()
    val envPath = env["MINEMAPS_KEYSTORE_PATH"]
    if (!envPath.isNullOrBlank() && env["MINEMAPS_KEYSTORE_PASSWORD"] != null && env["MINEMAPS_KEY_ALIAS"] != null && env["MINEMAPS_KEY_PASSWORD"] != null) {
        return@run SigningCreds(File(envPath), env["MINEMAPS_KEYSTORE_PASSWORD"]!!, env["MINEMAPS_KEY_ALIAS"]!!, env["MINEMAPS_KEY_PASSWORD"]!!)
    }
    val kpFile = File(System.getProperty("user.home"), ".config/minemaps/keystore.properties")
    if (kpFile.exists()) {
        val kp = Properties().apply { kpFile.inputStream().use { load(it) } }
        val path = kp.getProperty("storeFile")
        if (!path.isNullOrBlank() && File(path.replaceFirst("~", System.getProperty("user.home"))).exists()) {
            return@run SigningCreds(File(path.replaceFirst("~", System.getProperty("user.home"))), kp.getProperty("storePassword") ?: "", kp.getProperty("keyAlias") ?: "", kp.getProperty("keyPassword") ?: "")
        }
    }
    val local = prop("minemaps.release.storeFile", "")
    if (local.isNotEmpty()) SigningCreds(rootProject.file(local), prop("minemaps.release.storePassword", ""), prop("minemaps.release.keyAlias", ""), prop("minemaps.release.keyPassword", "")) else null
}

if (releaseCreds == null && gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    logger.warn("WARNING: no release keystore found (env MINEMAPS_KEYSTORE_*, ~/.config/minemaps/keystore.properties, local.properties). This release APK is signed with the DEBUG key and can NOT be updated by or from official releases.")
}

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
        versionCode = appVersionCode
        versionName = appVersion

        buildConfigField("String", "TILEJSON_URL", "\"${prop("minemaps.tilejson", "https://tiles.openfreemap.org/planet")}\"")
        buildConfigField("String", "PHOTON_URL", "\"${prop("minemaps.photon", "https://photon.komoot.io")}\"")
        buildConfigField("String", "USER_AGENT", "\"MineMaps/$versionName (Android)\"")
        buildConfigField("String", "UPDATE_REPO", "\"${prop("minemaps.updateRepo", "rh45-one/MineMaps")}\"")
        buildConfigField("String", "UPDATE_API_BASE", "\"https://api.github.com\"")
        buildConfigField("boolean", "UPDATES_ENABLED", "false")
    }

    signingConfigs {
        releaseCreds?.let { c ->
            create("release") {
                storeFile = c.file
                storePassword = c.storePassword
                keyAlias = c.alias
                keyPassword = c.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "UPDATES_ENABLED", "true")
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
