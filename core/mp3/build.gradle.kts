plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.rainyworks.flow.core.mp3"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild.cmake {
            arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
        }
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
