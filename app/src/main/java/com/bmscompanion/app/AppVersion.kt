package com.bmscompanion.app

/**
 * The one place the version and the project's own details are written.
 *
 * Gradle reads [NAME] and [BMS] straight out of this file for the APK's `versionName` and for the PC installer
 * (`app/build.gradle.kts`, `desktop/build.gradle.kts`), so a release only has to be bumped here — and the About page
 * shows the same numbers on Android, the PC and in the browser.
 */
object AppVersion {
    /** MAJOR.MINOR.PATCH; the Windows installer accepts nothing else. */
    const val NAME = "1.3.5"

    /** The Falcon BMS version this build's bundled data was extracted from. */
    const val BMS = "4.38"

    const val AUTHOR = "LoneWolf-41"
    const val REPO = "https://github.com/Scorpion-41/Falcon-BMS-Companion"
    const val RELEASES = "$REPO/releases"
}
