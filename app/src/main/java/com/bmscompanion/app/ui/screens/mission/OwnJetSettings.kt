package com.bmscompanion.app.ui.screens.mission

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bmscompanion.app.ui.components.HudChip
import com.bmscompanion.app.ui.components.OwnJetLook
import com.bmscompanion.app.ui.components.drawOwnJet
import com.bmscompanion.app.ui.theme.Hud
import kotlin.math.roundToInt

/**
 * The device's own jet ([OwnJetLook]): a preview on the dark map ground, the size slider (a small arrow to a large
 * jet), the shape and the colour. Shown from the Mission map's **Jet ›** chip and in the kneeboard's ⋯ menu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OwnJetSettings(modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // the jet as the map draws it, heading north-east
            Canvas(
                Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF26323A))
                    .border(1.dp, Hud.Outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
            ) {
                // read so the preview draws again when one changes
                OwnJetLook.lenDp; OwnJetLook.shape; OwnJetLook.color
                drawOwnJet(Offset(size.width * 0.45f, size.height * 0.55f), 45.0, OwnJetLook.lengthPx(this) * 1.2f, light = false)
            }
            Column(Modifier.weight(1f)) {
                Text("Your jet  ·  ${OwnJetLook.lenDp} dp", color = Hud.Text, fontSize = 13.sp)
                Slider(
                    value = OwnJetLook.lenDp.toFloat(),
                    onValueChange = { OwnJetLook.chooseLen(it.roundToInt()) },
                    valueRange = OwnJetLook.MIN_DP.toFloat()..OwnJetLook.MAX_DP.toFloat(),
                    modifier = Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = "Jet size" },
                    colors = SliderDefaults.colors(
                        thumbColor = Hud.Amber, activeTrackColor = Hud.Amber.copy(alpha = 0.65f),
                        inactiveTrackColor = Hud.Outline.copy(alpha = 0.7f),
                    ),
                )
                Text("Small arrow … large jet", color = Hud.TextFaint, fontSize = 10.sp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OwnJetLook.shapes.forEachIndexed { i, n -> HudChip(n, OwnJetLook.shape == i) { OwnJetLook.chooseShape(i) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OwnJetLook.colors.forEachIndexed { i, c ->
                val on = OwnJetLook.color == i
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF0B0E10))
                        .border(if (on) 2.dp else 1.dp, if (on) Hud.Amber else Hud.Outline, CircleShape)
                        .clickable { OwnJetLook.chooseColor(i) }.padding(5.dp).clip(CircleShape).background(c)
                        .semantics { contentDescription = OwnJetLook.colorNames[i] },
                )
            }
        }
    }
}

/** The Mission map's chip for [OwnJetSettings]: **Jet ›** opens them under it. */
@Composable
fun OwnJetChip() {
    var open by remember { mutableStateOf(false) }
    Box {
        HudChip("Jet ${OwnJetLook.labels[OwnJetLook.preset]} ›", open) { open = !open }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.background(Hud.Surface)) {
            OwnJetSettings(Modifier.padding(horizontal = 14.dp, vertical = 6.dp).width(300.dp))
        }
    }
}

/** The map's bearings, magnetic (as the F-16 shows them) or true ([BearingPrefs]): one chip, a press switches. */
@Composable
fun BearingChip() {
    HudChip(if (BearingPrefs.magnetic) "BRG MAG" else "BRG TRUE", false) { BearingPrefs.set(!BearingPrefs.magnetic) }
}

/** The same choice as two chips, for a menu (the kneeboard's ⋯). */
@Composable
fun BearingChoice(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Bearings", color = Hud.TextDim, fontSize = 13.sp)
        HudChip("Magnetic", BearingPrefs.magnetic) { BearingPrefs.set(true) }
        HudChip("True", !BearingPrefs.magnetic) { BearingPrefs.set(false) }
    }
}
