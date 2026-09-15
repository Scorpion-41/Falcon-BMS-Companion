package android.graphics

import androidx.compose.ui.graphics.ImageBitmap

/** Browser (and PC) stand-in for android.graphics.Bitmap: a decoded image as the shared screens use it (size + asImageBitmap). */
class Bitmap(val image: ImageBitmap) {
    val width: Int get() = image.width
    val height: Int get() = image.height
    val byteCount: Int get() = width * height * 4
}
