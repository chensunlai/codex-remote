import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val productVersion =
    (JsonSlurper().parse(rootProject.file("../package.json")) as Map<*, *>)["version"] as String
val productVersionParts = productVersion.substringBefore('-').split('.').map(String::toInt)
require(productVersionParts.size == 3) { "Root package version must use major.minor.patch" }
val productVersionCode =
    productVersionParts[0] * 1_000_000 + productVersionParts[1] * 1_000 + productVersionParts[2]
val signingPropertiesFile = rootProject.file("../signing/keystore.properties")
val releaseSigningProperties = if (signingPropertiesFile.isFile) {
    Properties().apply {
        signingPropertiesFile.inputStream().use { load(it) }
    }.also { properties ->
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword", "storeType").forEach { key ->
            require(!properties.getProperty(key).isNullOrBlank()) {
                "Missing $key in ${signingPropertiesFile.path}"
            }
        }
        val configuredStore = signingPropertiesFile.parentFile.resolve(properties.getProperty("storeFile"))
        require(configuredStore.isFile) { "Android release keystore not found: ${configuredStore.path}" }
    }
} else {
    null
}

android {
    namespace = "dev.codexremote.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.codexremote.app"
        minSdk = 26
        targetSdk = 37
        versionCode = productVersionCode
        versionName = productVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        releaseSigningProperties?.let { properties ->
            create("release") {
                storeFile = signingPropertiesFile.parentFile.resolve(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
                storeType = properties.getProperty("storeType")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/DEPENDENCIES",
    )

    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // 0.39.2 is the last release aligned with this project's Kotlin 2.3 and Compose 1.10 toolchain.
    //noinspection NewerVersionAvailable
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.39.2")
    //noinspection NewerVersionAvailable
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.39.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
