@file:Suppress("NOTHING_TO_INLINE", "unused", "UNUSED_PARAMETER")

package com.bmscompanion.app.ui.screens

import com.bmscompanion.web.Printf
import java.util.Locale

// Browser stand-ins for the few JVM APIs the shared screens in this package call (String.format, Math, System, Locale casing).

internal fun String.format(vararg args: Any?): String = Printf.format(this, args)
internal fun String.format(locale: Locale?, vararg args: Any?): String = Printf.format(this, args)
internal fun String.Companion.format(format: String, vararg args: Any?): String = Printf.format(format, args)
internal fun String.Companion.format(locale: Locale?, format: String, vararg args: Any?): String = Printf.format(format, args)
internal inline fun String.uppercase(locale: Locale): String = uppercase()
internal inline fun String.lowercase(locale: Locale): String = lowercase()
internal inline fun Char.titlecase(locale: Locale): String = titlecase()
internal inline fun <R> synchronized(lock: Any, block: () -> R): R = block()

internal object Math {
    const val PI = kotlin.math.PI
    fun toRadians(deg: Double) = deg * kotlin.math.PI / 180.0
    fun toDegrees(rad: Double) = rad * 180.0 / kotlin.math.PI
    fun sin(a: Double) = kotlin.math.sin(a)
    fun cos(a: Double) = kotlin.math.cos(a)
    fun exp(a: Double) = kotlin.math.exp(a)
    fun round(a: Double): Long = kotlin.math.floor(a + 0.5).toLong()
    fun round(a: Float): Int = kotlin.math.floor(a + 0.5f).toInt()
}

internal object System {
    fun currentTimeMillis(): Long = com.bmscompanion.web.nowMillis().toLong()
    fun nanoTime(): Long = (com.bmscompanion.web.perfMillis() * 1_000_000.0).toLong()
}
