package com.bmscompanion.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.bmscompanion.app.data.Repo
import com.bmscompanion.app.data.mission.BmsInstallList
import com.bmscompanion.app.data.mission.PcAnswer
import com.bmscompanion.app.ui.screens.mission.BmsInstallsCard
import com.bmscompanion.app.ui.screens.mission.BmsInstallsSource
import com.bmscompanion.desktop.bridge.ApiRequest
import com.bmscompanion.desktop.bridge.Bridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The list of Falcon BMS installs on the PC's own server page (1.4.1): the same card every Setup page shows
 * ([BmsInstallsCard]), answered by this program's [Bridge] in-process rather than over the link (the server page draws
 * no Mission section), and a folder chosen with the PC's own folder picker ([pickFolder]).
 */
object PcInstalls : BmsInstallsSource {
    private fun call(method: String, path: String, args: Map<String, String> = emptyMap()): PcAnswer<BmsInstallList> = try {
        val r = Bridge.handle(ApiRequest(method, path, args))
        PcAnswer.read(r.status, r.body.toString(Charsets.UTF_8)) { Repo.json.decodeFromString(BmsInstallList.serializer(), it) }
    } catch (e: Throwable) {
        PcAnswer(error = "BMS Companion could not do that: ${e.message ?: e.javaClass.simpleName}")
    }

    override suspend fun list(refresh: Boolean) = withContext(Dispatchers.IO) {
        call("GET", "/api/bms/installs", if (refresh) mapOf("refresh" to "1") else emptyMap())
    }

    override suspend fun change(op: String, args: Map<String, String>) = withContext(Dispatchers.IO) { call("POST", "/api/bms/installs/$op", args) }

    override suspend fun pickFolder(start: String?): String? = pickFolder("Add a Falcon BMS folder", start)
}

/** The card on the server page: live while this PC reads Falcon BMS. */
@Composable
fun PcInstallsCard() {
    // the ticks recompose it, so a switch made on another device reads the list again (refreshKey)
    val ticks = Bridge.ticks.collectAsState().value
    @Suppress("UNUSED_EXPRESSION") ticks
    BmsInstallsCard(source = PcInstalls, linkedOverride = Bridge.running, refreshKey = Bridge.install.baseDir)
}
