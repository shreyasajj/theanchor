import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The release key. Android installs an update only when it carries the same
 * signature as the installed app, so every build must use one fixed key. CI
 * passes it in through ANCHOR_* environment variables (from repository
 * secrets); on a developer machine it is read from
 * ~/.android/anchor-release.properties. With neither, release falls back to
 * the debug key, which is created per machine: such a build installs only
 * fresh, never over an existing one.
 */
val releaseKey: Map<String, String>? =
    System.getenv("ANCHOR_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file ->
        mapOf(
            "storeFile" to file,
            "storePassword" to System.getenv("ANCHOR_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("ANCHOR_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("ANCHOR_KEY_PASSWORD").orEmpty(),
        )
    } ?: File(System.getProperty("user.home"), ".android/anchor-release.properties")
        .takeIf { it.isFile }
        ?.let { file ->
            val props = Properties().apply { file.inputStream().use { load(it) } }
            props.stringPropertyNames().associateWith { props.getProperty(it) }
        }

android {
    namespace = "com.anchor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anchor"
        minSdk = 33
        targetSdk = 35
        // CI numbers each build so every update is a step up; local builds stay 1.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getValue("storeFile"))
                storePassword = releaseKey.getValue("storePassword")
                keyAlias = releaseKey.getValue("keyAlias")
                keyPassword = releaseKey.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Personal sideload: keep stack traces readable, but ship a real
            // release build. Debug Compose is markedly slower to scroll.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.documentfile)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
