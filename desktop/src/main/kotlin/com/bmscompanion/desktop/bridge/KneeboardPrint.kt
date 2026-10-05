package com.bmscompanion.desktop.bridge

import com.bmscompanion.app.data.mission.KbFileResult
import com.bmscompanion.app.data.mission.KbFileSend
import com.bmscompanion.app.data.mission.KbKind
import com.bmscompanion.app.data.mission.KbOwner
import com.bmscompanion.app.data.mission.KbPrintState
import com.bmscompanion.app.data.mission.KbSlot
import com.bmscompanion.desktop.AppInfo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * `/api/kbprint/…`: the Planner's **Upd Kneeboard** — the F-16's cockpit kneeboard pages, their owners and
 * pictures, and writing a page drawn on a device into one of them (docs/PROTOCOL.md, "Planner integration"; the
 * study is R3-KNEEBOARD, the decisions A15-A17 of R3-PLAN).
 *
 * **Which files.** Falcon BMS shows the F-16's pilot-model kneeboard from sixteen textures, `7982.dds`-`7997.dds`, in
 * `KoreaObj` under the `3ddatadir` of the theater BMS is set to ([Theaters]; LHTO and Hellas WCP read Hellas's, EF2000
 * Balkans's, the Korean add-ons KTO's). The left half of each 2048-pixel file is the left knee's page *n*, the right
 * half the right knee's; BMS reads them on entering the cockpit. When `KoreaObj_HiRes` holds a twin, BMS shows the twin
 * (`g_bHiResTextures`, on by default), so the twin is printed too, in its own size. A page file that does not exist is
 * never created (OFM KTO has page 1 only). The F-15C's pages are not touched.
 *
 * **How a file is written** (the user's rule: these pages are made again at will by EZBoards, BMS's PRINT or this
 * Print, so they are written in place, with no backup):
 * - from the file's own bytes, keeping its format, size, mip count and 128-byte header; only the header's unused field
 *   at offset 32 changes, to a tag saying who printed which half ([Dds.print]); the half not printed keeps its blocks;
 * - to `<n>.dds.bmsc-new` beside it, then moved over it in one step, so a half-written texture never reaches BMS;
 * - nothing throws: each file comes back written, unchanged or refused with the reason (read-only, in use, no such
 *   page, a format this program does not write, cut short, disk full);
 * - anything but DXT1, DXT5 or 32-bit BGRA, square and a power of two, is refused, never guessed at.
 *
 * **The page's shape.** A device lays each half page out at [PAGE_W]×[PAGE_H] (2:3, [PAGE_ASPECT]) and the PC stretches
 * it by 4/3 vertically into the texture's 1:2 half, as WDP and EZBoards do: the pad shows the half at about 2:3.
 * A **Picture** half is not drawn on a device: the device sends the file's path and the PC stretches the file's own
 * pixels over the half ([pictureHalf], [PictureFile]), as WDP resizes the file itself, so nothing is lost on the way.
 *
 * **Put BMS's page back** copies from the pages Falcon BMS ships in its Docs folder (KTO's
 * `Docs\07 Kneeboard Templates\F-16\DDS_Backup`, the Balkans' and Hellas's own backup folders), where a file of the
 * same name and size is there; the same source rebuilds a page file that is empty or cut short.
 *
 * Owners are worked out from the file alone: our tag (and which half), EZBoards' `IMAGEMAGICK` tag, the bytes of BMS's
 * shipped copy, html_brief's uncompressed 32-bit layout, WDP's DevIL signature, else another tool.
 */
object KneeboardPrint {
    /** The routes this object answers. */
    val ROUTES = listOf("GET /api/kbprint/state", "GET /api/kbprint/thumb", "POST /api/kbprint/file", "POST /api/kbprint/shipped")

    /** Page 1's file number; page n is `7981 + n`. */
    const val FIRST = 7982
    const val PAGES = 16
    /** The size a device draws one half page at. */
    const val PAGE_W = 1024
    const val PAGE_H = 1536
    /** The visible shape of a half page on the pad, width over height (R3-KNEEBOARD §6; the Test page's circle checks it). */
    const val PAGE_ASPECT = 2f / 3f
    /** The start of the header tag this program writes at offset 32. */
    const val TAG = "BMSCOMPANION"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

    fun pageName(n: Int) = "${FIRST + n - 1}.dds"

    // ================================================================ routes

    fun handle(req: ApiRequest): ApiResponse {
        val key = "${req.method} ${req.path.trimEnd('/')}"
        return try {
            when (key) {
                "GET /api/kbprint/state" -> routeState()
                "GET /api/kbprint/thumb" -> routeThumb(req)
                "POST /api/kbprint/file" -> routeFile(req)
                "POST /api/kbprint/shipped" -> routeShipped(req)
                else -> ApiResponse.notFound()
            }
        } catch (e: Throwable) {
            BridgeLog.warn("Upd Kneeboard: ${e::class.java.simpleName}: ${e.message}")
            error("Upd Kneeboard failed on the PC: ${e.message ?: e::class.java.simpleName}", 500)
        }
    }

    private fun error(sentence: String, status: Int) =
        ApiResponse.json("""{"error":${JsonPrimitive(sentence)}}""", status)

