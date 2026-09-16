package com.bmscompanion.app

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bmscompanion.app.data.ImageAction
import com.bmscompanion.app.data.Platform
import com.bmscompanion.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bmscompanion.app.ui.AppRoot
import com.bmscompanion.app.ui.theme.BmsTheme

class BmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

/** Media actions on Android: the system share sheet, and downloading to Pictures/BMS Companion. */
private fun imageActions(activity: android.app.Activity): List<ImageAction> = buildList {
    add(ImageAction("Share", "share") { name, load ->
        val bytes = load() ?: return@ImageAction "Could not download the screenshot"
        val uri = withContext(Dispatchers.IO) {
            val dir = java.io.File(activity.cacheDir, "shared").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = java.io.File(dir, name).apply { writeBytes(bytes) }
            androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (name.endsWith(".png", true)) "image/png" else "image/jpeg"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(android.content.Intent.createChooser(send, name))
        null
    })
    add(ImageAction("Download to this device", "download") { name, load ->
        val bytes = load() ?: return@ImageAction "Could not download the screenshot"
        withContext(Dispatchers.IO) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@withContext runCatching {
                // before Android 10 there is no permission-free public folder: keep it in the app's own Pictures folder
                val dir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)!!
                java.io.File(dir, name).writeBytes(bytes)
                "Saved to ${dir.path}"
            }.getOrElse { "Could not save: ${it.message}" }
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, if (name.endsWith(".png", true)) "image/png" else "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BMS Companion")
                }
                val resolver = activity.contentResolver
                val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                "Saved to Pictures/BMS Companion"
            }.getOrElse { "Could not save: ${it.message}" }
        }
    })
}

class MainActivity : ComponentActivity() {
    /** Old tablets run out of heap quickly: hand the cached images back when Android asks for memory. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) Repo.trimBitmaps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Platform.imageActions = imageActions(this)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        goFullscreen()
        val start = if (BuildConfig.DEBUG) intent?.getStringExtra("route") else null
        setContent { BmsTheme { AppRoot(start) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen() // re-hide after dialogs, keyboard or a transient swipe
    }

    /** Immersive fullscreen: status + navigation bars hidden, swipe from an edge shows them briefly. */
    private fun goFullscreen() {
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}
