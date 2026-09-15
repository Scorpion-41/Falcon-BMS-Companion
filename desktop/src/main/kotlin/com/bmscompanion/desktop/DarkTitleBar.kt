package com.bmscompanion.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/** Asks Windows 10 (1809+) / 11 for a dark title bar so the window frame matches the dark UI. No-op elsewhere. */
object DarkTitleBar {
    @Suppress("FunctionName")
    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19 // Windows 10 before 20H1

    fun apply(window: java.awt.Window) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return
            val dwm = Native.load("dwmapi", DwmApi::class.java)
            val on = IntByReference(1)
            if (dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, on, 4) != 0)
                dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, on, 4)
        }
    }
}
