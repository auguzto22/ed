import java.util.Properties
import org.gradle.api.tasks.Exec

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
    ndkVersion = "26.3.11579264"

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

val sdkDirectory = localProperties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    ?: error("Android SDK directory is required to build the Rust preview")
val androidNdkDirectory = file("$sdkDirectory/ndk/26.3.11579264")
val cargoBinary = System.getenv("CARGO")
    ?: file("${System.getProperty("user.home")}/.cargo/bin/cargo").takeIf { it.isFile }?.absolutePath
    ?: "cargo"
val rustPreviewOutput = layout.projectDirectory.dir("src/main/jniLibs")
val buildRustPreview = tasks.register<Exec>("buildRustPreview") {
    workingDir(rootProject.file("editor-preview-native"))
    environment("ANDROID_NDK_HOME", androidNdkDirectory.absolutePath)
    commandLine(
        cargoBinary, "ndk", "-t", "arm64-v8a", "-t", "x86_64",
        "-o", rustPreviewOutput.asFile.absolutePath,
        "build", "--release",
    )
    inputs.files(fileTree(rootProject.file("editor-preview-native")) {
        include("Cargo.toml", "Cargo.lock", "src/**/*.rs", "shaders/**/*.frag")
    })
    inputs.files(fileTree("src/main/assets/editor/transitions"), file("src/main/assets/editor/transitions.json"),
        file("src/main/res/raw/transition_header.glsl"), file("src/main/res/raw/transition_footer.glsl"))
    outputs.dir(rustPreviewOutput)
}

tasks.named("preBuild").configure { dependsOn(buildRustPreview) }

val buildRustTransitionHost = tasks.register<Exec>("buildRustTransitionHost") {
    workingDir(rootProject.file("editor-preview-native"))
    commandLine(cargoBinary, "build", "--offline")
}
tasks.withType<Test>().configureEach {
    dependsOn(buildRustTransitionHost)
    systemProperty("java.library.path", rootProject.file("editor-preview-native/target/debug").absolutePath)
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
