package androidx.compose.ui.text

/** Android-only `PlatformTextStyle(includeFontPadding = …)`: desktop text has no font padding, so no platform style is needed. */
@Suppress("FunctionName", "UNUSED_PARAMETER")
fun PlatformTextStyle(includeFontPadding: Boolean): PlatformTextStyle? = null
