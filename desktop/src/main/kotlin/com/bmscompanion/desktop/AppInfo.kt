package com.bmscompanion.desktop

/** Version of BMS Companion for Windows (written into the resources by desktop/build.gradle.kts from pcVersion). */
object AppInfo {
    val version: String by lazy {
        AppInfo::class.java.getResourceAsStream("/app-version.txt")?.use { it.readBytes().toString(Charsets.UTF_8).trim() } ?: "dev"
    }
}
