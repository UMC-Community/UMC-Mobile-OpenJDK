plugins {
    id("com.android.application")
}

android {
    namespace = "dev.umc.mobile.jvmdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.umc.mobile.jvmdemo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Native libraries must be extracted so jspawnhelper can be executed from disk
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libjspawnhelper.so")
        }
    }
}

dependencies {
    implementation(project(":umc-jvm"))
}