    /** The page folder of the theater Falcon BMS is set to, on this PC. */
    private fun here(): Place? {
        val base = Bridge.install.baseDir ?: return null
        return place(File(base), Bridge.install.theater)
    }

    private const val NO_BMS = "Falcon BMS was not found on this PC: set its folder in BMS Companion's settings on the PC."

    private var lastSharedDir: String? = null

    private fun routeState(): ApiResponse {
        val at = here()
        val snap = Bridge.snapshot()
        // U7 (R3-KNEEBOARD §10): what BMS itself reports as its 3D data folder, logged once per value to settle whether it is absolute
        snap.strings[THR_3DDATADIR]?.takeIf { it.isNotBlank() && it != lastSharedDir }?.let {
            lastSharedDir = it
            BridgeLog.info("Falcon BMS reports its 3D data folder as \"$it\" (kneeboard pages are taken from the theater definition: ${at?.folderLabel ?: "none"})")
        }
        val state = if (at == null) KbPrintState(error = NO_BMS)
        else state(at, Bridge.settings.value.EzBoardsDir, snap.flying)
        val body = json.encodeToString(KbPrintState.serializer(), state)
        return ApiResponse.json(body, if (state.error != null) 409 else 200)
    }

    private fun routeThumb(req: ApiRequest): ApiResponse {
        val n = req.query["n"]?.toIntOrNull()?.takeIf { it in 1..PAGES } ?: return error("Which page? n must be 1 to $PAGES.", 400)
        val side = when (req.query["side"]?.uppercase()) { "L" -> 0; "R" -> 1; else -> return error("Which half? side must be L or R.", 400) }
        val w = (req.query["w"]?.toIntOrNull() ?: 256).coerceIn(32, 1024)
        val at = here() ?: return error(NO_BMS, 409)
        val (jpeg, why) = thumb(at, n, side, w)
        return if (jpeg != null) ApiResponse(200, "image/jpeg", jpeg) else error(why ?: "No picture.", 409)
    }

