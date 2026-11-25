plugins {
    id("com.android.library")
}

android {
    namespace = "dev.umc.mobile.jvm"
    compileSdk = 35
    ndkPath = "/opt/android-ndk-r30"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild {
                arguments += "-j4"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    sourceSets["main"].jniLibs.srcDir("src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
