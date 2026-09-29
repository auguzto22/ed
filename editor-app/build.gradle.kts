plugins {
    id("com.android.application")
}

android {
    namespace = "com.recly.editor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.recly.editor"
        minSdk = 29
        targetSdk = 37
        versionCode = 64
        versionName = "4.1.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-transformer:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.mlkit:text-recognition:16.0.0")

    testImplementation("junit:junit:4.13.2")
}
