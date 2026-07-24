import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt)
}

// GraphHopper's MMapDataAccess uses JDK-13 absolute-bulk ByteBuffer methods (ART API 34+ only):
// an artifact transform rewrites those call sites to app.vela.core.util.ByteBufferCompat so
// offline region graphs load on the API 26-33 keypad phones this fork targets. See
// buildSrc/src/main/kotlin/GraphHopperByteBufferPatch.kt. Upstream has no equivalent - their
// test hardware is all API 34+, where the real methods exist and the patched calls behave
// identically.
val bbPatched = Attribute.of("graphhopperByteBufferPatched", Boolean::class.javaObjectType)
dependencies {
    attributesSchema { attribute(bbPatched) }
    artifactTypes.getByName("jar") { attributes.attribute(bbPatched, false) }
    registerTransform(GraphHopperByteBufferPatch::class.java) {
        from.attribute(bbPatched, false)
            .attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, org.gradle.api.artifacts.type.ArtifactTypeDefinition.JAR_TYPE)
        to.attribute(bbPatched, true)
            .attribute(org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, org.gradle.api.artifacts.type.ArtifactTypeDefinition.JAR_TYPE)
    }
}
configurations.configureEach {
    if (isCanBeResolved) attributes.attribute(bbPatched, true)
}

