package com.bmscompanion.desktop.bridge

import java.io.File
import javax.imageio.ImageIO

/**
 * `--wdptest <folder> <out.txt>` — the Weapon Delivery Planner remote, against the real program.
 *
 * Everything this feature rests on is a Windows behaviour that either works for a particular program or does not,
 * and none of it can be reasoned about from the source: whether a .NET window will draw itself into a bitmap on
 * request, whether a posted click reaches a control nobody has focused, how long a frame takes. So the check runs
 * the real WDP and measures.
 *
 * It writes frames beside the report so the pictures can be looked at, and it leaves WDP running or closed exactly
 * as it found it. Nothing in WDP's folder is written.
 */
internal fun wdpTest(root: File, out: File): String = buildString {
    appendLine("Weapon Delivery Planner remote")
    appendLine("folder: ${root.path}")
    val exe = File(root, WdpRemote.EXE)
    if (!exe.isFile) {
        appendLine("FAIL — ${WdpRemote.EXE} is not in that folder.")
        return@buildString
    }
    var ok = 0
    var bad = 0
    fun check(name: String, good: Boolean, note: String = "") {
        appendLine((if (good) "ok   " else "FAIL ") + name + (if (note.isEmpty()) "" else "  — $note"))
        if (good) ok++ else bad++
    }

    val wasRunning = WdpRemote.state(BridgeSettings(), BmsInstall()).running
    if (!wasRunning) {
        appendLine("starting it…")
        appendLine("  " + WdpRemote.open(root, hidden = true))
    }
    val s = WdpRemote.state(BridgeSettings(WeaponPlannerDir = root.path), BmsInstall())
    check("it is running", s.running, "${s.width}x${s.height}")
    check("the window was found", s.width > 200 && s.height > 200)
    check("it is out of sight on the PC", s.hidden)

    // 1. the picture
    val t0 = System.nanoTime()
    val img = WdpRemote.frame(0)
    val grabMs = (System.nanoTime() - t0) / 1_000_000
    check("a frame came back", img != null, "${grabMs} ms")
    if (img != null) {
        // a blank capture is the failure this is really looking for: count how many colours a coarse sample sees
        val seen = HashSet<Int>()
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) { seen += img.getRGB(x, y); x += 17 }
            y += 17
        }
        check("the frame is a picture, not a blank", seen.size > 20, "${seen.size} colours sampled")
        val t1 = System.nanoTime()
        val jpg = WdpRemote.jpeg(img)
        val encMs = (System.nanoTime() - t1) / 1_000_000
        check("it encodes", jpg.size > 5000, "${jpg.size / 1024} KB in ${encMs} ms")
        appendLine("     a frame costs about ${grabMs + encMs} ms end to end at ${img.width}x${img.height}")
        runCatching { ImageIO.write(img, "png", File(out.parentFile, "wdp-frame.png")) }

        // scaled, which is what a tablet actually asks for
        val t2 = System.nanoTime()
        val small = WdpRemote.frame(900)
        val smallMs = (System.nanoTime() - t2) / 1_000_000
        check("it scales for a tablet", small != null && small.width == 900, "${smallMs} ms")
        small?.let { appendLine("     900 px wide: ${WdpRemote.jpeg(it).size / 1024} KB") }
    }

    // 2. the input, proved by the picture changing
    appendLine("  " + WdpRemote.children())
    // what is actually under the points we are about to click
    appendLine("  under 169,63 (Briefing): " + WdpRemote.probe(169, 63, 0))
    appendLine("  under 382,63 (MAP):      " + WdpRemote.probe(382, 63, 0))
    appendLine("  under 619,373 (TOSS button): " + WdpRemote.probe(619, 373, 0))

    // the same click with the window back on the PC screen, to tell a hidden-window problem from an input one
    WdpRemote.setHidden(false)
    Thread.sleep(600)
    val shownBefore = WdpRemote.frame(0)
    WdpRemote.input("click", 619, 373, 0, 0)
    Thread.sleep(2000)
    val shownAfter = WdpRemote.frame(0)
    check(
        "a posted click works a real control (TOSS) while on screen",
        shownBefore != null && shownAfter != null && differs(shownBefore, shownAfter),
    )
    WdpRemote.setHidden(true)
    Thread.sleep(600)

    val before = WdpRemote.frame(0)
    // The tab strip across the top of WDP. Briefing is the check, because it redraws the page and raises nothing;
    // MAP is left for the HDR check below, since loading it is what makes WDP complain.
    WdpRemote.input("click", 619, 373, 0, 0)
    Thread.sleep(2000)
    val after = WdpRemote.frame(0)
    val changed = before != null && after != null && differs(before, after)
    check("a posted click works a real control while hidden", changed, if (changed) "the page redrew" else "the picture did not change")
    runCatching { after?.let { ImageIO.write(it, "png", File(out.parentFile, "wdp-after-click.png")) } }

    // 3. its own HDR complaint, which the MAP page raises, is taken off the pilot's hands
    WdpRemote.input("click", 382, 63, 0, 0)
    Thread.sleep(2500)
    val withBox = WdpRemote.frame(0)          // this frame dismisses it and shows it one last time
    Thread.sleep(1200)
    val cleared = WdpRemote.frame(0)
    runCatching { withBox?.let { ImageIO.write(it, "png", File(out.parentFile, "wdp-map.png")) } }
    runCatching { cleared?.let { ImageIO.write(it, "png", File(out.parentFile, "wdp-cleared.png")) } }
    check(
        "the HDR notice is taken off your hands",
        cleared != null && cleared.width > 400 && (withBox == null || differs(withBox, cleared)),
        "WDP raises it when MAP loads; the remote presses OK",
    )

    if (!wasRunning) {
        appendLine("closing it again…")
        appendLine("  " + WdpRemote.close())
    } else {
        appendLine("it was already running, so it has been left alone (and put back on screen)")
        WdpRemote.setHidden(false)
    }

    appendLine()
    appendLine(if (bad == 0) "PASS — the real program can be worked from anywhere this app reaches ($ok checks)"
    else "FAIL — $bad of ${ok + bad} checks")
}

/** Whether two frames differ anywhere that matters, sampled coarsely. */
private fun differs(a: java.awt.image.BufferedImage, b: java.awt.image.BufferedImage): Boolean {
    if (a.width != b.width || a.height != b.height) return true
    var diff = 0
    var y = 0
    while (y < a.height) {
        var x = 0
        while (x < a.width) {
            if (a.getRGB(x, y) != b.getRGB(x, y)) diff++
            x += 7
        }
        y += 7
    }
    return diff > 40
}
