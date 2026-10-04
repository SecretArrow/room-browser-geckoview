pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView ships from Mozilla's own maven2 rather than Google's,
        // because the engine is bundled INTO the APK instead of being taken
        // from the device the way WebView is. The group filter is what makes
        // this addition safe rather than merely convenient: a Mozilla outage
        // cannot affect resolution of anything outside org.mozilla.geckoview,
        // and resolution stays as fast as it is today for every other
        // coordinate. FAIL_ON_PROJECT_REPOS above stays on — this is the one
        // sanctioned exception, declared in one place.
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "RoomBrowser"

include(":app")
include(":core:domain")
include(":core:wallet")
// TODO(geckoview): include(":engine") lands together with the module itself, the
// ABI restriction in app/build.gradle.kts, and the CI verification steps -- as
// one change. Adding the include on its own would fail configuration, and adding
// the module without the ABI restriction would make the debug build package
// three copies of a ~150 MB engine.
