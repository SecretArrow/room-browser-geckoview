plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

// The engine module owns EVERY reference to a concrete rendering engine in the
// tree. `:app` depends on it as `implementation`, so `org.mozilla.geckoview`
// types are not on `:app`'s compile classpath at all -- the facade in
// com.roombrowser.engine is the only thing that crosses the boundary. A JVM
// guard test on the app side enforces that, so this is not a convention that
// can quietly rot.
//
// That is what makes the two editions of this browser the same codebase: every
// feature above the facade is shared verbatim, and only the classes in this
// module differ between the WebView edition and the GeckoView edition.
android {
    namespace = "com.roombrowser.engine"
    // 36, not 35: GeckoView's POM drags in androidx.core 1.18.0 and
    // androidx.media3 1.10.1, and their AAR metadata refuses to be consumed by
    // anything compiled below API 36. targetSdk stays 35 in :app — compiling
    // against newer APIs and opting in to newer runtime behaviour are separate
    // decisions and only the first one is forced here.
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 28
        // No targetSdk: a library is not an application and AGP rejects one
        // here. The consuming :app owns the platform behaviour contract.
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
}

dependencies {
    // `api`, not `implementation`: Profile and ProfileId appear in the facade
    // signatures, so every consumer needs them on its own classpath.
    api(project(":core:domain"))

    // The engine's own KTX-form calls. GeckoView's POM drags in `androidx.core`
    // -- the Java artifact, not the `-ktx` one -- and as a RUNTIME dependency,
    // so the KTX extensions are not on this module's compile classpath without
    // this line.
    implementation(libs.androidx.core.ktx)

    // `implementation`, deliberately: this is the single line that keeps
    // GeckoView out of `:app`'s compile classpath. Changing it to `api` would
    // silently undo the whole facade.
    implementation(libs.geckoview)

    // `androidx.annotation.OptIn` marks the three calls into GeckoView's
    // experimental preference controller, and that annotation is declared in
    // androidx.annotation:annotation-experimental. GeckoView's POM names that
    // artifact AND androidx.annotation:annotation, and both are RUNTIME
    // dependencies there, so neither reaches this module's compile classpath on
    // its own. The WebView implementation used to sit here and pulled
    // annotation-experimental in transitively through androidx.webkit; removing
    // that package removed the provider, and the compile failed on these three
    // annotation lines.
    implementation(libs.androidx.annotation.experimental)

    // Engine calls suspend: an acknowledgement from the engine arrives on its
    // own thread and the caller waits for it.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
