package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.CartridgeEdit
import com.bmscompanion.app.data.mission.CartridgeState
import com.bmscompanion.app.data.wdp.DtcEdits
import com.bmscompanion.app.ui.screens.wdp.DtcSource
import java.io.File

/**
 * The DTC page's cartridge for the headless checks (`--wdpclicktest`, `--wdprender`): a copy of a real cartridge,
 * read once and then held **in memory** — a save applies its edits to the text held here, as the PC writes them
 * straight into the file. Nothing is ever written to disk, so the checks can press Save as often as they like.
 */
internal object WdpDtcFixture {
    /** The cartridge the checks were given; null leaves the page without one, as on a PC with no pilot. */
    @Volatile var file: File? = null

    /**
     * Where the Load buttons' "BMS default" is read from: a folder holding copies of BMS's `*_Def.ini`, read only.
     * Null (the default) answers as a PC with no BMS install does.
     */
    @Volatile var defaultsDir: File? = null

    fun source(): DtcSource = Source(file?.takeIf { it.isFile }?.readText(Charsets.ISO_8859_1))

    private class Source(private var text: String?) : DtcSource {
        private fun state(message: String? = null) = CartridgeState(
            available = text != null, callsign = "Fixture", file = "Fixture.ini", text = text, modified = 1,
            path = "C:\\Falcon BMS\\User\\Config\\Fixture.ini", message = message,
            error = if (text == null) "No cartridge was given to the check." else null,
        )

        override suspend fun load(callsign: String?) = state()
        override suspend fun bmsDefaults() = BmsDefaultsFiles.read(defaultsDir)
        // read on the spot, so the page is whole the moment it is made and a press is measured against a still page
        override suspend fun harmCodes() = dtcAppHarmCodes()
        override suspend fun save(callsign: String?, edits: List<CartridgeEdit>): CartridgeState {
            if (text == null) return state()
            text = text?.let { DtcEdits.apply(it, edits) }
            return state("Fixture.ini saved (${edits.size} keys).")
        }
    }
}
