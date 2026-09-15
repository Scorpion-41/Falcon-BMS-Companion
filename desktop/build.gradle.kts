import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** PC version (the installer needs MAJOR.MINOR.BUILD). Keep in step with versionName in app/build.gradle.kts. */
val pcVersion = "1.3.0"

kotlin { jvmToolchain(17) }

// The PC version compiles the Android app's code as-is, so both show the same screens and data. It also reads Falcon BMS
// (src/main/kotlin/com/bmscompanion/desktop/bridge) and serves the Android app, browsers and client PCs.
// The few Android-only files are replaced by PC versions in src/main/kotlin/overrides, and
// src/main/kotlin/shims provides the handful of Android APIs the shared screens call (Uri.encode, Bitmap, screen width).
sourceSets {
    main {
        kotlin {
            srcDir("../app/src/main/java")
            exclude(
                "com/bmscompanion/app/MainActivity.kt",
                "com/bmscompanion/app/data/Repo.kt",
                "com/bmscompanion/app/data/mission/MissionLink.kt",
                "com/bmscompanion/app/ui/components/TheaterMap.kt",
                "com/bmscompanion/app/ui/screens/Charts.kt",
                "com/bmscompanion/app/ui/screens/mission/MissionScreen.kt",
                "com/bmscompanion/app/ui/screens/mission/MissionSetup.kt",
            )
        }
        // bundled BMS data (JSON, maps, images, charts) is shared with the Android app
        resources.srcDir("../app/src/main/assets")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("net.java.dev.jna:jna:5.15.0") // dark Windows title bar
    implementation("net.java.dev.jna:jna-platform:5.15.0") // BMS shared memory, registry, Recycle Bin
    implementation("com.google.zxing:core:3.5.3") // QR code for browser access
}

// The browser version (web/, served to browsers as /webapp) and the version number are generated into the PC app's resources.
val webResources = layout.buildDirectory.dir("generated/web-resources")
val versionResources = layout.buildDirectory.dir("generated/version-resources")
val webBundle by tasks.registering(Sync::class) {
    dependsOn(":web:wasmJsBrowserDistribution")
    from(project(":web").layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) { exclude("*.map") }
    into(webResources.map { it.dir("webapp") })
}
val versionResource by tasks.registering {
    val out = versionResources.map { it.file("app-version.txt") }
    inputs.property("version", pcVersion)
    outputs.file(out)
    doLast { out.get().asFile.apply { parentFile.mkdirs(); writeText(pcVersion) } }
}
sourceSets.main {
    resources.srcDir(webResources)
    resources.srcDir(versionResources)
}
tasks.named("processResources") { dependsOn(webBundle, versionResource) }

compose.desktop {
    application {
        mainClass = "com.bmscompanion.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "BMS Companion"
            packageVersion = pcVersion
            description = "Falcon BMS Companion for Windows"
            vendor = "BMS Companion"
            modules("java.naming", "jdk.unsupported", "jdk.httpserver")
            windows {
                iconFile.set(project.file("app.ico"))
                menuGroup = "BMS Companion"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "d7efb8c6-db9f-4616-a751-7c1559f17056"
            }
        }
    }
}

