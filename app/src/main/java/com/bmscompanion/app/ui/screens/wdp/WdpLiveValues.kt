package com.bmscompanion.app.ui.screens.wdp

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import com.bmscompanion.app.data.wdp.WdpControl
import com.bmscompanion.app.data.wdp.WdpForm
import com.bmscompanion.app.data.wdp.WdpValues

/**
 * **A page's values, handed to each control on its own.** A WDP page is hundreds of controls (the DataCard well over
 * a thousand), and the wiring hands the renderer one [WdpValues] for all of them. Passed down as it is, every new set
 * — a box typed in, a poll of the PC, a notice on the toolbar — recomposed every control on the page, which on a
 * tablet is a frame or several of work for a change to one box.
 *
 * So the renderer ([WdpFormView]) keeps one of these per form. Each control reads the values through [read], which
 * subscribes it to its own counter only; [update] compares a new set with the last one and bumps the counters of the
 * controls whose keys changed (a key belongs to the control named before its first `.` or `:` — `"txtFuel1"`,
 * `"txtFuel1.fore"`, `"grdRoute:row:3"`) and of the controls that hold them, since a container reads its children's
 * visibility, the page a tab control shows and which child is in front. A key no control owns is read by nothing
 * on the page and wakes nothing. A set equal to the last changes nothing at all.
 */
@Stable
internal class WdpLiveValues(form: WdpForm?) {
    /** the values every control reads, the newest set ([update]) */
    var current: WdpValues = WdpValues()
        private set

    private val stamps = HashMap<String, MutableIntState>()
    /** each control's container, by name (null at the page's roots) */
    private val parents = HashMap<String, String?>()
    /** controls whose counters [update] found changed, to be bumped after the composition that saw the new set */
    private val pending = LinkedHashSet<String>()
    private var first = true

    init {
        fun walk(c: WdpControl, parent: String?) {
            parents[c.name] = parent
            for (k in c.children) walk(k, c.name)
        }
        form?.roots?.forEach { walk(it, null) }
    }

    /** The values, as control [name] reads them: it is composed again when one of its keys (or a child's) changes. */
    fun read(name: String): WdpValues {
        stamps.getOrPut(name) { mutableIntStateOf(0) }.intValue
        return current
    }

    /**
     * Takes [new] as the page's values. Controls composed from now on read it at once; those already composed and
     * skipped are woken by [flush], which the renderer runs once the composition is done.
     */
    fun update(new: WdpValues) {
        val old = current
        val wasFirst = first
        first = false
        if (old === new || old == new) return
        current = new
        // the first set: every control is composed with it now, nothing composed before it to wake
        if (wasFirst) return
        if (parents.isEmpty()) return
        val a = old.values
        val b = new.values
        for ((k, v) in b) if (a[k] != v) changed(k)
        for (k in a.keys) if (k !in b) changed(k)
    }

    private fun changed(key: String) {
        var cut = key.length
        for (i in key.indices) { val ch = key[i]; if (ch == '.' || ch == ':') { cut = i; break } }
        var n: String? = key.substring(0, cut)
        if (n !in parents) return
        while (n != null && pending.add(n)) n = parents[n]
    }

    /** Wakes the controls [update] found changed. */
    fun flush() {
        if (pending.isEmpty()) return
        for (n in pending) stamps[n]?.let { it.intValue++ }
        pending.clear()
    }
}
