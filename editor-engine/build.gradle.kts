import java.util.Properties

plugins {
    id("com.android.library")
}

val localProperties = Properties()
rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { localProperties.load(it) }
val localGeminiApiKey = localProperties.getProperty("GEMINI_API_KEY", "")
val captionBackendUrl = providers.gradleProperty("RECLY_CAPTION_BACKEND_URL").orNull ?: ""

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

android {
    namespace = "com.recly.editor.engine"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // The value is read only from the untracked root local.properties.
            buildConfigField("String", "GEMINI_API_KEY", buildConfigString(localGeminiApiKey))
            buildConfigField("String", "RECLY_CAPTION_BACKEND_URL", buildConfigString(captionBackendUrl))
        }
        release {
            // Never copy a local secret into a release variant or the APK.
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "RECLY_CAPTION_BACKEND_URL", buildConfigString(captionBackendUrl))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-media"))
    implementation(project(":core-ui"))

    implementation("androidx.annotation:annotation:1.8.1")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-detection:16.1.7")
    // ML Kit has no stable pose-detection release; 18.0.0-beta5 is the newest published.
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.airbnb.android:lottie:6.4.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
