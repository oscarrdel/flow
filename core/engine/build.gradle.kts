plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.rainyworks.flow.core.engine"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.transformer)
    api(libs.androidx.media3.effect)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