    private fun routeFile(req: ApiRequest): ApiResponse {
        val send = runCatching { json.decodeFromString(KbFileSend.serializer(), req.body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return error("The page to print could not be read.", 400)
        if (send.n !in 1..PAGES) return error("Which page? n must be 1 to $PAGES.", 400)
        val halves = listOf(send.left, send.right)
        val pics = arrayOfNulls<BufferedImage>(2)
        val kinds = arrayOfNulls<String>(2)
        // a Picture half whose file is gone or cannot be read is left as it is, and says why; the other half still goes
        val refused = arrayOfNulls<String>(2)
        val at = here() ?: return error(NO_BMS, 409)
        for (s in 0..1) {
            val half = halves[s] ?: continue
            if (half.kind == KbKind.LEAVE) continue
            if (half.kind == KbKind.PICTURE && half.picture.isNotBlank()) {
                when (val p = pictureHalf(at, send.n, s, half.picture)) {
                    is PictureFile.Read.Ok -> { pics[s] = p.img; kinds[s] = half.kind }
                    is PictureFile.Read.Bad -> refused[s] = p.why
                }
                continue
            }
            if (half.png.isBlank()) continue
            pics[s] = picture(half.png) ?: return error("The picture for page ${send.n}'s ${if (s == 0) "left" else "right"} half could not be read.", 400)
            kinds[s] = half.kind
        }
        if (pics.all { it == null } && refused.any { it != null }) {
            val r = KbFileResult(pageName(send.n), KbFileResult.REFUSED, refused.filterNotNull().joinToString(" "), refused[0], refused[1])
            BridgeLog.info("Upd Kneeboard: ${r.file} ${r.status} — ${r.reason}")
            return result(r)
        }
        val done = printPage(at, send.n, pics, kinds)
        val r = done.result.copy(leftRefused = refused[0], rightRefused = refused[1])
        BridgeLog.info("Upd Kneeboard: ${r.file} ${r.status}" + (listOfNotNull(r.reason, r.leftRefused, r.rightRefused).joinToString(" ").ifEmpty { null }?.let { " — $it" } ?: "") + " (${done.ms} ms)")
        return result(r)
    }

    /**
     * Page [n]'s half [side] (0 left) as the picture file [path] on this PC, as WDP draws its "Selected Picture": read
     * from the file's own pixels (never from a device's capture) at the size the page file's half needs, then stretched
     * over the whole half by [printInto]. [PictureFile.Read.Bad] carries the sentence for the window.
     */
    private fun pictureHalf(at: Place, n: Int, side: Int, path: String): PictureFile.Read {
        val knee = if (side == 0) "left knee" else "right knee"
        val target = PcFileRoutes.pictureFile(path)
        val file = target.file ?: return PictureFile.Read.Bad("Page $n, $knee, was left as it is: ${target.why ?: "no picture was named."}")
        // the half's size in the largest file written (the page, or its high-resolution twin): 1024 x 2048 on a 2048 page
        val sizes = listOfNotNull(at.page(n), at.twin(n)).mapNotNull { f ->
            runCatching {
                val head = ByteArray(Dds.HEADER)
                RandomAccessFile(f, "r").use { it.readFully(head) }
                Dds.header(head, f.length()).takeIf { it.width > 0 && it.height > 0 }?.let { it.width / 2 to it.height }
            }.getOrNull()
        }
        val needW = sizes.maxOfOrNull { it.first } ?: PAGE_W
        val needH = sizes.maxOfOrNull { it.second } ?: (PAGE_W * 2)
        return when (val r = PictureFile.read(file) { w, h -> w >= needW && h >= needH }) {
            // held only at the size the half is drawn from while the page waits for its turn to be written
            is PictureFile.Read.Ok -> runCatching { PictureFile.Read.Ok(halvedFor(r.img, needW, needH), r.format, r.width, r.height) }.getOrElse { r }
            is PictureFile.Read.Bad -> PictureFile.Read.Bad("Page $n, $knee, was left as it is: ${(target.asked ?: file).name} could not be read as a picture: ${r.why}")
        }
    }

    private fun result(r: KbFileResult): ApiResponse {
        val obj = json.encodeToJsonElement(KbFileResult.serializer(), r).jsonObject
        return if (r.status == KbFileResult.REFUSED)
            ApiResponse.json(JsonObject(obj + ("error" to JsonPrimitive(r.reason ?: "${r.file} was not written."))).toString(), 409)
        else ApiResponse.json(obj.toString(), 200)
    }

    private fun routeShipped(req: ApiRequest): ApiResponse {
        val which = req.query["n"]?.trim()?.lowercase() ?: "all"
        val pages = if (which == "all") (1..PAGES).toList()
        else listOf(which.toIntOrNull()?.takeIf { it in 1..PAGES } ?: return error("Which page? n must be 1 to $PAGES, or all.", 400))
        val at = here() ?: return error(NO_BMS, 409)
        at.error?.let { return error(it, 409) }
        if (at.shipped == null) return error(noShipped(at), 409)
        val results = putShipped(at, pages)
        results.filter { it.status != KbFileResult.UNCHANGED }.forEach {
            BridgeLog.info("Put BMS's page back: ${it.file} ${it.status}" + (it.reason?.let { r -> " — $r" } ?: ""))
        }
        return ApiResponse.json(json.encodeToString(ListSerializer(KbFileResult.serializer()), results), 200)
    }

    private fun noShipped(at: Place) =
        "Falcon BMS ships no copy of these pages for ${at.theater?.name ?: "this theater"} (only Korea KTO, the Balkans and Hellas have one)."

    /** A PNG (or any picture ImageIO reads) sent as base64, with or without a `data:` prefix. */
    fun picture(base64: String): BufferedImage? = runCatching {
        val bytes = java.util.Base64.getMimeDecoder().decode(base64.substringAfter("base64,"))
        ImageIO.read(ByteArrayInputStream(bytes))?.takeIf { it.width in 1..8192 && it.height in 1..8192 }
    }.getOrNull()

    // ================================================================ where the pages are

    /**
     * The kneeboard page folder of one theater. [dir] is `<3ddatadir>\KoreaObj`, [hiRes] its `_HiRes` twin folder when
     * there is one, [shipped] the folder where Falcon BMS ships its own copies of those pages, [error] why there is no
     * folder.
     */
    class Place(
        val root: File,
        val theater: Theaters.Theater?,
        val dir: File?,
        val hiRes: File?,
        val shipped: File?,
        val error: String?,
    ) {
        fun page(n: Int): File? = dir?.let { File(it, pageName(n)) }
        fun twin(n: Int): File? = hiRes?.let { File(it, pageName(n)) }?.takeIf { it.isFile }
        fun shippedPage(n: Int): File? = shipped?.let { File(it, pageName(n)) }?.takeIf { it.isFile }
        /** [dir] relative to the BMS folder, never an absolute path */
        val folderLabel: String? get() = dir?.let { relative(root, it) }
    }

    /** The page folder for the theater named [theaterName] (the registry's `curTheater`) in the BMS folder [root]. */
    fun place(root: File, theaterName: String?): Place {
        val set = Theaters.at(root)
        val t = if (theaterName.isNullOrBlank()) set.all.firstOrNull() else set.byName(theaterName)
        if (t == null) {
            val why = if (theaterName.isNullOrBlank()) (set.error ?: "Falcon BMS's theater list could not be read.")
            else "Falcon BMS is set to the theater \"${theaterName.trim()}\", which its theater list does not name."
            return Place(root, null, null, null, null, why)
        }
        val dir = set.kneeboardDir(t)
            ?: return Place(root, t, null, null, null,
                "${t.name} has no kneeboard folder (${t.threeDDataDir ?: "its 3D data folder"}\\KoreaObj was not found).")
        return Place(root, t, dir, set.kneeboardHiResDir(t), shippedFolder(set, t), null)
    }

    private val shippedCache = HashMap<String, Pair<Long, File?>>()

    /**
     * Where Falcon BMS ships its own copies of [t]'s pages: under `Docs` of the add-on its 3D data folder belongs to
     * (`Data\Add-On Hellas\Docs\03 3d Kneeboard Backup`, `Data\Add-On Balkans\Docs\04 3d Kneeboards Backup\01 F-16`), or
     * the install's own `Docs` for the base theater's (`Docs\07 Kneeboard Templates\F-16\DDS_Backup`): a folder whose
     * path names a kneeboard, not an F-15's, holding `7982.dds`. Three levels down at most; null when there is none.
     */
    private fun shippedFolder(set: Theaters.TheaterSet, t: Theaters.Theater): File? {
        val rel = t.threeDDataDir ?: return null
        val first = rel.replace('/', '\\').split('\\').firstOrNull { it.isNotBlank() } ?: return null
        val docs = (if (first.startsWith("add-on", ignoreCase = true)) Theaters.resolveDir(set.data, "$first\\Docs")
        else Theaters.resolveDir(set.root, "Docs")) ?: return null
        val key = Theaters.canonical(docs)
        synchronized(shippedCache) {
            shippedCache[key]?.let { (at, f) -> if (System.currentTimeMillis() - at < 30_000) return f }
        }
        val found = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            val kids = runCatching { d.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
            if (depth > 0) {
                val path = relative(docs, d).lowercase()
                if (path.contains("kneeboard") && !path.contains("f-15") && !path.contains("f15") &&
                    kids.any { it.isFile && it.name.equals("7982.dds", ignoreCase = true) }) found += d
            }
            if (depth < 3) kids.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.forEach { walk(it, depth + 1) }
        }
        runCatching { walk(docs, 0) }
        // a folder named as a backup, or as the F-16's, before any other
        val best = found.sortedBy { f ->
            val p = relative(docs, f).lowercase()
            (if (p.contains("backup")) 0 else 2) + (if (p.contains("f-16") || p.contains("f16")) 0 else 1)
        }.firstOrNull()
        synchronized(shippedCache) { shippedCache[key] = System.currentTimeMillis() to best }
        return best
    }

    /**
     * [f] relative to [root], or its name alone when it is not inside [root]. Inside means a folder boundary after the
     * root: `D:\Falcon BMS 4.38 Tools\EZBoards` is not inside `D:\Falcon BMS 4.38` although its path starts with it.
     */
    internal fun relative(root: File, f: File): String {
        val r = Theaters.canonical(root)
        val path = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
        val p = path.lowercase()
        val inside = p.startsWith(r) && (p.length == r.length || p[r.length] == '\\' || p[r.length] == '/')
        return if (inside) path.substring(r.length).trimStart('\\', '/') else f.name
    }

    // ================================================================ state

    /** The halves EZBoards writes at every PRINT: its `SET KNEEBOARD[F16_<n><L|R>]=…` lines ("1L", "1R"), read only. */
    fun ezClaims(dir: File?): Set<String> {
        val f = dir?.let { File(it, "CONFIG_USER.BAT") }?.takeIf { it.isFile } ?: return emptySet()
        val line = Regex("""^\s*SET\s+"?KNEEBOARD\[F16_(\d{1,2})([LR])]\s*=\s*(.*?)"?\s*$""", RegexOption.IGNORE_CASE)
        val out = LinkedHashSet<String>()
        runCatching {
            f.readLines(Charsets.ISO_8859_1).forEach { raw ->
                val t = raw.trim()
                if (t.startsWith("REM", ignoreCase = true) || t.startsWith("::")) return@forEach
                val m = line.find(t) ?: return@forEach
                val n = m.groupValues[1].toInt()
                if (n in 1..PAGES && m.groupValues[3].isNotBlank()) out += "$n${m.groupValues[2].uppercase()}"
            }
        }
        return out
    }

    /** What the Print window shows: the folder, the sixteen files and who made each half. [ezDir] is EZBoards' folder. */
    fun state(at: Place, ezDir: String?, in3d: Boolean): KbPrintState {
        val ez = ezDir?.takeIf { EzBoardsRunner.isValidDir(it) }?.let(::File)
        val claims = ezClaims(ez)
        val ezConfig = ez?.let { File(it, "CONFIG_USER.BAT") }?.takeIf { it.isFile }?.let { f ->
            val rel = relative(at.root, f)
            if (rel != f.name) rel else "${ez.name}\\${f.name}"
        }
        val pages = (1..PAGES).map { n -> slot(at, n, claims) }
        return KbPrintState(
            folder = at.folderLabel,
            theater = at.theater?.name,
            twin = (1..PAGES).any { at.twin(it) != null },
            bmsIn3d = in3d,
            pages = pages,
            shipped = at.shipped != null && (1..PAGES).any { at.shippedPage(it) != null },
            ezConfig = ezConfig,
            error = at.error,
            mode = MissionSource.mode(),
        )
    }

    /** Who made each half of page [n] now, from the file alone; null when there is no such file or it cannot be read. */
    fun ownersOf(at: Place, n: Int): Owners? = runCatching {
        val f = at.page(n)?.takeIf { it.isFile } ?: return@runCatching null
        val length = f.length()
        val head = ByteArray(minOf(Dds.HEADER.toLong(), length).toInt())
        RandomAccessFile(f, "r").use { it.readFully(head) }
        owners(f, Dds.header(head, length), at.shippedPage(n))
    }.getOrNull()

    private fun slot(at: Place, n: Int, claims: Set<String>): KbSlot {
        val f = at.page(n)
        val base = KbSlot(n = n, file = pageName(n), ezLeft = "${n}L" in claims, ezRight = "${n}R" in claims)
        if (f == null || !f.isFile) return base.copy(left = KbOwner.MISSING, right = KbOwner.MISSING, missing = true)
        val head = ByteArray(Dds.HEADER)
        val length = f.length()
        val read = runCatching { RandomAccessFile(f, "r").use { it.readFully(head, 0, minOf(Dds.HEADER.toLong(), length).toInt()) } }
        if (read.isFailure) return base.copy(modified = f.lastModified(), problem = why(read.exceptionOrNull()!!, f, at))
        val info = Dds.header(if (length >= Dds.HEADER) head else head.copyOf(length.toInt()), length)
        val o = owners(f, info, at.shippedPage(n))
        return base.copy(
            format = info.format, mips = info.levels, size = info.width, left = o.left, right = o.right,
            modified = f.lastModified(), leftKind = o.leftKind, rightKind = o.rightKind,
            problem = info.unwritable?.let { "${f.name} is left as it is: $it." },
        )
    }

    // ================================================================ owners

    class Owners(val left: String, val right: String, val leftKind: String? = null, val rightKind: String? = null)

    private val CODES = mapOf(
        KbOwner.BMS to 'b', KbOwner.EZBOARDS to 'e', KbOwner.HTMLBRIEF to 'h', KbOwner.WDP to 'w',
        KbOwner.COMPANION to 'c', KbOwner.OTHER to 'o',
    )
    private val OWNERS = CODES.entries.associate { (k, v) -> v to k }

    /**
     * The page kinds in a fixed order: a kind is written into the tag as its position, one base-36 digit (0-9, then a-z:
     * the first sixteen are the hex digits the first 1.3.8 builds wrote, so their tags read the same). Add at the end.
     */
    private val KINDS = listOf(
        KbKind.LEAVE, KbKind.BLANK, KbKind.TEST, KbKind.DATACARD_LEFT, KbKind.DATACARD_RIGHT, KbKind.COORDINATION_LEFT,
        KbKind.COORDINATION_RIGHT, KbKind.BRIEFING, KbKind.WEATHER, KbKind.TARGETS_LEFT, KbKind.TARGETS_RIGHT,
        KbKind.ROUTE_MAP, KbKind.ATTACK, KbKind.DEPARTURE, KbKind.ARRIVAL, KbKind.ALTERNATE, KbKind.PICTURE,
    )

    private val TAG_RE = Regex("""^$TAG(\S*)(?: L([a-z])([0-9a-z-]))?(?: R([a-z])([0-9a-z-]))?""")

    /** The tag for a file whose halves are now [owners], each with the kind printed on it (null: unknown). */
    fun tag(owners: Owners): String {
        val ver = AppInfo.version.filter { it.isLetterOrDigit() || it in ".-_" }.take(16)
        fun half(owner: String, kind: String?): String {
            val k = kind?.let { KINDS.indexOf(it) }?.takeIf { it in 0..35 }?.let { Integer.toString(it, 36) } ?: "-"
            return "${CODES[owner] ?: 'o'}$k"
        }
        return "$TAG$ver L${half(owners.left, owners.leftKind)} R${half(owners.right, owners.rightKind)}"
    }

    private val hashCache = HashMap<String, String>()

    private fun sha(f: File): String? {
        val key = "${f.path}|${f.length()}|${f.lastModified()}"
        synchronized(hashCache) { hashCache[key]?.let { return it } }
        val h = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val r = s.read(buf); if (r < 0) break; md.update(buf, 0, r) } }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        synchronized(hashCache) { if (hashCache.size > 256) hashCache.clear(); hashCache[key] = h }
        return h
    }

