import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// The release workflow passes the tag it is publishing (v1.0.95) through
// RB_VERSION_NAME, so an APK's filename, the versionName the phone reports and
// the About screen all name the release they came from.  Without it every
// release shipped "1.0.0": the files collided in a downloads folder, and a
// build could not be told apart from any other in Android's app info.  A local
// build keeps the fallback.
val baseVersionName =
    System.getenv("RB_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"
// CI produces incrementing version codes per pipeline run (spec section 57);
// local builds fall back to 1.
val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val baseVersionCode = if (ciBuildNumber > 0) ciBuildNumber else 1

// The ABIs the bundled engine actually ships native libraries for. Measured
// from the AAR rather than assumed: GeckoView publishes arm64-v8a, armeabi-v7a
// and x86_64, and NO 32-bit x86 build at all.
//
// That absence is why "x86" is REMOVED from the split set rather than merely
// deprioritised. An x86 APK would build, install, launch, and then die at
// System.loadLibrary -- a crash on exactly the devices least able to report
// it. Not shipping one is the honest answer, and this list is the single place
// the fact is written down.
val geckoViewAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "com.roombrowser"
    // 36 is forced by the engine, not chosen: see android/engine/build.gradle.kts
    // and the note on `geckoview` in gradle/libs.versions.toml. targetSdk below
    // is a separate decision and stays where it was.
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.roombrowser"
        minSdk = 28
        targetSdk = 35
        versionCode = baseVersionCode
        versionName = baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // ABI splits.
    //
    // RB_ABIS (comma-separated) narrows the split set. There is one variable
    // rather than separate debug and release ones because `splits` is a
    // project-wide setting in AGP -- there is no per-build-type equivalent --
    // so two variables would look like they did something they cannot.
    //
    // This matters far more than it used to. The engine is now BUNDLED into
    // the APK instead of being taken from the device, so every ABI adds its
    // own copy of a ~150 MB engine. The former default -- four splits plus a
    // universal APK carrying all of them -- would push roughly half a gigabyte
    // of native libraries through every CI job that only wanted to know
    // whether the app compiles. The jobs narrow it accordingly: quality builds
    // arm64-v8a, e2e builds the emulator's own x86_64.
    splits {
        abi {
            isEnable = true
            reset()
            val focusAbis = System.getenv("RB_ABIS")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (focusAbis.isNullOrEmpty()) {
                include(*geckoViewAbis.toTypedArray())
            } else {
                focusAbis.forEach { abi ->
                    require(abi in geckoViewAbis) {
                        "ABI '$abi' has no GeckoView native library; the engine ships " +
                            "only $geckoViewAbis. See the note above geckoViewAbis."
                    }
                }
                include(*focusAbis.toTypedArray())
            }
            // Never a universal APK. It is the sum of every ABI, which makes it
            // the single largest artifact this build can produce, and no job
            // needs it: the release ships per-ABI splits and the Play listing
            // takes splits too.
            isUniversalApk = false
        }
    }

    // Per-ABI output naming + distinct version codes (modern Variant API).
    androidComponents {
        onVariants { variant ->
            val buildTypeName = variant.buildType ?: "release"
            variant.outputs.forEach { output ->
                val impl = output as? VariantOutputImpl ?: return@forEach
                val abi = impl.filters.firstOrNull()?.identifier
                val abiRank = when (abi) {
                    "arm64-v8a" -> 4
                    "armeabi-v7a" -> 3
                    "x86_64" -> 2
                    else -> 0
                }
                impl.versionCode.set(abiRank * 100_000 + baseVersionCode)
                impl.outputFileName.set(
                    "room-browser-v$baseVersionName-${abi ?: "universal"}-$buildTypeName.apk"
                )
            }
        }
    }

    val envKeystore = providers.environmentVariable("ROOMBROWSER_KEYSTORE").orNull
    val envStorePassword = providers.environmentVariable("ROOMBROWSER_STORE_PASSWORD").orNull
    val envKeyAlias = providers.environmentVariable("ROOMBROWSER_KEY_ALIAS").orNull
    val envKeyPassword = providers.environmentVariable("ROOMBROWSER_KEY_PASSWORD").orNull

    signingConfigs {
        if (envKeystore != null && envStorePassword != null && envKeyAlias != null && envKeyPassword != null) {
            create("releaseFromEnv") {
                storeFile = file(envKeystore)
                storePassword = envStorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signing configuration comes from environment variables —
            // no secrets are ever committed to Git (spec section 58).
            // RB_UNSIGNED_RELEASE=true (CI release job) attaches NO signing
            // config: the APKs come out UNSIGNED for manual signing with the
            // production keystore (the interim policy until the passphrase
            // is provided). Local dev builds keep the debug-key fallback so
            // they remain installable.
            //
            // WHY THIS IS PRINTED: an APK that comes out unsigned, or signed
            // with the debug key, is indistinguishable from a correct build
            // until someone checks the certificate — so the decision this
            // block makes is stated in the log, every build, rather than left
            // to be inferred from the artifact.
            val fromEnv = signingConfigs.findByName("releaseFromEnv")
            val unsignedRelease = System.getenv("RB_UNSIGNED_RELEASE") == "true"
            logger.lifecycle(
                "release signing: unsignedRelease=$unsignedRelease " +
                    "keystore=${envKeystore ?: "<unset>"} " +
                    "alias=${if (envKeyAlias.isNullOrBlank()) "<unset>" else envKeyAlias} " +
                    "storePassword=${if (envStorePassword.isNullOrBlank()) "<unset>" else "set"} " +
                    "keyPassword=${if (envKeyPassword.isNullOrBlank()) "<unset>" else "set"} " +
                    "-> ${if (unsignedRelease) "UNSIGNED" else (fromEnv?.name ?: "debug")}"
            )
            if (!unsignedRelease) {
                signingConfig = fromEnv ?: signingConfigs.getByName("debug") // dev fallback only
            }
        }
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("releaseFromEnv")
                ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles("proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        resources {
            // License/metadata files that multiple transitive jars ship in
            // identical paths (:core:wallet's web3j-crypto tree: jackson
            // core/databind/annotations + tuweni-bytes/units each ship
            // LICENSE/NOTICE; tuweni also DISCLAIMER; jackson-core ships the
            // FastDoubleParser-* variants). None is read at runtime; the
            // patterns cover the whole family (verified by listing every
            // jar's META-INF — see the worklog, Task 5-ci-fix).
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/DISCLAIMER"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/*LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:domain"))
    // Multi-chain wallet core (chain adapters + crypto). App-side wallet
    // layers (contract/engine/bridge/repository/UI) build on it.
    implementation(project(":core:wallet"))

    // The engine. `implementation`, never `api`: this is what keeps every
    // org.mozilla.geckoview type off this module's compile classpath, so the
    // app physically cannot write code that depends on which engine it runs.
    // EngineBoundaryTest fails the build if an engine import ever appears
    // above the facade, so this stays true rather than merely intended.
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.webkit)

    // Guava, for `com.google.common.util.concurrent.ListenableFuture` -- the
    // type QrScannerActivity calls `addListener` on, because that is what
    // CameraX's `ProcessCameraProvider.getInstance()` returns.
    //
    // WHY THIS LINE IS HERE, because deleting it looks harmless and is not:
    // the class was reaching this module TRANSITIVELY, and the engine broke
    // that path. CameraX depends on `com.google.guava:listenablefuture:1.0`,
    // the stub artifact that exists so libraries can name ListenableFuture
    // without pulling in all of Guava. Bundling the engine put
    // geckoview -> androidx.media3:media3-common -> com.google.guava:guava on
    // the RUNTIME classpath, and Guava declares that it PROVIDES the
    // `listenablefuture` capability at version
    // `9999.0-empty-to-avoid-conflict-with-guava` together with a dependency
    // on that very artifact -- which is empty, and exists only so that "9999"
    // wins the conflict and the duplicate class never ships. It wins, the stub
    // is dropped, and on the runtime classpath that is correct: Guava's own
    // jar carries ListenableFuture.
    //
    // It stops being correct one configuration over. AGP resolves the debug
    // compile classpath CONSISTENTLY with the runtime one, so the compile
    // classpath inherits the same choice -- and GeckoView is not on the
    // compile classpath, because `:engine` keeps it to `implementation`. So
    // the compile classpath got the empty artifact, not Guava, and the
    // compiler reported what it actually saw:
    //
    //   Cannot access class 'com.google.common.util.concurrent.ListenableFuture'
    //
    // Declaring Guava here is the honest repair rather than a workaround for
    // it: this module's code genuinely uses the type, so it should declare the
    // library that provides it instead of inheriting it from whichever engine
    // happens to be in the tree. The alternative -- forcing
    // `com.google.guava:listenablefuture` back to 1.0 -- was rejected because
    // it would put the stub back alongside Guava and reintroduce the duplicate
    // class that the 9999 artifact exists to prevent.
    //
    // Cost is nil: Guava is already in the APK through the engine, and R8
    // shrinks it in release builds.
    implementation(libs.guava)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.okhttp)
    implementation(libs.okhttp.doh)
    implementation(libs.zxing.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // JVM tests of the data layer fake Room DAOs with mockk (no device needed).
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.okhttp.mockwebserver)
}
