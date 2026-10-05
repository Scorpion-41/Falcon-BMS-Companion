package com.bmscompanion.app.data.wdp

import kotlinx.serialization.Serializable

/**
 * A Weapon Delivery Planner page, as a tree of controls.
 *
 * **The layout is Falcas's.** These files are read out of WDP's own designer code by
 * `tools/extractor/src/wdplayout.mjs` — every control's kind, rectangle, text, font and colour, and which panel it
 * sits in. Falcas's layout; see `docs/WDP-PORT.md`.
 *
 * Doing it this way rather than by hand is the difference between a port that looks like the program and one that
 * nearly does. `cntDTC` alone has 1,878 controls: placed by hand, a hundred of them would end up a few pixels out
 * and a dozen would be forgotten, and every one of those is a pilot looking for a field that is not where it
 * should be. Read out of the designer, the page is in the right place by construction — and the whole user
 * interface of every page in the program is 684 KB of data.
 *
 * What is *not* here is behaviour: what a button does, what a field means, what gets recomputed when it changes.
 * That is ported per page in Kotlin, against the real program's own answers (`--wdpporttest`). Layout is data;
 * planning is code.
 */
@Serializable
data class WdpForm(
    val form: String = "",
    val roots: List<WdpControl> = emptyList(),
    /** A dialog's window size (`base.ClientSize`); pages are sized by their controls instead. 0 when not given. */
    val clientW: Int = 0,
    val clientH: Int = 0,
    /**
     * The form's own BackColor, ForeColor and BackgroundImage. Most of WDP's windows are charcoal (`#404040`) with
     * white ink set once on the form, and the Mil Codes window is nothing but its picture; null where the designer
     * leaves the form at the Windows default.
     */
    val bg: String? = null,
    val fg: String? = null,
    val image: String? = null,
) {
    /** Every control in the tree, parents before children. */
    fun all(): List<WdpControl> = buildList { fun walk(cs: List<WdpControl>) { for (c in cs) { add(c); walk(c.children) } }; walk(roots) }

    fun find(name: String): WdpControl? = all().firstOrNull { it.name == name }

    /**
     * The page's own size. A page (a UserControl) is taken from the controls it holds — the designer records no size
     * for it we can trust. A dialog has its window's client size, which is what Windows shows whatever the controls
     * are, and what keeps an OK button its margin from the edge.
     */
    val width: Int get() = if (clientW > 0) clientW else roots.maxOfOrNull { it.x + it.w } ?: 0
    val height: Int get() = if (clientH > 0) clientH else roots.maxOfOrNull { it.y + it.h } ?: 0
}

/**
 * One control: where it is, what it is, and what it says.
 *
 * The rectangle is in the designer's own pixels and relative to the control's **parent**, which is how Windows
 * Forms works and how the renderer consumes it. Nothing here is resolved or scaled at extraction time, so a page
 * can be drawn at any size the device has.
 */
@Serializable
data class WdpControl(
    val name: String = "",
    /** label, button, text, combo, check, radio, group, panel, picture, number, list, grid, tabs, tab, slider */
    val kind: String = "label",
    /** the Windows Forms class it came from, kept for the cases where two kinds map to one */
    val winKind: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val w: Int = 0,
    val h: Int = 0,
    val text: String? = null,
    val font: String? = null,
    val fontSize: Double? = null,
    /** Bold, Italic, or the two together, as the designer writes it */
    val fontStyle: String? = null,
    /** MiddleLeft, TopRight … the Windows Forms content alignment */
    val align: String? = null,
    /** a named colour or #rrggbb */
    val fg: String? = null,
    val bg: String? = null,
    val readOnly: Boolean = false,
    val hidden: Boolean = false,
    val multiline: Boolean = false,
    /** a label with AutoSize on: Windows grows it to fit the text the program gives it, rather than clipping it */
    val autoSize: Boolean = false,
    /**
     * A picture the designer gave the control — its BackgroundImage or Image — named as `wdpimages.mjs` writes
     * it (`cntTOSS.pnlRefUp.BackgroundImage`), under `data/wdp/img/`. The DataCard's grid is one of these.
     */
    val image: String? = null,
    /** a checkbox or radio button ticked in the designer — the state before any code has run */
    val checked: Boolean = false,
    /** a combo box's or list's designer items (`Items.AddRange`), before any code adds its own */
    val items: List<String> = emptyList(),
    /** a grid's column headers and widths, in the designer's pixels */
    val columns: List<String> = emptyList(),
    val columnWidths: List<Int> = emptyList(),
    val children: List<WdpControl> = emptyList(),
) {
    val bold: Boolean get() = fontStyle?.contains("Bold") == true
    val italic: Boolean get() = fontStyle?.contains("Italic") == true

    /** Whether this is something a pilot types in or presses, rather than furniture. */
    val interactive: Boolean
        get() = kind == "button" || kind == "combo" || kind == "check" || kind == "radio" ||
            kind == "number" || kind == "slider" || (kind == "text" && !readOnly)
}

/**
 * What a page is currently showing: control name to value.
 *
 * Kept apart from the layout on purpose. The layout never changes, so it is loaded once and shared; the values
 * change constantly and belong to whoever is driving the page.
 */
@Serializable
data class WdpValues(val values: Map<String, String> = emptyMap()) {
    operator fun get(name: String): String? = values[name]
    fun with(name: String, value: String) = WdpValues(values + (name to value))
}
