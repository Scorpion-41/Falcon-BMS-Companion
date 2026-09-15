package androidx.compose.ui.graphics

/** Matches Android's `Bitmap.asImageBitmap()` import used by the shared screens. */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = image
