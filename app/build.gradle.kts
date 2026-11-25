import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from signing.properties at the repo root (gitignored; CI writes it from secrets).
val releaseSigningFile = rootProject.file("signing.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "com.niriun.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.niriun.app"
        // Android 10: MediaCodec Opus encoder (c2.android.opus.encoder) and FULL_LOW_LATENCY Wi-Fi lock.
        minSdk = 29
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseSigning.getProperty("storeFile")))
                storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningFile.isFile) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val validateReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningFile.isFile) { "Create signing.properties before building a release. See docs/ci-cd.md." }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach {
            check(!releaseSigning.getProperty(it).isNullOrBlank()) { "Missing release signing property: $it" }
        }
        check(rootProject.file(releaseSigning.getProperty("storeFile")).isFile) { "Release keystore not found." }
    }
}

tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    dependsOn(validateReleaseSigning)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
