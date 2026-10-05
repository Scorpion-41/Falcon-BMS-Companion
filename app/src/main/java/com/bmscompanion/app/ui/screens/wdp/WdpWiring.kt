package com.bmscompanion.app.ui.screens.wdp

import com.bmscompanion.app.data.wdp.WdpValues

/**
 * What a wired Weapon Delivery Planner page gives its layout, and takes back from it.
 *
 * The layout (`WdpForm`) is Falcas's and knows nothing about planning; a page's plan (`TossPlan` and its kin)
 * knows nothing about controls. A wiring is the seam: it turns the plan into the page's values — every label the
 * plan fills, every slider's position and range, which of the stacked panels is up — and turns a slider's move or
 * a tap into the plan's own handler, in WDP's units.
 *
 * One interface, because the Planner picks the wiring by page and the renderer talks to it the same way whatever
 * the page is. A page with no wiring shows the designer's page as it is, and says so.
 *
 * What the renderer reads from [values], by control:
 * - any control: its text under its name; `"shown"`/`"hidden"` for visibility; `"<name>.enabled"` = `"false"`;
 *   `"<name>.fore"`/`".back"` (or `".color"`) colours; `"<name>.text"` a caption where the value is a state;
 * - check box / radio: `"checked"`/`"unchecked"`; slider: its value and `".min"`/`".max"`;
 * - picture: a file name under `data/wdp/pictures/`;
 * - combo box / list box: `"<name>.items"`, new-line separated (the designer's items otherwise); a list's
 *   `".selected"` index; a combo the designer hides is drawn only for `"shown"`, so its choice goes in `".text"`
 *   (the DataCard's tanker lists); `"<combo>.drop"`: a token that drops the list open when the combo appears or the
 *   token changes (the tanker lists, which a pilot opens to pick from);
 * - grid: `"<name>.rows"` (rows by new line, cells by tab), `".columns"`/`".widths"` to override the designer's,
 *   `".selected"`;
 * - tab control: the selected TabPage's name under the tab control's name;
 * - `"<name>.front"` = `"true"`: drawn over its siblings (WDP's BringToFront); `"<name>.focus"` = `"true"`: a box
 *   that takes the keyboard when it appears (WDP's Focus()); `"<combo>.editable"` = `"true"`: a combo whose text is
 *   typed as well as picked (WDP reads its Text); `"<updown>.increment"`: what its arrows add (1 by default);
 *   `"<updown>.arrows"` = `"click"`: its arrows come back by name rather than as the stepped value ([upDownArrow]);
 *   `"<name>.tip"`: its tooltip as the page works it out (WDP's `SetToolTip` at run time: a weapon's id, a comment),
 *   over the one in `tips/<form>.json`, and `""` for none ([WdpTips]).
 *
 * What comes back: [onValue] for a typed field (every keystroke, then `"<name>.leave"` when it is left: a press
 * elsewhere, Tab, Enter, its page going away), a slider, an up/down box (the value, when stepped or when what was
 * typed is committed; with `".arrows"` = `"click"` only what was typed, its arrows being `"<name>:up"`/`":down"` clicks), a combo pick (the item's text), a tab pick (the page's name); [onClick] for a button, a
 * check box, a radio, a tapped label/panel/picture, a tapped text or up/down box (its name: WDP's Click handlers on
 * boxes), Enter in a one-line box (`"<name>:enter"`, after its `.leave`: WDP's KeyDown for Return), and
 * `"<grid or list>:row:<i>"` / `":open:<i>"` (double tap). A wiring ignores the names it has no handler for.
 * Child windows are opened with [WdpDialogs.show] and message boxes with [WdpDialogs.message].
 */
interface WdpWiring {
    /** The page's current values. [hidden] is what the page hides of the designer's controls (its stacked views). */
    fun values(hidden: List<String>): WdpValues

    /** A slider moved or a field changed: the control's name and its new value, in the control's own units. */
    fun onValue(name: String, value: String)

    /** Something pressed: a button, a shortcut label, a knob picture. */
    fun onClick(name: String)

    /**
     * The mission arrived or changed — the briefing was printed, the cartridge re-read. A page that plans against
     * the mission (a target's coordinates, the callsign, the airbases) takes what it needs; a page that does not
     * ignores it. Called on every refresh, so it must be cheap and idempotent.
     */
    fun onMission(mission: WdpMission) {}
}
