plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sarvam.voiceassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sarvam.voiceassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Shown in Settings. An installed build that predated a fix looked identical to one
        // that had it, which cost several rounds of "rebuild and try again".
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Lets unit tests call android.util.Base64 and friends without Robolectric.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    // Pinned deliberately. biometric 1.1.0 pulls androidx.fragment 1.2.5, whose
    // FragmentActivity packs a fragment index into the upper 16 bits of a request code and
    // rejects anything larger. The modern ActivityResultRegistry hands it full-range random
    // codes, so every runtime permission request crashed with
    // "Can only use lower 16 bits for requestCode". Fragment 1.3.0 dropped that packing.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)

    debugImplementation(libs.androidx.ui.tooling)
}

/**
 * The short commit this build came from, read from .git directly rather than by running
 * git, which is often not on the PATH that Android Studio gives Gradle on Windows.
 * Falls back to "unknown" rather than failing the build.
 */
fun gitCommit(): String = runCatching {
    val gitDir = rootProject.projectDir.parentFile.resolve(".git")
    val head = gitDir.resolve("HEAD").readText().trim()
    val sha = if (head.startsWith("ref: ")) {
        val ref = head.removePrefix("ref: ")
        gitDir.resolve(ref).takeIf { it.isFile }?.readText()?.trim()
            ?: gitDir.resolve("packed-refs").readLines()
                .first { it.endsWith(" $ref") }
                .substringBefore(' ')
    } else {
        head
    }
    sha.take(7)
}.getOrDefault("unknown")