    /**
     * Who made each half of [file] (header [info]), worked out from the file alone. [shipped] is BMS's own copy of the
     * same page, when it ships one (the only proof a page is BMS's); without one, a page with the layout BMS's own pages
     * have (made by texconv: depth 1 or a full mip chain, no tag) counts as BMS's.
     */
    fun owners(file: File?, info: Dds.Info, shipped: File?): Owners {
        if (file == null || !file.isFile) return Owners(KbOwner.MISSING, KbOwner.MISSING)
        TAG_RE.find(info.tag)?.let { m ->
            fun owner(c: String) = c.firstOrNull()?.let { OWNERS[it] } ?: KbOwner.COMPANION
            fun kind(c: String) = c.firstOrNull()?.takeIf { it != '-' }?.digitToIntOrNull(36)?.let { KINDS.getOrNull(it) }
            val (_, lo, lk, ro, rk) = m.destructured
            return Owners(owner(lo), owner(ro), kind(lk).takeIf { lo == "c" }, kind(rk).takeIf { ro == "c" })
        }
        val whole = when {
            info.tag.startsWith("IMAGEMAGICK", ignoreCase = true) -> KbOwner.EZBOARDS
            shipped != null && shipped.length() == file.length() && sha(shipped)?.let { it == sha(file) } == true -> KbOwner.BMS
            info.kind == Dds.Kind.BGRA && info.flags == 0x100F && info.pitch == info.width * 4 && info.tag.isEmpty() -> KbOwner.HTMLBRIEF
            info.kind == Dds.Kind.DXT1 && info.flags == 0xA1007 && info.levels == 1 && info.depth == 0 && info.caps == 0x1000 && info.tag.isEmpty() -> KbOwner.WDP
            shipped == null && info.tag.isEmpty() && (info.kind == Dds.Kind.DXT1 || info.kind == Dds.Kind.DXT5) &&
                info.flags == 0xA1007 && (info.depth == 1 || info.levels > 1) -> KbOwner.BMS
            else -> KbOwner.OTHER
        }
        return Owners(whole, whole)
    }

