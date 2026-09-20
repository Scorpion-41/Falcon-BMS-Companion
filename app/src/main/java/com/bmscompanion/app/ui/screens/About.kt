package com.bmscompanion.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.bmscompanion.app.AppVersion
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.update.Progress
import com.bmscompanion.app.data.update.formatBytes
import com.bmscompanion.app.data.update.Updates
import com.bmscompanion.app.ui.components.BmsTopBar
import com.bmscompanion.app.ui.components.ContentColumn
import com.bmscompanion.app.ui.components.SectionCard
import com.bmscompanion.app.ui.components.TagFlow
import com.bmscompanion.app.ui.screens.mission.SmallButton
import com.bmscompanion.app.ui.theme.Hud
import com.bmscompanion.app.ui.theme.LocalExtra
import kotlinx.coroutines.launch

/**
 * What this is, who wrote it, which version is running and where to get the next one. The version comes from
 * [AppVersion], which Gradle also reads for the APK and the installer, so all three builds agree.
 */
@Composable
fun AboutScreen(nav: NavHostController) {
    val open = Platform.openUrl
    Column(Modifier.fillMaxSize()) {
        BmsTopBar("About", "BMS Companion ${AppVersion.NAME}", onBack = { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ContentColumn(maxWidth = 760.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppMark(Modifier.size(72.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("BMS Companion", style = MaterialTheme.typography.headlineSmall, color = Hud.Text)
                        Text(
                            "Version ${AppVersion.NAME} · data from Falcon BMS ${AppVersion.BMS}",
                            style = LocalExtra.current.monoSmall, color = Hud.TextDim,
                        )
                        Text("by ${AppVersion.AUTHOR}", style = MaterialTheme.typography.bodyMedium, color = Hud.Green)
                    }
                }
                Text(
                    "Flight reference and live mission data for Falcon BMS, on your tablet, PC and browser.",
                    style = MaterialTheme.typography.bodyLarge, color = Hud.Text,
                )
                UpdateCard(open)
                CreditsCard(open)
                SectionCard("Source", accent = Hud.Cyan) {
                    LinkRow("Source code and issues", AppVersion.REPO, Icons.Default.Code, Hud.Cyan, open)
                }
                Text(
                    "Not affiliated with Benchmark Sims or the Falcon BMS team.",
                    style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(vertical = 18.dp),
                )
            }
        }
    }
}

/**
 * Updating from inside the app.
 *
 * The card asks GitHub what has been released, and says one of three things: you are up to date, something is
 * available, or GitHub could not be reached. When there is something available it shows the notes for *every*
 * version in between, not only the newest — a pilot two releases behind should see what they skipped before
 * deciding. The download is verified against the checksum the release publishes before anything is run.
 */
@Composable
private fun UpdateCard(open: ((String) -> Unit)?) {
    val state by Updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Updates.check() }

    val install = Updates.installer
    val progress = state.progress
    val accent = when {
        state.available -> Hud.Green
        state.error != null -> Hud.Red
        else -> Hud.Amber
    }
    SectionCard("Updates", accent = accent) {
        val headline = when {
            state.checking -> "Checking GitHub for a newer version…"
            state.error != null -> "Could not check for updates: ${state.error}"
            state.available -> "Version ${state.latest?.version} is available."
            state.checkedAt > 0L -> "BMS Companion ${AppVersion.NAME} is the latest version."
            else -> "The releases page lists every version with its notes."
        }
        Text(headline, style = MaterialTheme.typography.bodyMedium, color = if (state.error != null) Hud.Red else Hud.Text)
        val latest = state.latest
        if (latest != null) {
            val behind = state.newer.size
            Text(
                buildString {
                    append("You are on ${AppVersion.NAME}")
                    if (behind > 1) append(", $behind releases behind")
                    val bytes = Updates.assetFor(latest)?.size ?: 0
                    if (bytes > 0 && install?.canInstall == true) append(" · download ${formatBytes(bytes)}")
                    append(latest.date.takeIf { it.isNotBlank() }?.let { " · released $it" }.orEmpty())
                },
                style = LocalExtra.current.monoSmall, color = Hud.TextDim, modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            UpdateProgress(progress)
        }

        Spacer(Modifier.height(12.dp))
        TagFlow {
            val busy = progress != null && progress.stage in setOf(Progress.Stage.DOWNLOADING, Progress.Stage.VERIFYING, Progress.Stage.INSTALLING)
            if (latest != null && install?.canInstall == true && !busy) {
                val again = progress?.stage == Progress.Stage.FAILED
                val mb = Updates.assetFor(latest)?.size ?: 0
                val label = when {
                    again -> "Try again"
                    mb > 0 -> "Download and install (${formatBytes(mb)})"
                    else -> "Download and install"
                }
                SmallButton(label, Icons.Default.SystemUpdate, primary = true) {
                    scope.launch { Updates.update(latest) }
                }
            }
            if (latest != null) {
                SmallButton(if (notes) "Hide what's new" else "What's new", Icons.AutoMirrored.Filled.List, primary = false) { notes = !notes }
            }
            if (!busy) {
                SmallButton(if (state.checking) "Checking…" else "Check again", Icons.Default.Refresh, primary = false) {
                    scope.launch { Updates.check(force = true) }
                }
            }
            if (open != null) {
                SmallButton("Releases page", Icons.AutoMirrored.Filled.OpenInNew, primary = false) { open(AppVersion.RELEASES) }
            }
            if (progress != null && !busy) {
                SmallButton("Dismiss", null, primary = false) { Updates.dismissProgress() }
            }
        }

        if (install?.canInstall != true && latest != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "This is the browser version — it cannot install anything. Update the Windows program or the " +
                    "Android app from the releases page, and this page comes with it.",
                style = MaterialTheme.typography.bodySmall, color = Hud.TextDim,
            )
        }

        if (notes && state.newer.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            state.newer.forEach { release ->
                Text(
                    "${release.title}${release.date.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty()}",
                    style = MaterialTheme.typography.titleSmall, color = Hud.Amber, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                )
                ReleaseNotes(release.body)
            }
        }
    }
}

