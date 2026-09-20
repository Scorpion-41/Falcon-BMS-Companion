import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/** PC version (the installer needs MAJOR.MINOR.BUILD), read from the app's AppVersion.kt so there is one source. */
val pcVersion = Regex("""NAME = "([^"]+)"""")
    .find(file("../app/src/main/java/com/bmscompanion/app/AppVersion.kt").readText())?.groupValues?.get(1)
    ?: error("NAME is missing from AppVersion.kt")

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
                "com/bmscompanion/app/AndroidInstaller.kt",
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

    // Rendering the kneeboard PDF that UOAF's html_brief exports, so its pages can be read on a tablet, in a
    // browser and on a VR board. The same library the chart extractor uses, here at runtime.
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

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

// Two things jpackage cannot do are done to the finished MSI by pc/finish-msi.ps1: its dialogs get the artwork in
// desktop/installer (made by pc/make-installer-art.mjs), and the package gets a product code of its own so that
// installing over an existing copy replaces it instead of stopping with "Another version of this product is already
// installed". The script's header has the details.
val finishMsi by tasks.registering {
    val script = rootProject.layout.projectDirectory.file("pc/finish-msi.ps1").asFile
    val art = layout.projectDirectory.dir("installer").asFile
    val msiDir = layout.buildDirectory.dir("compose/binaries/main/msi")
    val name = "BMS Companion-$pcVersion.msi"
    doLast {
        val msi = File(msiDir.get().asFile, name)
        if (!msi.isFile) {
            logger.lifecycle("no $name to finish (nothing was packaged)")
            return@doLast
        }
        val run = ProcessBuilder(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath,
            "-Msi", msi.absolutePath,
            "-Banner", File(art, "banner.bmp").absolutePath,
            "-Dialog", File(art, "dialog.bmp").absolutePath,
        ).redirectErrorStream(true).start()
        val output = run.inputStream.bufferedReader().use { it.readText() }.trim()
        check(run.waitFor() == 0) { "the installer could not be finished:\n$output" }
        output.lines().forEach { logger.lifecycle(it) }
    }
}
tasks.matching { it.name == "packageMsi" }.configureEach { finalizedBy(finishMsi) }

compose.desktop {
    application {
        mainClass = "com.bmscompanion.desktop.MainKt"
        // Keep the memory footprint small: a bounded heap, and G1 set to hand free memory back to Windows.
        // (Map tiles and charts are Skia images in native memory, bounded by the caches in overrides/data/Repo.kt.)
        jvmArgs(
            "-Xmx512m",
            "-XX:MaxMetaspaceSize=192m",
            "-XX:+UseG1GC",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25",
            "-XX:G1PeriodicGCInterval=15000",
            "-XX:+G1PeriodicGCInvokesConcurrent",
        )
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
                // Per machine, into Program Files. A per-user package declares in its summary information
                // that it installs without elevation, and Windows then refuses ALLUSERS=1 outright; per machine is
                // also the context every copy already out there was registered in, so an update replaces it.
                // Double-clicking asks for consent by itself: nobody has to right-click "Run as administrator".
                perUserInstall = false
                upgradeUuid = "d7efb8c6-db9f-4616-a751-7c1559f17056"
            }
        }
    }
}

