import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rpcs4.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rpcs4.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        //
        // ABI policy - READ THIS BEFORE CHANGING.
        //
        // The PS4 guest CPU is an AMD Jaguar x86-64. RPCS4 executes guest code
        // *natively* on the host CPU through the System V AMD64 ABI, with no
        // binary translator in between (see upstream README). Therefore the
        // emulation core can only ever produce correct results when the host
        // CPU is also x86-64.
        //
        // We ship the x86_64 ABI exclusively: Android emulators on x86 hosts,
        // x86_64 tablets and Chromebooks can run games today. arm64-v8a would
        // require a full x86-64 -> AArch64 user-space translator (Box64-style)
        // which does not exist yet; see PORTING.md for the roadmap.
        //
        ndk { abiFilters += listOf("x86_64") }

        externalNativeBuild {
            cmake {
                arguments +=
                    listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DENABLE_USER_BUILD=ON",
                    )
                cppFlags += "-std=c++23"
                // NDK r27 ships clang 18 with solid C++23 support.
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = false
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
}
