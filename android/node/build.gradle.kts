plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

// An embedded Node.js runtime, in a process of its own.
//
// NOTHING HERE NAMES A RENDERING ENGINE, so this module is shared: it is
// byte-identical in both editions, and the two ABIs each edition ships are the
// two this runtime is built for. What differs between the editions is only the
// app-level build file that consumes it.
//
// `libnode.so` and its headers are NOT in the tree. Each is 58-65 MB, which
// does not belong in a public repository, so CI downloads the pinned release
// and unpacks it into `src/main/jniLibs/<abi>/` and
// `src/main/cpp/nodejs-mobile/include/`. A checkout without them still
// configures -- see the CMakeLists -- and a build without them produces an
// APK whose runtime reports itself unavailable rather than one that will not
// compile.
android {
    namespace = "com.roombrowser.node"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 28
        externalNativeBuild {
            cmake {
                // libnode.so is built against the shared C++ runtime, so the
                // APK has to carry libc++_shared.so as well.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