    // ================================================================ printing

    /** One print: what happened to the file, and how long it took (encoding and writing, the twin included). */
    class Printed(val result: KbFileResult, val ms: Long, val twin: KbFileResult?)

    /**
     * Prints page [n]: [pics] holds the left and right half pictures (null: leave that half as it is), [kinds] what each
     * is ([KbKind]). The page file is written, and its `KoreaObj_HiRes` twin when there is one.
     */
    @Synchronized
    fun printPage(at: Place, n: Int, pics: Array<BufferedImage?>, kinds: Array<String?>): Printed {
        val t0 = System.nanoTime()
        val name = pageName(n)
        val ms = { (System.nanoTime() - t0) / 1_000_000 }
        at.error?.let { return Printed(KbFileResult(name, KbFileResult.REFUSED, it), ms(), null) }
        val file = at.page(n)
        if (file == null || !file.isFile)
            return Printed(KbFileResult(name, KbFileResult.REFUSED,
                "Page $n ($name) does not exist in ${at.folderLabel ?: "this theater's kneeboard folder"}; BMS Companion never creates a page file."), ms(), null)
        if (pics.all { it == null }) return Printed(KbFileResult(name, KbFileResult.UNCHANGED), ms(), null)
        val main = printInto(file, at, n, pics, kinds)
        val twinFile = at.twin(n)
        val twin = twinFile?.let { printInto(it, at, n, pics, kinds) }
        val result = if (twin == null || twin.status != KbFileResult.REFUSED) main
        else main.copy(reason = listOfNotNull(main.reason, "Its KoreaObj_HiRes twin, which BMS shows instead, was not written: ${twin.reason}").joinToString(" "))
        return Printed(result, ms(), twin)
    }

    private fun printInto(file: File, at: Place, n: Int, pics: Array<BufferedImage?>, kinds: Array<String?>): KbFileResult {
        val name = file.name
        return try {
            val bytes = file.readBytes()
            val info = Dds.header(bytes)
            info.unwritable?.let { return KbFileResult(name, KbFileResult.REFUSED, "$name was left as it is: $it.") }
            val before = owners(file, info, at.shippedPage(n))
            val halves = Array(2) { s -> pics[s]?.let { raster(it, info.width / 2, info.height) } }
            val after = Owners(
                left = if (halves[0] != null) KbOwner.COMPANION else before.left,
                right = if (halves[1] != null) KbOwner.COMPANION else before.right,
                leftKind = if (halves[0] != null) kinds[0] else before.leftKind,
                rightKind = if (halves[1] != null) kinds[1] else before.rightKind,
            )
            val out = Dds.print(bytes, info, halves, tag(after))
            if (out.size != bytes.size) return KbFileResult(name, KbFileResult.REFUSED, "$name was left as it is: the new page came out the wrong size.")
            if (out.contentEquals(bytes)) return KbFileResult(name, KbFileResult.UNCHANGED)
            writeSafely(file, out)
            KbFileResult(name, KbFileResult.WRITTEN)
        } catch (e: Throwable) {
            KbFileResult(name, KbFileResult.REFUSED, why(e, file, at))
        }
    }

    /**
     * A half page picture scaled into the texture's half: [w]×[h] opaque ARGB (1024×2048 in a 2048-pixel file), on
     * white where the picture is transparent. A picture more than twice the size in either direction is halved in that
     * direction first, so the scaling never skips pixels — a landscape photo stretched onto the tall half shrinks a lot
     * across and little down, and is halved across only.
     */
    fun raster(img: BufferedImage, w: Int, h: Int): IntArray {
        val src = halvedFor(img, w, h)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        val px = out.getRGB(0, 0, w, h, null, 0, w)
        for (i in px.indices) px[i] = px[i] or (0xFF shl 24)
        return px
    }

    /**
     * [img] halved, each direction on its own, while it is at least twice [w] (or [h]) in that direction: what [raster]
     * scales from, and what a Picture half keeps while its page waits to be written (a few tens of MB, not the file's
     * full size). Each halving averages pairs of pixels, so nothing is skipped.
     */
    fun halvedFor(img: BufferedImage, w: Int, h: Int): BufferedImage {
        var src = img
        while (src.width >= 2 * w || src.height >= 2 * h) {
            val hw = if (src.width >= 2 * w) src.width / 2 else src.width
            val hh = if (src.height >= 2 * h) src.height / 2 else src.height
            val half = BufferedImage(hw, hh, BufferedImage.TYPE_INT_ARGB)
            val g = half.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, half.width, half.height, null)
            g.dispose()
            src = half
        }
        return src
    }

    /**
     * Puts Falcon BMS's own shipped page back into each page of [pages]: only where BMS ships a file of the same name and
     * the same size, or where the page file is empty or cut short (which the shipped copy then rebuilds).
     */
    @Synchronized
    fun putShipped(at: Place, pages: List<Int>): List<KbFileResult> = pages.map { n ->
        val name = pageName(n)
        val file = at.page(n)
        try {
            when {
                at.error != null -> KbFileResult(name, KbFileResult.REFUSED, at.error)
                file == null || !file.isFile -> KbFileResult(name, KbFileResult.REFUSED,
                    "Page $n ($name) does not exist in ${at.folderLabel ?: "this theater's kneeboard folder"}; BMS Companion never creates a page file.")
                else -> {
                    val src = at.shippedPage(n)
                    if (src == null) KbFileResult(name, KbFileResult.REFUSED, noShipped(at).removeSuffix(".") + " for page $n.")
                    else {
                        val good = src.readBytes()
                        val gi = Dds.header(good)
                        val cur = file.readBytes()
                        val ci = Dds.header(cur)
                        when {
                            !gi.readable -> KbFileResult(name, KbFileResult.REFUSED, "BMS's own copy of $name is damaged (${gi.unreadable}); nothing was changed.")
                            cur.contentEquals(good) -> KbFileResult(name, KbFileResult.UNCHANGED)
                            ci.cutShort || (ci.readable && ci.width == gi.width && ci.height == gi.height) ||
                                (!ci.readable && ci.width == gi.width && ci.height == gi.height) -> {
                                writeSafely(file, good)
                                val twinNote = at.twin(n)?.let { "Its KoreaObj_HiRes twin, which BMS shows instead, was left as it is." }
                                KbFileResult(name, KbFileResult.WRITTEN, twinNote)
                            }
                            else -> KbFileResult(name, KbFileResult.REFUSED,
                                "$name is ${ci.width}×${ci.height} and BMS's own copy is ${gi.width}×${gi.height}; it was left as it is.")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            KbFileResult(name, KbFileResult.REFUSED, why(e, file ?: File(name), at))
        }
    }

    /**
     * Falcon BMS's own page back into the halves [sides] (0 left, 1 right) of page [n] — what a switch of the Mission
     * section's mode does to the other mode's pages (`SwitchReset`). When every other half is BMS's already (or is put
     * back too), the whole shipped file is copied back byte for byte ([putShipped]); otherwise only those halves are
     * drawn from BMS's copy into the file, the other half's blocks kept and the file's format unchanged, and the tag
     * names those halves BMS's. Never creates a page file, never throws; the answer says what happened.
     */
    @Synchronized
    fun putShippedHalves(at: Place, n: Int, sides: Set<Int>): KbFileResult {
        val name = pageName(n)
        val file = at.page(n)
        return try {
            when {
                at.error != null -> KbFileResult(name, KbFileResult.REFUSED, at.error)
                file == null || !file.isFile -> KbFileResult(name, KbFileResult.REFUSED,
                    "Page $n ($name) does not exist in ${at.folderLabel ?: "this theater's kneeboard folder"}; BMS Companion never creates a page file.")
                sides.isEmpty() -> KbFileResult(name, KbFileResult.UNCHANGED)
                else -> {
                    val src = at.shippedPage(n) ?: return KbFileResult(name, KbFileResult.REFUSED, noShipped(at).removeSuffix(".") + " for page $n.")
                    val cur = file.readBytes()
                    val ci = Dds.header(cur)
                    val o = owners(file, ci, src)
                    val others = (0..1).filter { it !in sides }
                    if (others.all { (if (it == 0) o.left else o.right) == KbOwner.BMS }) return putShipped(at, listOf(n)).first()
                    val good = src.readBytes()
                    val gi = Dds.header(good)
                    ci.unwritable?.let { return KbFileResult(name, KbFileResult.REFUSED, "$name was left as it is: $it.") }
                    if (!gi.readable) return KbFileResult(name, KbFileResult.REFUSED, "BMS's own copy of $name is damaged (${gi.unreadable}); nothing was changed.")
                    if (gi.width != ci.width || gi.height != ci.height)
                        return KbFileResult(name, KbFileResult.REFUSED, "$name is ${ci.width}×${ci.height} and BMS's own copy is ${gi.width}×${gi.height}; it was left as it is.")
                    val hw = ci.width / 2
                    val halves = Array(2) { s ->
                        if (s in sides) Dds.decode(good, gi, 0, s * hw, 0, hw, ci.height).also { px -> for (i in px.indices) px[i] = px[i] or (0xFF shl 24) } else null
                    }
                    val after = Owners(
                        left = if (0 in sides) KbOwner.BMS else o.left, right = if (1 in sides) KbOwner.BMS else o.right,
                        leftKind = if (0 in sides) null else o.leftKind, rightKind = if (1 in sides) null else o.rightKind,
                    )
                    val out = Dds.print(cur, ci, halves, tag(after))
                    when {
                        out.size != cur.size -> KbFileResult(name, KbFileResult.REFUSED, "$name was left as it is: the page came out the wrong size.")
                        out.contentEquals(cur) -> KbFileResult(name, KbFileResult.UNCHANGED)
                        else -> {
                            writeSafely(file, out)
                            KbFileResult(name, KbFileResult.WRITTEN, at.twin(n)?.let { "Its KoreaObj_HiRes twin, which BMS shows instead, was left as it is." })
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            KbFileResult(name, KbFileResult.REFUSED, why(e, file ?: File(name), at))
        }
    }

    // ================================================================ pictures

    private val thumbs = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 64
    }

    /**
     * A JPEG of what one half of page [n] holds now ([side] 0 left, 1 right), [w] pixels wide at the page's visible
     * shape (2:3). Decoded from the smallest mip level that is still wide enough. The picture, or null and the reason.
     */
    fun thumb(at: Place, n: Int, side: Int, w: Int): Pair<ByteArray?, String?> {
        at.error?.let { return null to it }
        val file = at.page(n)
        if (file == null || !file.isFile) return null to "Page $n (${pageName(n)}) does not exist in this theater's kneeboard folder."
        val key = "${file.path}|${file.length()}|${file.lastModified()}|$side|$w"
        synchronized(thumbs) { thumbs[key]?.let { return it to null } }
        return try {
            val img = halfImage(file.readBytes(), side, w) ?: return null to "${file.name} cannot be shown: ${Dds.header(file.readBytes()).unreadable}."
            val jpeg = jpeg(img)
            synchronized(thumbs) { thumbs[key] = jpeg }
            jpeg to null
        } catch (e: Throwable) {
            null to why(e, file, at)
        }
    }

    /** One half of a page file as the pad shows it: [w] wide, at [PAGE_ASPECT]. Null when the file cannot be read. */
    fun halfImage(bytes: ByteArray, side: Int, w: Int): BufferedImage? {
        val info = Dds.header(bytes)
        if (!info.readable || info.width < 2) return null
        var level = 0
        while (level + 1 < info.levels && info.levelWidth(level + 1) / 2 >= w) level++
        val lw = info.levelWidth(level)
        val lh = info.levelHeight(level)
        val hw = lw / 2
        val px = Dds.decode(bytes, info, level, side * hw, 0, hw, lh)
        val src = Dds.toImage(px, hw, lh)
        val h = Math.round(w / PAGE_ASPECT)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    fun jpeg(img: BufferedImage, quality: Float = 0.85f): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        try {
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val p = writer.defaultWriteParam
                p.compressionMode = ImageWriteParam.MODE_EXPLICIT
                p.compressionQuality = quality
                writer.write(null, IIOImage(img, null, null), p)
            }
        } finally {
            writer.dispose()
        }
        return out.toByteArray()
    }

    // ================================================================ the disk

    /**
     * Writes beside the file first and moves it into place, so the move is the only moment the page changes and a
     * write that fails half way never leaves BMS half a texture. The file keeps its own name on disk.
     */
    private fun writeSafely(target: File, bytes: ByteArray) {
        val file = if (target.exists()) target.canonicalFile else target
        val tmp = File(file.parentFile, file.name + ".bmsc-new")
        try {
            java.nio.file.Files.write(tmp.toPath(), bytes)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** Whether another program holds [f] open so that it cannot be written (Falcon BMS in the cockpit, a viewer). */
    private fun inUse(f: File): Boolean = try {
        RandomAccessFile(f, "rw").use { }
        false
    } catch (e: Exception) {
        val m = e.message.orEmpty()
        m.contains("being used by another process", ignoreCase = true) || m.contains("locked", ignoreCase = true)
    }

    /** The sentence for what went wrong writing (or reading) [file]. */
    private fun why(e: Throwable, file: File, at: Place): String {
        val name = file.name
        val folder = at.folderLabel ?: file.parentFile?.name ?: "the kneeboard folder"
        val m = e.message.orEmpty()
        return when {
            m.contains("being used by another process", ignoreCase = true) || m.contains("locked a portion", ignoreCase = true) -> inUseSentence(name)
            m.contains("not enough space", ignoreCase = true) || m.contains("disk is full", ignoreCase = true) ->
                "There is not enough space on the disk to write $name; nothing was changed."
            e is java.nio.file.AccessDeniedException || e is java.nio.file.FileSystemException ||
                (e is java.io.FileNotFoundException && m.contains("denied", ignoreCase = true)) -> when {
                file.isFile && !file.canWrite() -> "$name is marked read-only, so it was left as it is. Clear \"Read-only\" in its Properties to print on it."
                file.isFile && inUse(file) -> inUseSentence(name)
                else -> "Windows would not let BMS Companion write in $folder, so $name was left as it is. Falcon BMS is usually " +
                    "installed where that needs administrator rights: start BMS Companion as administrator."
            }
            else -> "$name could not be written: ${m.ifBlank { e::class.java.simpleName }}"
        }
    }

    private fun inUseSentence(name: String) =
        "$name is in use by another program (Falcon BMS holds its pages while you are in the cockpit), so it was left as it is. " +
            "Print before you enter the cockpit, or leave it and print again."

    /** `Thr3ddatadir` in BMS's StringData (see [StringId]): only logged (U7). */
    private const val THR_3DDATADIR = 20
}
