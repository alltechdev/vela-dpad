// Vendored copy of Yapchik (https://github.com/theOnionsAreWatching/yapchik), the softkey
// engine for keypad / D-pad phones, under LGPL-3.0-or-later (see LICENSE + NOTICE in this
// module). Kept as its OWN module - source-identical to upstream, its own package - so it
// stays a cleanly replaceable library (LGPL) and can be re-synced from upstream without
// touching :app. Zero dependencies: framework only, no androidx/appcompat.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.theonionsarewatching.yapchik"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Intentionally empty - Yapchik depends only on the Android framework.
}
