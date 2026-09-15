import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Browser version: the Android app's own screens compiled to WebAssembly (Compose for Web). It runs on the phone, tablet
// or computer that opens it; the PC program only serves the files, the bundled BMS data and the bridge API.
// Like desktop/, it compiles app/src/main/java and replaces the few platform files (src/wasmJsMain/kotlin/overrides).
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "bmsc"
        browser {
            commonWebpackConfig { outputFileName = "bmsc.js" }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            kotlin.srcDir("../app/src/main/java")
            // the PC's map and chart viewers (mouse-wheel zoom) are plain Compose and work in browsers too
            kotlin.srcDir("../desktop/src/main/kotlin/overrides/ui")
            kotlin.exclude(
                "com/bmscompanion/app/MainActivity.kt",
                "com/bmscompanion/app/data/Repo.kt",
                "com/bmscompanion/app/data/mission/MissionLink.kt",
                "com/bmscompanion/app/ui/components/TheaterMap.kt",
                "com/bmscompanion/app/ui/screens/Charts.kt",
                "com/bmscompanion/app/ui/screens/mission/MissionScreen.kt",
                "com/bmscompanion/app/ui/screens/mission/MissionSetup.kt",
                // PC-only versions (window pin, bridge settings)
                "screens/mission/**",
            )
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}

// Node.js, Yarn and Binaryen come from the repositories declared in settings.gradle.kts (project repositories are not allowed).
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().downloadBaseUrl = null
}
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().downloadBaseUrl = null
}
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.binaryen.BinaryenRootPlugin> {
    rootProject.extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.binaryen.BinaryenRootExtension>().downloadBaseUrl = null
}
