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
        // MapLibre ships on Maven Central; jitpack is kept for community
        // bits we may pull later (e.g. a PMTiles plugin, the nav-SDK fork).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Vela"

// Two modules, deliberately. `:core` is the "extractor" - all Google interop,
// routing, location and voice live here with no UI dependency, the same way
// NewPipe keeps NewPipeExtractor as a standalone library. `:app` is the
// Compose UI shell on top. Split further (core:model / core:data / …)
// once the surface grows.
include(":app")
include(":core")
// Vendored softkey engine for keypad / D-pad phones (LGPL-3.0, source-identical to
// github.com/theOnionsAreWatching/yapchik). Own module = cleanly replaceable + re-syncable.
include(":yapchik")
// Standalone JVM tool (not an app dependency) - builds per-region routing graphs off-device.
// Run: ./gradlew :tools:graphbuilder:run --args="<region.osm.pbf> <out-dir>"
include(":tools:graphbuilder")
