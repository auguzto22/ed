import java.util.Properties

plugins {
    id("com.android.application")
}

// Contas (Supabase Auth). Os valores vêm do local.properties (não versionado) ou de -P na linha de
// comando. A chave anon do Supabase é pública por natureza; a segurança vem das políticas RLS.
val accountProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun accountSetting(name: String): String =
    providers.gradleProperty(name).orNull ?: accountProperties.getProperty(name, "")
// Um release sem Supabase deixaria todo mundo preso na tela de conta: falha o build em vez disso.
if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) } &&
    (accountSetting("SUPABASE_URL").isBlank() || accountSetting("SUPABASE_ANON_KEY").isBlank())) {
    throw GradleException("Release sem SUPABASE_URL/SUPABASE_ANON_KEY. Configure o local.properties (veja docs/accounts.md).")
}
fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.recly.editor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.recly.editor"
        minSdk = 29
        targetSdk = 37
        versionCode = 64
        versionName = "4.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", quoted(accountSetting("SUPABASE_URL").trimEnd('/')))
        buildConfigField("String", "SUPABASE_ANON_KEY", quoted(accountSetting("SUPABASE_ANON_KEY")))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(accountSetting("GOOGLE_WEB_CLIENT_ID")))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // Só para testar o editor sem passar pelo login: RECLY_SKIP_LOGIN=true no local.properties.
            buildConfigField("boolean", "SKIP_LOGIN", accountSetting("RECLY_SKIP_LOGIN").equals("true", ignoreCase = true).toString())
        }
        release {
            // Release sempre exige conta.
            buildConfigField("boolean", "SKIP_LOGIN", "false")
            isMinifyEnabled = false
            isShrinkResources = false
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

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core-media"))
    implementation(project(":core-ui"))
    implementation(project(":editor-engine"))

    implementation("androidx.annotation:annotation:1.8.1")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")

    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Login com Google pelo Credential Manager (token enviado ao Supabase).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
