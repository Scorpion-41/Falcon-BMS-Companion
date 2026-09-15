package com.bmscompanion.desktop

import com.bmscompanion.app.data.ImageAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Media actions in the PC app window. With BMS on this PC the screenshots are already here: copy, save a copy, open the folder.
 * On a client PC they are on the BMS PC: download to Downloads\BMS Companion, copy, save a copy.
 */
fun pcImageActions(bmsHere: Boolean): List<ImageAction> = buildList {
    if (!bmsHere) add(ImageAction("Download to this PC", "download") { name, load ->
        val bytes = load() ?: return@ImageAction "Could not download the screenshot"
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(System.getProperty("user.home"), "Downloads\\BMS Companion").apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
                "Downloaded to ${dir.path}"
            }.getOrElse { "Could not save: ${it.message}" }
        }
    })
    addAll(pcFileActions(bmsHere))
}

private fun pcFileActions(bmsHere: Boolean): List<ImageAction> = listOfNotNull(
    ImageAction("Copy image", "copy") { _, load ->
        val bytes = load() ?: return@ImageAction "Could not download the screenshot"
        withContext(Dispatchers.IO) {
            val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@withContext "Unsupported image"
            Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(img), null)
            "Copied: paste it into Discord, a forum post or an image editor"
        }
    },
    ImageAction("Save a copy…", "save") { name, load ->
        val target = withContext(Dispatchers.Main) {
            val dlg = java.awt.FileDialog(null as java.awt.Frame?, "Save screenshot", java.awt.FileDialog.SAVE)
            dlg.file = name
            dlg.isVisible = true
            dlg.file?.let { File(dlg.directory, it) }
        } ?: return@ImageAction null
        val bytes = load() ?: return@ImageAction "Could not download the screenshot"
        withContext(Dispatchers.IO) { runCatching { target.writeBytes(bytes); "Saved to ${target.path}" }.getOrElse { "Could not save: ${it.message}" } }
    },
    if (!bmsHere) null else ImageAction("Open the screenshot folder", "folder") { _, _ ->
        val dir = com.bmscompanion.desktop.bridge.Bridge.picturesDir?.takeIf { File(it).isDirectory }
        if (dir != null) { SystemTools.open(dir); null } else "Screenshot folder not found"
    },
)

private class ImageSelection(private val image: java.awt.Image) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any = if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
}
