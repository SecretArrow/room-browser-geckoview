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
// The engine. This is the ONLY module that may name a concrete rendering
// engine, and it does so behind the com.roombrowser.engine facade. The app
// depends on it as `implementation`, so no engine type reaches :app's compile
// classpath -- enforced by EngineBoundaryTest rather than by convention.
include(":engine")
