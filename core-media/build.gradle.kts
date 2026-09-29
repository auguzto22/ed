plugins {
    id("com.android.library")
}

android {
    namespace = "com.recly.core.media"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
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
    implementation("androidx.annotation:annotation:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
