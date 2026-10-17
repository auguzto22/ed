import java.util.Properties
import javax.inject.Inject

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

// ---- Motor de velocidade em Rust (blitz-speed/) -------------------------------------------------
//
// A lógica de velocidade (curvas, mapa de tempo, presets, câmera lenta, motion blur na GPU, áudio)
// é a biblioteca nativa libblitz_speed.so. Requisitos para compilar o app:
//   * Rust (rustup) com os alvos Android:
//       rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
//   * cargo-ndk:  cargo install cargo-ndk
//   * Android NDK instalado pelo SDK Manager (ou ANDROID_NDK_HOME apontando para ele).
// Os testes de unidade da JVM usam a mesma biblioteca compilada para o computador (cargo build).

val blitzSpeedCrate = rootProject.layout.projectDirectory.dir("blitz-speed")
val blitzSpeedSources = files(blitzSpeedCrate.file("Cargo.toml"), blitzSpeedCrate.file("Cargo.lock"), fileTree(blitzSpeedCrate.dir("src")))

abstract class CargoNdkBuild : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val crateDir: DirectoryProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Internal abstract val ndkDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val ndk = runCatching { ndkDir.orNull?.asFile }.getOrNull()?.takeIf { it.isDirectory }
            ?: System.getenv("ANDROID_NDK_HOME")?.let(::File)?.takeIf { it.isDirectory }
            ?: throw GradleException("Android NDK não encontrado. Instale pelo SDK Manager (NDK side by side) ou defina ANDROID_NDK_HOME — o motor de velocidade (blitz-speed) é compilado em Rust.")
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val result = exec.exec {
            workingDir = crateDir.get().asFile
            environment("ANDROID_NDK_HOME", ndk.absolutePath)
            commandLine(buildList {
                add("cargo"); add("ndk")
                abis.get().forEach { add("-t"); add(it) }
                add("--platform"); add(minSdk.get().toString())
                add("-o"); add(out.absolutePath)
                add("build"); add("--release")
            })
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) throw GradleException(
            "Falha ao compilar blitz-speed para Android (cargo ndk, código ${result.exitValue}). Verifique: rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android; cargo install cargo-ndk.")
    }
}

abstract class CargoHostBuild : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val crateDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val result = exec.exec {
            workingDir = crateDir.get().asFile
            commandLine("cargo", "build", "--release")
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) throw GradleException("Falha ao compilar blitz-speed para os testes (cargo build --release, código ${result.exitValue}). Instale o Rust: https://rustup.rs")
    }
}

val cargoBuildAndroid = tasks.register<CargoNdkBuild>("cargoBuildAndroid") {
    group = "build"; description = "Compila o motor de velocidade (Rust) para os ABIs Android."
    sources.from(blitzSpeedSources)
    crateDir.set(blitzSpeedCrate)
    abis.set(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
    minSdk.set(29)
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
}

val cargoBuildHost = tasks.register<CargoHostBuild>("cargoBuildHost") {
    group = "verification"; description = "Compila o motor de velocidade (Rust) para a JVM dos testes."
    sources.from(blitzSpeedSources)
    crateDir.set(blitzSpeedCrate)
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildAndroid, CargoNdkBuild::outputDir)
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    systemProperty("java.library.path", blitzSpeedCrate.dir("target/release").asFile.absolutePath)
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
    // Full-resolution per-subject masks for photo clips (Google Play services, model on demand).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
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