/** A bar and a line of words: what is happening, and how far through it is. */
@Composable
private fun UpdateProgress(p: Progress) {
    val colour = if (p.stage == Progress.Stage.FAILED) Hud.Red else if (p.stage == Progress.Stage.READY) Hud.Green else Hud.Cyan
    Column {
        val f = p.fraction
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Hud.Surface2)) {
            if (f != null) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(6.dp).background(colour))
            } else if (p.stage != Progress.Stage.FAILED && p.stage != Progress.Stage.READY) {
                // nothing to measure yet: a faint full bar reads as "working" without pretending to know how far
                Box(Modifier.fillMaxWidth().height(6.dp).background(colour.copy(alpha = 0.25f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.text, Modifier.weight(1f), style = LocalExtra.current.monoSmall, color = colour)
            // the percentage on its own, kept out of the sentence so the eye can find it
            if (f != null) Text("${(f * 100).toInt()}%", style = LocalExtra.current.monoSmall, color = Hud.Text, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Release notes as GitHub holds them: Markdown. Rendering all of it would be a project of its own, so this keeps
 * the three things release notes actually use — headings, bullets and emphasis — and drops the punctuation.
 */
@Composable
private fun ReleaseNotes(body: String?) {
    val text = body?.trim().orEmpty()
    if (text.isBlank()) {
        Text("No notes were published for this release.", style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint)
        return
    }
    Column {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            val plain = line.trim().replace("**", "").replace("__", "").replace("`", "")
            when {
                plain.isBlank() -> Spacer(Modifier.height(6.dp))
                line.trimStart().startsWith("#") -> Text(
                    plain.trimStart('#', ' '),
                    style = MaterialTheme.typography.titleSmall, color = Hud.Text, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text("·", style = MaterialTheme.typography.bodySmall, color = Hud.Green, modifier = Modifier.width(14.dp))
                    Text(plain.trimStart('-', '*', ' '), style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
                }
                else -> Text(plain, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

/** Who made the things this app leans on. None of them are part of it, and all of them are worth naming. */
@Composable
private fun CreditsCard(open: ((String) -> Unit)?) {
    SectionCard("Credits and acknowledgements", accent = Hud.Amber) {
        Credit(
            "Benchmark Sims and the Falcon BMS team",
            "Falcon BMS itself, and the manuals and data files this app's reference sections were built from. " +
                "Live mission data is read through the shared-memory interface BMS provides.",
            "https://www.falcon-bms.com/", open,
        )
        Credit(
            "EZBoards, by \"Logic\"",
            "The kneeboard generator that ships with BMS. BMS Companion only launches it; the boards and the " +
                "briefing tables are its own work.",
            "https://www.falcon-bms.com/", open,
        )
        Credit(
            "OpenKneeboard — Fred Emmott",
            "The in-game kneeboard the VR boards are built for, through its Web Dashboard tabs.",
            "https://openkneeboard.com/", open,
        )
        Credit(
            "Tacview — Raia Software",
            "The real-time telemetry stream the AWACS picture is built from.",
            "https://www.tacview.net/", open,
        )
        Text(
            "The reference pages come from Falcon BMS and from published AIP documents.",
            style = MaterialTheme.typography.bodySmall, color = Hud.TextFaint, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun Credit(who: String, what: String, url: String, open: ((String) -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .let { if (open == null) it else it.clickable { open(url) } },
    ) {
        Text(who, style = MaterialTheme.typography.titleSmall, color = Hud.Text, fontWeight = FontWeight.SemiBold)
        Text(what, style = MaterialTheme.typography.bodySmall, color = Hud.TextDim)
        Text(url.removePrefix("https://").trimEnd('/'), style = LocalExtra.current.monoSmall, color = Hud.Cyan)
    }
}

/** One tappable link. Without a platform opener (there always is one) the address is still readable. */
@Composable
private fun LinkRow(label: String, url: String, icon: ImageVector, accent: Color, open: ((String) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Hud.Surface2)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .let { if (open == null) it else it.clickable { open(url) } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Hud.Text, fontWeight = FontWeight.SemiBold)
            Text(url.removePrefix("https://"), style = LocalExtra.current.monoSmall, color = Hud.TextDim)
        }
        if (open != null) Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Hud.TextFaint, modifier = Modifier.size(18.dp))
    }
}

/** The app's mark, drawn rather than bundled: the same HUD reticle and delta as the launcher icon. */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        val u = r / 54f // the icon is drawn on a 108-unit square
        drawCircle(Color(0xFF0B1218), r, c)
        drawCircle(Hud.Green, 30 * u, c, style = Stroke(2.2f * u))
        drawCircle(Hud.Green, 10 * u, c, style = Stroke(1.6f * u))
        listOf(
            Offset(0f, -36f) to Offset(0f, -28f), Offset(0f, 28f) to Offset(0f, 36f),
            Offset(-36f, 0f) to Offset(-28f, 0f), Offset(28f, 0f) to Offset(36f, 0f),
        ).forEach { (a, b) -> drawLine(Hud.Green, c + a * u, c + b * u, 2.2f * u) }
        drawPath(delta(c, u), Hud.Amber)
    }
}

/** The F-16 delta silhouette of the icon, in icon units around [c]. */
private fun DrawScope.delta(c: Offset, u: Float): Path {
    val pts = listOf(
        0f to -24f, 3f to -10f, 3f to -2f, 18f to 8f, 18f to 12f, 3f to 8f, 2.5f to 16f,
        8f to 21f, 8f to 24f, 0f to 22f, -8f to 24f, -8f to 21f, -2.5f to 16f, -3f to 8f,
        -18f to 12f, -18f to 8f, -3f to -2f, -3f to -10f,
    )
    return Path().apply {
        pts.forEachIndexed { i, (x, y) ->
            val p = c + Offset(x, y) * u
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
}
