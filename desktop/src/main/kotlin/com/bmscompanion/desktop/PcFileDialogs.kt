package com.bmscompanion.desktop

import com.bmscompanion.app.data.FileMode
import com.bmscompanion.app.data.FileRequest
import com.bmscompanion.app.data.NativeAnswer
import com.bmscompanion.app.data.NativeFileDialogs
import com.bmscompanion.app.data.mission.LinkMode
import com.bmscompanion.app.data.mission.MissionLink
import com.bmscompanion.desktop.bridge.Bridge
import com.bmscompanion.desktop.bridge.DevGuard
import com.bmscompanion.desktop.bridge.PcFileRoutes
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * The Planner's file windows on the BMS PC's own window: **Windows' own file dialog**, as Weapon Delivery Planner's
 * `OpenFileDialog` and `SaveFileDialog` are — the same dialog .NET shows (Windows' common item dialog,
 * `IFileOpenDialog`/`IFileSaveDialog`), with WDP's title, start folder, file name, named file types, default extension
 * and Save's overwrite question, modal to the app's window. Set as [com.bmscompanion.app.data.PcFiles.native] at start.
 *
 * It applies only while the window reads Falcon BMS on this PC ("This PC"): a window linked to another PC shows that
 * PC's folders in the app's own window instead, since the files are there.
 *
 * The dialog runs on a thread of its own (COM wants a single-threaded apartment, and the dialog waits for the pilot),
 * never on the thread Compose draws on. **Nothing throws**: if the common item dialog cannot be made, AWT's
 * `java.awt.FileDialog` (Windows' older common dialog) is shown instead, and if that fails too the answer is
 * [NativeAnswer.Failed], on which the Planner shows its own window. Swing's `JFileChooser` is never used: under the
 * system look it walks the Windows shell for icons, which throws or takes the process down on many machines.
 */
object PcFileDialogs : NativeFileDialogs {
    override val available: Boolean
        get() = MissionLink.mode.value == LinkMode.LOCAL && Bridge.running

    override suspend fun show(request: FileRequest): NativeAnswer {
        val done = CompletableDeferred<NativeAnswer>()
        val start = startFolder(request.startDir)
        Thread({
            val first = try {
                ItemDialog.show(request, start)
            } catch (e: Throwable) {
                NativeAnswer.Failed(e.message ?: e.javaClass.simpleName)
            }
            val answer = if (first is NativeAnswer.Failed) {
                PcLog.write("file dialog (\"${request.title}\"): the common item dialog failed, ${first.why}", null)
                try { awtDialog(request, start) } catch (e: Throwable) { NativeAnswer.Failed(e.message ?: e.javaClass.simpleName) }
            } else first
            done.complete(answer)
        }, "file dialog").apply { isDaemon = true }.start()
        return done.await()
    }

    /** File Explorer on [folder] (made first when it is missing, as WDP's DataCards folders are), for Cards Directory. */
    override fun explore(folder: String): Boolean = try {
        val f = (PcFileRoutes.resolve(folder) as? PcFileRoutes.Target.Ok)?.file
        var dir: File? = f
        if (dir != null && !dir.isDirectory && DevGuard.refusal(dir.path) == null) runCatching { dir.mkdirs() }
        while (dir != null && !dir.isDirectory) dir = dir.parentFile
        if (dir == null) false else {
            ProcessBuilder("explorer.exe", dir.path).start()
            true
        }
    } catch (e: Throwable) {
        PcLog.write("Cards Directory: Explorer did not open ($folder): ${e.message}", null)
        false
    }

    /**
     * Where the dialog opens: [startDir] resolved as a remote window's would be (a place such as `@config`, a folder,
     * or a file, which names its folder), then its nearest folder that exists. Null leaves it to Windows (the folder
     * this program last used), as WDP's dialogs do when they name none.
     */
    private fun startFolder(startDir: String?): File? {
        if (startDir.isNullOrBlank()) return null
        val f = (PcFileRoutes.resolve(startDir) as? PcFileRoutes.Target.Ok)?.file ?: return null
        var dir: File? = if (runCatching { f.isFile }.getOrDefault(false)) f.parentFile else f
        while (dir != null && !runCatching { dir!!.isDirectory }.getOrDefault(false)) dir = dir.parentFile
        return dir
    }

    /** The app's window the dialog belongs to (it is modal to it), or null. */
    private fun ownerWindow(): java.awt.Window? = runCatching {
        val shown = java.awt.Window.getWindows().filter { it.isShowing && it is java.awt.Frame }
        shown.firstOrNull { it.isActive } ?: shown.firstOrNull()
    }.getOrNull()

    /**
     * For the checks: builds Windows' dialog for [request] exactly as [show] does — title, types, options, start
     * folder, name, default extension — then releases it unshown. Answers the start folder read back through the
     * shell item the dialog was given, or [NativeAnswer.Failed] naming each call Windows refused. Nothing appears.
     */
    internal fun buildUnshown(request: FileRequest): NativeAnswer = try {
        ItemDialog.show(request, startFolder(request.startDir), display = false)
    } catch (e: Throwable) {
        NativeAnswer.Failed(e.message ?: e.javaClass.simpleName)
    }

    /**
     * For the checks: shows Windows' dialog on the calling thread and waits for it, as [show] does on its own thread,
     * without the AWT fallback. The check calls it on a thread it has moved to a desktop of its own, where nobody sees it.
     */
    internal fun showHere(request: FileRequest): NativeAnswer = try {
        ItemDialog.show(request, startFolder(request.startDir))
    } catch (e: Throwable) {
        NativeAnswer.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** The name WDP's `DefaultExt` adds to a name typed without one, else the chosen type's first extension. */
    private fun defaultExt(r: FileRequest): String? =
        r.defaultExt?.takeIf { it.isNotBlank() } ?: r.filters.getOrNull(r.filterIndex)?.extensions?.firstOrNull()
            ?: r.filters.firstOrNull()?.extensions?.firstOrNull()

    // ---------------------------------------------------------------- Windows' common item dialog, through COM

    private object ItemDialog {
        private val CLSID_OPEN = Guid.CLSID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}")
        private val IID_OPEN = Guid.IID("{d57c7288-d4ad-4768-be02-9d969532d960}")
        private val CLSID_SAVE = Guid.CLSID("{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}")
        private val IID_SAVE = Guid.IID("{84bccd23-5fde-4cdb-aea4-af64b83d78ab}")
        private val IID_SHELLITEM = Guid.IID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}")

        // IFileDialog's methods, by their place in its table (IUnknown 0-2, IModalWindow::Show 3)
        private const val RELEASE = 2
        private const val SHOW = 3
        private const val SET_FILE_TYPES = 4
        private const val SET_FILE_TYPE_INDEX = 5
        private const val GET_FILE_TYPE_INDEX = 6
        private const val SET_OPTIONS = 9
        private const val GET_OPTIONS = 10
        private const val SET_DEFAULT_FOLDER = 11
        private const val SET_FOLDER = 12
        private const val SET_FILE_NAME = 15
        private const val SET_TITLE = 17
        private const val GET_RESULT = 20
        private const val SET_DEFAULT_EXTENSION = 22
        // IShellItem::GetDisplayName
        private const val GET_DISPLAY_NAME = 5

        private const val FOS_OVERWRITEPROMPT = 0x2
        private const val FOS_NOCHANGEDIR = 0x8
        private const val FOS_PICKFOLDERS = 0x20
        private const val FOS_FORCEFILESYSTEM = 0x40
        private const val FOS_PATHMUSTEXIST = 0x800
        private const val FOS_FILEMUSTEXIST = 0x1000
        private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
        private const val CANCELLED = 0x800704C7.toInt()   // HRESULT_FROM_WIN32(ERROR_CANCELLED)

        /** With [display] false (the checks), everything but showing it: the answer is the start folder read back. */
        fun show(r: FileRequest, start: File?, display: Boolean = true): NativeAnswer {
            val init = Ole32.INSTANCE.CoInitializeEx(null, 0x2 or 0x4)   // apartment threaded, no OLE1 DDE
            try {
                val ppv = PointerByReference()
                val save = r.mode == FileMode.SAVE
                // a folder is chosen with the Open dialog told to pick folders (WDP's FolderBrowserDialog)
                val pickFolder = r.mode == FileMode.FOLDER
                val hr = Ole32.INSTANCE.CoCreateInstance(if (save) CLSID_SAVE else CLSID_OPEN, null, 1, if (save) IID_SAVE else IID_OPEN, ppv)
                if (hr.toInt() != 0 || ppv.value == null) return NativeAnswer.Failed("CoCreateInstance 0x%08X".format(hr.toInt()))
                val dlg = ppv.value
                val keep = ArrayList<Memory>()   // the strings the dialog points at, alive until it closes
                // a setting Windows refuses is not worth failing the dialog for; the checks name it
                val refused = ArrayList<String>()
                fun step(name: String, result: Int) { if (result != 0) refused += "%s 0x%08X".format(name, result) }
                try {
                    step("SetTitle", call(dlg, SET_TITLE, WString(r.title)))
                    if (!pickFolder) {
                        val filters = r.filters.ifEmpty { listOf(com.bmscompanion.app.data.FileFilter.ALL) }
                        val specs = Memory(filters.size * 2L * Native.POINTER_SIZE)
                        filters.forEachIndexed { i, f ->
                            specs.setPointer(i * 2L * Native.POINTER_SIZE, wide(f.label, keep))
                            specs.setPointer((i * 2L + 1) * Native.POINTER_SIZE, wide(f.pattern, keep))
                        }
                        step("SetFileTypes", call(dlg, SET_FILE_TYPES, filters.size, specs))
                        step("SetFileTypeIndex", call(dlg, SET_FILE_TYPE_INDEX, r.filterIndex.coerceIn(0, filters.size - 1) + 1))
                    }
                    val opts = IntByReference()
                    step("GetOptions", call(dlg, GET_OPTIONS, opts))
                    var o = opts.value or FOS_FORCEFILESYSTEM or FOS_NOCHANGEDIR or FOS_PATHMUSTEXIST
                    o = when {
                        save -> o or FOS_OVERWRITEPROMPT
                        pickFolder -> o or FOS_PICKFOLDERS
                        else -> o or FOS_FILEMUSTEXIST
                    }
                    step("SetOptions", call(dlg, SET_OPTIONS, o))
                    var folderBack: String? = null
                    if (start != null) {
                        val item = shellItem(start.path)
                        if (item == null) refused += "SHCreateItemFromParsingName"
                        else {
                            step("SetDefaultFolder", call(dlg, SET_DEFAULT_FOLDER, item))
                            step("SetFolder", call(dlg, SET_FOLDER, item))
                            if (!display) folderBack = pathOf(item)
                            call(item, RELEASE)
                        }
                    }
                    r.fileName?.takeIf { it.isNotBlank() && !pickFolder }?.let { step("SetFileName", call(dlg, SET_FILE_NAME, WString(it))) }
                    if (save) defaultExt(r)?.let { step("SetDefaultExtension", call(dlg, SET_DEFAULT_EXTENSION, WString(it))) }
                    if (!display) {
                        return if (refused.isNotEmpty()) NativeAnswer.Failed(refused.joinToString(", "))
                        else NativeAnswer.Picked(folderBack ?: "", r.filterIndex)
                    }
                    val owner = ownerWindow()?.let { w -> runCatching { Native.getWindowPointer(w) }.getOrNull() }
                    val shown = call(dlg, SHOW, owner)
                    if (shown == CANCELLED) return NativeAnswer.Cancelled
                    if (shown != 0) return NativeAnswer.Failed("Show 0x%08X".format(shown))
                    val result = PointerByReference()
                    if (call(dlg, GET_RESULT, result) != 0 || result.value == null) return NativeAnswer.Cancelled
                    val path = try { pathOf(result.value) } finally { call(result.value, RELEASE) }
                        ?: return NativeAnswer.Failed("the picked item has no file-system path")
                    val index = IntByReference()
                    val type = if (call(dlg, GET_FILE_TYPE_INDEX, index) == 0 && index.value > 0) index.value - 1 else r.filterIndex
                    return NativeAnswer.Picked(path, type)
                } finally {
                    call(dlg, RELEASE)
                    keep.clear()
                }
            } finally {
                if (init.toInt() >= 0) Ole32.INSTANCE.CoUninitialize()
            }
        }

        /** The shell item for a folder, which the dialog opens in; null when Windows will not make one. */
        private fun shellItem(path: String): Pointer? {
            val out = PointerByReference()
            val f = NativeLibrary.getInstance("shell32").getFunction("SHCreateItemFromParsingName", Function.ALT_CONVENTION)
            val hr = f.invokeInt(arrayOf(WString(path), null, IID_SHELLITEM, out))
            return if (hr == 0) out.value else null
        }

        /** An item's path on disk ("G:\\Falcon BMS 4.38\\User\\Config\\Viper.ini"). */
        private fun pathOf(item: Pointer): String? {
            val out = PointerByReference()
            if (call(item, GET_DISPLAY_NAME, SIGDN_FILESYSPATH, out) != 0 || out.value == null) return null
            return try { out.value.getWideString(0) } finally { Ole32.INSTANCE.CoTaskMemFree(out.value) }
        }

        private fun wide(s: String, keep: MutableList<Memory>): Memory =
            Memory((s.length + 1) * 2L).also { it.setWideString(0, s); keep += it }

        /** Method [index] of the COM object [obj]; answers its HRESULT. */
        private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
            val table = obj.getPointer(0)
            val fn = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
            return fn.invokeInt(arrayOf<Any?>(obj, *args))
        }
    }

    // ---------------------------------------------------------------- the fallback: AWT's FileDialog

    /**
     * `java.awt.FileDialog`: Windows' older common dialog, which shows every type in one list, so the chosen type's
     * pattern goes in the name box, where Windows reads it as the filter. Shown from this thread (a modal AWT dialog
     * waits on the calling thread while the event thread goes on).
     */
    private fun awtDialog(r: FileRequest, start: File?): NativeAnswer {
        // AWT's dialog picks files only: a folder is chosen in the Planner's own window instead
        if (r.mode == FileMode.FOLDER) return NativeAnswer.Failed("AWT's file dialog cannot choose a folder")
        val owner = ownerWindow() as? java.awt.Frame
        val d = java.awt.FileDialog(owner, r.title, if (r.mode == FileMode.SAVE) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
        start?.let { d.directory = it.path }
        val pattern = r.filters.getOrNull(r.filterIndex)?.pattern ?: r.filters.firstOrNull()?.pattern
        d.file = r.fileName?.takeIf { it.isNotBlank() } ?: pattern
        d.isVisible = true
        val name = d.file ?: return NativeAnswer.Cancelled
        val dir = d.directory ?: start?.path ?: return NativeAnswer.Cancelled
        var picked = File(dir, name)
        if (r.mode == FileMode.SAVE && '.' !in picked.name) defaultExt(r)?.let { picked = File(picked.parentFile, picked.name + "." + it) }
        return NativeAnswer.Picked(picked.path, r.filterIndex)
    }
}
