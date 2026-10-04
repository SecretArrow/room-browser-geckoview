// Room Browser — root build configuration
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Keep kotlin-stdlib at the version this project's own compiler is.
//
// GeckoView's POM declares `org.jetbrains.kotlin:kotlin-stdlib:2.3.21` at
// compile scope, and Gradle resolves a version conflict by taking the highest,
// so `:engine` — the one module that sees GeckoView — was compiling Kotlin
// 2.0.21 sources against a 2.3.21 stdlib. That is not a warning: the compiler
// refuses the newer module metadata outright
//
//   Module was compiled with an incompatible version of Kotlin. The binary
//   version of its metadata is 2.3.0, expected version is 2.0.0.
//
// and then CRASHES while reporting it
// (`FirIncompatibleClassExpressionChecker`: "source must not be null"), which
// surfaces as an internal compiler error on an unrelated file and says nothing
// about the cause.
//
// Pinning the stdlib back, rather than raising Kotlin to 2.3.21, is decided on
// evidence and worth re-checking if either side moves:
//
//  - GeckoView ships NO Kotlin bytecode. Its classes.jar (549
//    org.mozilla.geckoview classes, 422 org.mozilla.gecko, the org.webrtc set)
//    contains zero classes carrying a `kotlin/Metadata` annotation and no
//    `.kotlin_module` at all — read out of the AAR itself. The stdlib entry in
//    its POM is an artefact of Mozilla's own toolchain, so pinning it back
//    cannot break the engine at runtime: there is no Kotlin in it to call a
//    2.3 API.
//  - Raising Kotlin instead would drag KSP, the Compose compiler plugin and
//    every Kotlin module in the tree to 2.3.21 in one step, for a dependency
//    that needs none of it. That belongs in its own change, with its own run.
//
// If GeckoView ever starts shipping Kotlin classes, re-examine this pin first:
// at that point the newer stdlib is a real requirement and Kotlin itself has
// to move.
val kotlinStdlibVersion = libs.versions.kotlin.get()
subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                requested.name.startsWith("kotlin-stdlib")
            ) {
                useVersion(kotlinStdlibVersion)
            }
        }
    }
}