android {
    namespace = "app.vela"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vela"
        minSdk = 26
        targetSdk = 35
        // Overridable from CI: -PappVersionCode / -PappVersionName (ci.yml derives
        // them from the run number → 0.3.<run> / 2000+run). Defaults are local/dev only.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "0.0.1"

        // Restricted build flavor flag (see productFlavors below). false everywhere except
        // the `restricted` flavor, which overrides it to true.
        // Self-hosted map-font glyphs (Roboto composited over Noto; ui/map/MapFonts). Defaults to
        // UPSTREAM's GitHub Pages glyph host for now (the probe guard degrades safely to Noto if
        // it is unreachable); mirroring the map-fonts release onto this repo's Pages is on ROADMAP.
        // Same local-test override pattern as the manifests: -PmapFontsUrl=http://127.0.0.1:8099.
        buildConfigField(
            "String",
            "MAP_FONTS_URL",
            "\"${(project.findProperty("mapFontsUrl") as String?) ?: "https://pimpinpumpkin.github.io/Vela/fonts"}\"",
        )
        buildConfigField("boolean", "RESTRICTED", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Ship ARM only. Vela runs on phones, and every phone we support is arm64 or (on the cheap
        // feature phones this fork exists for) 32-bit ARM - so x86/x86_64 was 22 MB of MapLibre
        // carried for nothing. The sherpa-onnx packaging{} block below drops its OWN x86 copies;
        // this catches everything else. x86/x86_64 are NOT built back in anywhere, debug included -
        // no phone uses them. The cost is that the local suites can no longer run on a standard
        // x86_64 AVD (they installed and then died at map init with no MapLibre .so); they need a
        // real device or an arm64 system image, and tests/dpad + tests/small_screen say so. CI is
        // unaffected - it is host-side only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        // libvelamem: the mallopt() purge shim (app/src/main/cpp). Built only for the two ABIs the
        // ndk filter above ships.
        externalNativeBuild {
            cmake { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }

        // MapTiler key injected from the CI secret (-PmaptilerKey); empty for
        // local builds, in which case the app falls back to the keyless
        // OpenFreeMap basemap. Never stored in the repo.
        buildConfigField(
            "String",
            "MAPTILER_KEY",
            "\"${(project.findProperty("maptilerKey") as String?) ?: ""}\"",
        )

        // Offline-routing region manifest (lists the prebuilt per-region CH graphs to download).
        // Default = the latest GitHub release's asset; override for local testing with
        // -ProutingManifestUrl=http://127.0.0.1:8099/manifest.json (served via `adb reverse`).
        buildConfigField(
            "String",
            "ROUTING_MANIFEST_URL",
            "\"${(project.findProperty("routingManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/routing-graphs/routing-manifest-v2.json"}\"",
        )
        // Open building-footprint overlay (Microsoft, ODbL) PMTiles catalog - same override pattern
        // (-PoverlayManifestUrl=http://127.0.0.1:8099/... for local testing via `adb reverse`).
        buildConfigField(
            "String",
            "OVERLAY_MANIFEST_URL",
            "\"${(project.findProperty("overlayManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/building-overlays/building-overlay-manifest.json"}\"",
        )
        // Posted speed-limit overlay (OSM maxspeed, ODbL) PMTiles catalog - the "Speed B" online source that
        // shows a limit WITHOUT the offline routing graph. Same override pattern (-PmaxspeedManifestUrl=…).
        buildConfigField(
            "String",
            "MAXSPEED_MANIFEST_URL",
            "\"${(project.findProperty("maxspeedManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/maxspeed-overlays/maxspeed-overlay-manifest.json"}\"",
        )
        // Open house-number (address-point) overlay (OpenAddresses) PMTiles catalog - same override pattern
        // (-PaddressManifestUrl=…). Rendered as a SymbolLayer of house numbers where OSM lacks addr:housenumber.
        buildConfigField(
            "String",
            "ADDRESS_MANIFEST_URL",
            "\"${(project.findProperty("addressManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/address-overlays/address-overlay-manifest.json"}\"",
        )
        // Offline PLACE packs (whole-region POI/address SQLite, pulled with a routing-region download so a
        // state is searchable offline) - same override pattern (-PpoiPackManifestUrl=… via `adb reverse`).
        buildConfigField(
            "String",
            "POI_PACK_MANIFEST_URL",
            "\"${(project.findProperty("poiPackManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/poi-packs/poi-pack-manifest.json"}\"",
        )
        // ALPR/Flock surveillance-camera dataset (DeFlock/OSM). A bundled floor ships in assets/, and the
        // app refreshes from this hosted manifest so camera data updates WITHOUT an app release (weekly CI
        // cron re-bakes + re-hosts). Same override pattern (-PflockManifestUrl=... via `adb reverse`).
        buildConfigField(
            "String",
            "FLOCK_MANIFEST_URL",
            "\"${(project.findProperty("flockManifestUrl") as String?)
                ?: "https://github.com/PimpinPumpkin/Vela/releases/download/flock-cameras/flock-manifest.json"}\"",
        )
    }

    // Real release signing comes from CI env vars; local dev falls back to the
    // debug keystore so `adb install` still works.
    //   VELA_KEYSTORE_PATH / VELA_KEYSTORE_PASSWORD / VELA_KEY_ALIAS (=vela)
    signingConfigs {
        create("releaseFromEnv") {
            val path = System.getenv("VELA_KEYSTORE_PATH")
            if (!path.isNullOrBlank() && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("VELA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VELA_KEY_ALIAS") ?: "vela"
                keyPassword = System.getenv("VELA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // R8 is what keeps map scroll/nav smooth - MapLibre draws natively (unaffected by
            // `debuggable`), so the jank is the Compose/Kotlin overlay, which R8 optimizes. Keeping
            // `debuggable=true` (AGP default) means breakpoints + Timber/StrictMode still work; only
            // the build gets slower (R8 runs each time). `proguard-rules.pro` is MANDATORY here or
            // sherpa-onnx's FindClass-by-name JNI SIGABRTs; `proguard-rules-debug.pro` keeps stacks
            // readable (-dontobfuscate). Use the `staging` variant below for true non-debuggable perf.
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-rules-debug.pro",
            )
            // Distinct applicationId (app.vela.debug) so the debuggable diagnosis build installs
            // SIDE BY SIDE with a normal release install instead of replacing it. The FileProvider
            // authority is ${applicationId}.fileprovider (manifest) / packageName + ".fileprovider"
            // (code), so it follows the suffix automatically; nothing hardcodes app.vela.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Always ship release: R8 here is what keeps map scroll/nav smooth
            // (debug builds visibly lag).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val envSigning = signingConfigs.getByName("releaseFromEnv")
            signingConfig = if (envSigning.storeFile?.exists() == true) {
                envSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // Non-debuggable, release-optimized variant for measuring the TRUE production frame profile
        // (Perfetto / `dumpsys gfxinfo framestats`) without the small `debuggable` ART deopt. Not
        // attachable to a debugger - use `debug` for stepping, `staging` for "is it actually smooth".
        create("staging") {
            initWith(getByName("release"))
            isDebuggable = false
            applicationIdSuffix = ".staging"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Two POLICY flavors. `standard` is the app as-is. `restricted` hard-locks the six
    // self-restriction toggles at their restrictive values and REMOVES their rows from Settings
    // (see ui/Restricted.kt + the holders): no reviews, no "Read all reviews" page, no photos,
    // adult categories hidden, external links hidden, no voice-search mic. Its own applicationId
    // (app.vela.restricted) so it installs side by side and the OS installer can never cross-grade
    // a restricted install onto a standard APK (different package + the updater picks the matching
    // release asset). CI ships BOTH release APKs; only standard gets a debug compile.
    flavorDimensions += "policy"
    productFlavors {
        create("standard") {
            dimension = "policy"
            isDefault = true
        }
        create("restricted") {
            dimension = "policy"
            applicationIdSuffix = ".restricted"
            versionNameSuffix = "-restricted"
            buildConfigField("boolean", "RESTRICTED", "true")
        }
    }

    // The app's only native code: the mallopt() purge shim. Pinned NDK/CMake versions so a
    // developer with a different NDK installed gets the same libvelamem.so as CI.
    ndkVersion = "27.0.12077973"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // Dead-RESOURCE half of the dead-code gate (the code halves are detekt + audit_deadcode.sh).
        // checkOnly restricts lint to JUST unused resources, so its unrelated pre-existing findings
        // (NewApi, MissingPermission, GradleDependency, ...) don't run and can't fail this gate;
        // UnusedResources is promoted to fatal so a dead drawable/string/layout fails CI. Accurate
        // here because the app does no dynamic getIdentifier() resource lookup.
        checkOnly += "UnusedResources"
        fatal += "UnusedResources"
        abortOnError = true
        warningsAsErrors = false
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // The neural-TTS/ASR runtime (ONNX Runtime + sherpa-onnx, from the vendored AAR) ships its
        // .so for all 4 ABIs. Drop x86/x86_64 (~30 MB compressed, no phone we support), but KEEP
        // armeabi-v7a: this line arrived from upstream reading "Vela targets arm64 phones", which is
        // true for upstream and FALSE for this fork - feature phones are the whole reason it exists,
        // and the cheap ones ship 32-bit userspace. With v7a stripped, every sherpa-onnx feature
        // (Vela voice TTS *and* on-device voice search) dies on those phones with an
        // UnsatisfiedLinkError that WhisperRecognizer swallowed into "the voice model isn't ready,
        // re-download it" - advice that could never work, on the exact hardware we target. Costs
        // ~12 MB compressed. MapLibre and other libs stay multi-ABI (untouched).
        jniLibs {
            excludes += listOf(
                "**/x86/libonnxruntime.so", "**/x86/libsherpa-onnx*.so",
                "**/x86_64/libonnxruntime.so", "**/x86_64/libsherpa-onnx*.so",
            )
        }
    }
}

dependencies {
    // ---- Self-coverage suite (in-process instrumented tour; tests/devices/self_coverage.sh) ----
    // Drives the real app in-process: Compose semantics for assertions, UiDevice.pressKeyCode for
    // REAL system-dispatcher D-pad input, androidx.test Screenshot for real-framebuffer stills
    // (includes the MapLibre GL surface). Faster AND stricter than the external uiautomator tour.
    // Local JVM unit tests for pure app-module logic (no device). The onboarding download-plan
    // decision lives here because its disk-full branch cannot be reproduced safely on a real phone.
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

    implementation(project(":core"))
    implementation(project(":yapchik")) // vendored softkey engine (LGPL-3.0) - keypad/D-pad softkeys

    // sherpa-onnx: in-process neural TTS + ASR runtime. Vendored AAR (no official Maven artifact;
    // the JitPack coordinate doesn't resolve). Lives in :app because a library module can't consume
    // a local .aar. 1.13.4 is a LOAD-BEARING upgrade, not routine: its bundled onnxruntime (1.27.0,
    // up from 1.24.3) fixes the armv7 unaligned-read SIGBUS that crashed every model LOAD on 32-bit
    // ARM phones (issue #95; device-verified both broken-before and fixed-after on an M5 forced to
    // `--abi armeabi-v7a`). Do not downgrade past it while the fork ships v7a.
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    // Extracts the Kokoro model's .tar.bz2 at download time (Android has no built-in bzip2/tar).
    implementation("org.apache.commons:commons-compress:1.27.1")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.coil.compose)
    implementation(libs.timber)

    // MapLibre Native - the renderer. Only the app module touches it; :core
    // stays UI-agnostic.
    implementation(libs.maplibre.android)
    implementation(libs.androidx.car.app) // Android Auto (projection): templates + car surface
    // The PROJECTED HOST CONNECTOR - without it Android Auto never lists the app: the host
    // discovers a car app through the CarAppMetadataHolderService this artifact merges into the
    // manifest, not through our own service declaration alone (official car-app docs: app-projected
    // is the Android Auto artifact). The first-cut port shipped only the base :app template
    // library, which is why Vela has never appeared in an AA launcher (device report, OnePlus 12).
    implementation(libs.androidx.car.app.projected)
    testImplementation(libs.androidx.car.app.testing) // car-template unit tests

    debugImplementation(libs.androidx.compose.ui.tooling)
}
