package com.bmscompanion.desktop.bridge.command

import com.bmscompanion.desktop.bridge.DevGuard
import java.io.File

/**
 * The theater-wide campaign database files of a campaign folder, read and written by the Command section's Files page:
 * `Stations+Ils.dat` (each airbase's TACAN, radios, ILS and ATC sound fragments), `RadioMap.dat` (callsign →
 * frequencies), the TE editor's lists (`TePlanes.lst`, `TeUnits.lst`, `TeNavalUnits.lst`), `InvalidAcTypes.XML` (the
 * squadron classes a pilot may not pick), every `MissionData_*.xml` (the campaign planner's parameters per mission
 * type) and `CampObjData.XML` (each objective's name, class and exact position).
 *
 * Each file is a [Table] of rows and columns. **Nothing a file holds is lost**: every field of the file is a column
 * (the XML files' leaf elements, whatever they are), comments, banners, blank lines and the separators of a line are
 * kept, and a row that was not edited is written back byte for byte. Text is held as the file's bytes (one char a byte)
 * and shown through its encoding (ANSI for the `.dat`/`.lst` files, UTF-8 for the XML files).
 *
 * Writing follows the backup rule ([save]): the file on disk must still be the one read (else "read it again"), its
 * original is copied once into `BMS Companion Backup\Command\` beside it with a README ([CommandBackup.backupOnce]),
 * the new text is derived from the file as read, read back and checked, then moved over the file (temp + atomic move).
 * [restore] puts BMS's original back. Nothing throws: every answer that is not null is a sentence for the page.
 */
object CampaignDbFiles {
    enum class Kind(val title: String, val about: String) {
        STATIONS("Stations and ILS", "Each airbase's TACAN, tower/ground/approach/ATIS radios, ILS frequencies and the ATC voice fragments"),
        RADIO_MAP("Flight radios", "The UHF and VHF frequencies of each callsign and agency"),
        TE_PLANES("TE aircraft", "The aircraft the TE and Dogfight editors offer"),
        TE_UNITS("TE ground units", "The ground units the TE editor offers, by equipment side"),
        TE_NAVAL("TE naval units", "The task forces the TE editor offers, by equipment side"),
        INVALID_AC("Squadrons not selectable", "Squadron classes a pilot may not pick in the campaign or a TE"),
        MISSION_DATA("Mission data", "The campaign planner's parameters for each mission type, per team"),
        CAMP_OBJ("Objective data", "Each objective's name, class and exact position"),
    }

    /** A column: [numeric] decides alignment and sorting; [editable] false for a key that ties the file together. */
    class Column(val name: String, val numeric: Boolean = true, val editable: Boolean = true, val tip: String? = null)

    /** A row: [key] its place in the file as read (-1 for a row added on the page), its [cells] as shown. */
    class Row(val key: Int, val cells: List<String>) {
        fun with(col: Int, value: String): Row = Row(key, cells.toMutableList().also { while (it.size <= col) it += ""; it[col] = value })
        fun same(o: Row) = key == o.key && cells == o.cells
    }

    /** A file read: its columns, rows, the hash of the bytes read, whether rows can be added or deleted, what went wrong. */
    class Table internal constructor(
        val kind: Kind, val file: File, val columns: List<Column>, val rows: List<Row>, val hash: String,
        val canAddRows: Boolean, internal val layout: Layout?, val problems: List<String>,
    ) {
        fun col(name: String): Int = columns.indexOfFirst { it.name == name }
    }

    /** One file of a campaign folder for the page's list. */
    class Entry(val kind: Kind, val file: File, val exists: Boolean, val backedUp: Boolean, val differsFromOriginal: Boolean)

    // ---------------------------------------------------------------- listing

    /** The database files of campaign folder [dir], in the page's order (the fixed names first, then every MissionData). */
    fun list(dir: File): List<Entry> {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        fun find(name: String) = files.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val out = ArrayList<Entry>()
        fun add(kind: Kind, f: File?) {
            if (f == null) return
            val copy = CommandBackup.copyOf(dir, f.name)
            val differs = copy != null && runCatching { CommandFiles.sha256(copy) != CommandFiles.sha256(f) }.getOrDefault(false)
            out += Entry(kind, f, f.isFile, copy != null, differs)
        }
        add(Kind.STATIONS, find("Stations+Ils.dat"))
        add(Kind.RADIO_MAP, find("RadioMap.dat"))
        add(Kind.TE_PLANES, find("TePlanes.lst"))
        add(Kind.TE_UNITS, find("TeUnits.lst"))
        add(Kind.TE_NAVAL, find("TeNavalUnits.lst"))
        add(Kind.INVALID_AC, find("InvalidAcTypes.XML"))
        files.filter { Regex("(?i)MissionData_[^.]*\\.xml").matches(it.name) }.sortedBy { it.name.lowercase() }.forEach { add(Kind.MISSION_DATA, it) }
        add(Kind.CAMP_OBJ, find("CampObjData.XML"))
        return out
    }

    // ---------------------------------------------------------------- reading

    /** Reads [file] as a [kind] table. Never throws: a file that cannot be read is an empty table with its reason. */
    fun read(kind: Kind, file: File): Table {
        val bytes = try { file.readBytes() } catch (e: Throwable) {
            return Table(kind, file, emptyList(), emptyList(), "", false, null, listOf("${file.name} could not be read: ${CommandFiles.reason(e)}."))
        }
        return try { parse(kind, file, bytes) } catch (e: Throwable) {
            Table(kind, file, emptyList(), emptyList(), CommandFiles.sha256(bytes), false, null, listOf("${file.name} could not be understood: ${CommandFiles.reason(e)}."))
        }
    }

    internal fun parse(kind: Kind, file: File, bytes: ByteArray): Table {
        val text = String(bytes, Charsets.ISO_8859_1)
        val layout: Layout = when (kind) {
            Kind.STATIONS -> LineLayout(text, LineSpec.STATIONS)
            Kind.RADIO_MAP -> LineLayout(text, LineSpec.RADIO)
            Kind.TE_PLANES -> LineLayout(text, LineSpec.PLANES)
            Kind.TE_UNITS, Kind.TE_NAVAL -> LineLayout(text, LineSpec.UNITS)
            Kind.INVALID_AC -> InvalidLayout(text)
            Kind.MISSION_DATA -> XmlLayout(text, XmlSpec.MISSION_DATA)
            Kind.CAMP_OBJ -> XmlLayout(text, XmlSpec.CAMP_OBJ)
        }
        return Table(kind, file, layout.columns, layout.rows, CommandFiles.sha256(bytes), layout.canAddRows, layout, layout.problems)
    }

    // ---------------------------------------------------------------- writing

    /** The bytes [rows] would make of [table]'s file (for the checks and [save]); null with the reason when they cannot. */
    fun render(table: Table, rows: List<Row>): Pair<ByteArray?, String?> = try {
        val l = table.layout ?: return null to "the file was not read"
        l.write(rows).toByteArray(Charsets.ISO_8859_1) to null
    } catch (e: Throwable) {
        null to CommandFiles.reason(e)
    }

    /**
     * Writes [rows] into [table]'s file under the backup rule. Answers the reason it did not (null = written), and the
     * file read again when it was written.
     */
    fun save(table: Table, rows: List<Row>): Pair<String?, Table?> {
        val f = table.file
        return try {
            val folder = f.absoluteFile.parentFile ?: return "${f.name} has no folder." to null
            DevGuard.refusal(folder.path)?.let { return it to null }
            if (table.layout == null || table.problems.isNotEmpty()) return "${f.name} was not read whole, so it is not written." to null
            val disk = runCatching { f.readBytes() }.getOrElse { return "${f.name} could not be read before writing: ${CommandFiles.reason(it)}." to null }
            if (CommandFiles.sha256(disk) != table.hash) return "${f.name} changed on disk since the page read it. Read it again (your changes are kept on the page until you do)." to null
            val (bytes, why) = render(table, rows)
            if (bytes == null) return "${f.name} could not be written: $why." to null
            // read back what would be written: every row as the page has it, nothing else changed
            val back = parse(table.kind, f, bytes)
            if (back.problems.isNotEmpty()) return "The new ${f.name} would not read back: ${back.problems.first()}" to null
            val want = rows.map { it.cells.map { c -> c.trim() } }
            val got = back.rows.map { r -> r.cells.map { c -> c.trim() } }
            fun same(a: String, b: String) = a == b || (a.toLongOrNull() != null && a.toLongOrNull() == b.toLongOrNull())
            if (want.size != got.size || want.indices.any { i -> want[i].indices.any { j -> !same(want[i][j], got[i].getOrElse(j) { "" }) } })
                return "The new ${f.name} does not read back as the page has it, so it was not written." to null
            if (bytes.contentEquals(disk)) return null to table
            CommandBackup.backupOnce(f, "campaign database file, before the Command section's first write")?.let { return "${f.name} was not written: its original could not be backed up first ($it)." to null }
            CommandFiles.writeAtomic(f, bytes)?.let { return "${f.name} could not be written: $it." to null }
            null to read(table.kind, f)
        } catch (e: Throwable) {
            "${f.name} could not be written: ${CommandFiles.reason(e)}." to null
        }
    }

    /** Puts BMS's original of [file] back from the backup beside it. Null when done. */
    fun restore(file: File): String? = try {
        val folder = file.absoluteFile.parentFile ?: return "${file.name} has no folder."
        CommandBackup.restore(folder, file.name)
    } catch (e: Throwable) {
        "${file.name} could not be put back: ${CommandFiles.reason(e)}."
    }

    // ---------------------------------------------------------------- encodings

    /** The file's bytes (held one char a byte) as text, and back. */
    internal fun shownAnsi(raw: String): String = String(raw.toByteArray(Charsets.ISO_8859_1), WIN1252)
    internal fun rawAnsi(shown: String): String = String(shown.toByteArray(WIN1252), Charsets.ISO_8859_1)
    internal fun shownUtf8(raw: String): String = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    internal fun rawUtf8(shown: String): String = String(shown.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    private val WIN1252 = runCatching { charset("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    // ---------------------------------------------------------------- layouts

    internal interface Layout {
        val columns: List<Column>
        val rows: List<Row>
        val canAddRows: Boolean
        val problems: List<String>
        fun write(rows: List<Row>): String
    }

    /** Lines with their terminators. */
    private fun lines(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var j = text.indexOf('\n', i)
            j = if (j < 0) text.length else j + 1
            out += text.substring(i, j)
            i = j
        }
        return out
    }

    private fun eolOf(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

    /** How a line file is laid out: its comment marks, how a row splits, the comment lines a row owns above it. */
    internal class LineSpec(
        val comment: List<String>, val comma: Boolean, val lead: Int, val leadMark: String,
        val leadColumn: String?, val dataColumns: List<Column>, val skipLeadOfFirst: Boolean = false,
    ) {
        companion object {
            private fun n(name: String, tip: String? = null) = Column(name, true, true, tip)
            val STATIONS = LineSpec(
                listOf("#"), comma = false, lead = 2, leadMark = "#", leadColumn = "Comment",
                dataColumns = listOf(
                    Column("CampId", true, false, "The airbase's campaign id (ties the line to the objective)"),
                    n("TACAN ch"), Column("Band", false), n("Callsign", "The TACAN callsign: a pointer into BMS's callsign list"),
                    n("Range", "TACAN range, nm"), n("TACAN type"), n("Tower UHF", "kHz"), n("Tower VHF", "kHz"),
                    n("ILS 1", "10 kHz (10810 = 108.10)"), n("ILS 2", "10 kHz"), n("ILS 3", "10 kHz"), n("ILS 4", "10 kHz"),
                    n("Ops UHF", "kHz"), n("Ground UHF", "kHz"), n("Approach UHF", "kHz"), n("LSO UHF", "kHz"), n("ATIS VHF", "kHz"),
                    n("Tower frag", "The tower's voice fragment"), n("Ground frag"), n("Approach frag"), n("ATIS frag"),
                ),
            )
            val RADIO = LineSpec(
                listOf("//", "#"), comma = true, lead = 0, leadMark = "//", leadColumn = null,
                dataColumns = listOf(Column("Callsign", false), n("UHF", "kHz, or NONE"), n("VHF", "kHz, or NONE"), n("UHF backup", "kHz, or NONE")),
            )
            val PLANES = LineSpec(
                listOf("//"), comma = false, lead = 1, leadMark = "//", leadColumn = "Name", skipLeadOfFirst = true,
                dataColumns = listOf(n("Type"), n("SubType"), n("Specific"), n("Unit SubType"), n("ID", "The Dogfight/UI id"), n("Text", "Text id"), n("Icon")),
            )
            val UNITS = LineSpec(
                listOf("//"), comma = false, lead = 0, leadMark = "//", leadColumn = null,
                dataColumns = listOf(n("Side", "Equipment side"), n("SubType"), n("Specific")),
            )
        }
    }

    /** One data line split: leading blanks, tokens with the separators between them, and the rest (blanks, end of line). */
    private class Split(val head: String, val tokens: List<String>, val seps: List<String>, val tail: String) {
        fun join(values: List<String>, defaultSep: String): String = buildString {
            append(head)
            values.forEachIndexed { i, v ->
                if (i > 0) append(seps.getOrNull(i - 1) ?: seps.lastOrNull() ?: defaultSep)
                append(v)
            }
            append(tail)
        }

        companion object {
            fun of(line: String, comma: Boolean): Split {
                var end = line.length
                while (end > 0 && line[end - 1].isWhitespace()) end--
                var start = 0
                while (start < end && line[start].isWhitespace()) start++
                val body = line.substring(start, end)
                val sepRx = if (comma) Regex("\\s*,\\s*") else Regex("\\s+")
                val tokens = ArrayList<String>()
                val seps = ArrayList<String>()
                var p = 0
                for (m in sepRx.findAll(body)) {
                    tokens += body.substring(p, m.range.first)
                    seps += m.value
                    p = m.range.last + 1
                }
                tokens += body.substring(p)
                return Split(line.substring(0, start), tokens, seps, line.substring(end))
            }
        }
    }

    /** A line file: rows each own their comment lines above ([LineSpec.lead]) and keep every line after them as it was. */
    private class LineLayout(val text: String, val spec: LineSpec) : Layout {
        private val all = lines(text)
        private val eol = eolOf(text)
        /** per row: the lead lines' indices, the data line's, and the lines after it up to the next row's lead */
        private class Place(val lead: List<Int>, val data: Int, val trailEnd: Int)
        private val places = ArrayList<Place>()
        private var prefixEnd = 0
        override val problems = ArrayList<String>()
        override val canAddRows = true
        override val columns: List<Column> = (listOfNotNull(spec.leadColumn?.let { Column(it, false) }) + spec.dataColumns)
        override val rows: List<Row>

        private fun isComment(l: String): Boolean { val t = l.trimStart(); return spec.comment.any { t.startsWith(it) } }
        private fun isData(l: String): Boolean = l.isNotBlank() && !isComment(l)

        init {
            val data = all.indices.filter { isData(all[it]) }
            data.forEachIndexed { k, d ->
                val lead = ArrayList<Int>()
                if (spec.lead > 0 && !(spec.skipLeadOfFirst && k == 0)) {
                    val floor = if (k == 0) 0 else data[k - 1] + 1
                    var i = d - 1
                    while (i >= floor && lead.size < spec.lead && all[i].trimStart().startsWith(spec.leadMark)) { lead.add(0, i); i-- }
                    if (lead.size < spec.lead) lead.clear()
                }
                places += Place(lead, d, 0)
            }
            for (k in places.indices) {
                val next = if (k + 1 < places.size) (places[k + 1].lead.firstOrNull() ?: places[k + 1].data) else all.size
                places[k] = Place(places[k].lead, places[k].data, next)
            }
            prefixEnd = places.firstOrNull()?.let { it.lead.firstOrNull() ?: it.data } ?: all.size
            rows = places.mapIndexed { k, p -> Row(k, cellsOf(p)) }
        }

        private fun leadText(p: Place): String {
            val l = p.lead.lastOrNull()?.let { all[it] } ?: return ""
            return shownAnsi(l.trimStart().removePrefix(spec.leadMark).trim())
        }

        private fun cellsOf(p: Place): List<String> {
            val toks = Split.of(all[p.data], spec.comma).tokens.map { shownAnsi(it) }
            val n = spec.dataColumns.size
            val data = if (toks.size <= n) toks + List(n - toks.size) { "" } else toks.take(n - 1) + toks.drop(n - 1).joinToString(if (spec.comma) ", " else " ")
            return listOfNotNull(if (spec.leadColumn != null) leadText(p) else null) + data
        }

        private fun dataOf(r: Row): List<String> {
            val d = if (spec.leadColumn != null) r.cells.drop(1) else r.cells
            // trailing empty fields of a row that had fewer are not written
            var n = d.size
            while (n > 0 && d[n - 1].isEmpty()) n--
            return d.take(n).map { rawAnsi(it.trim()) }
        }

        private fun leadLines(template: Place?, r: Row): List<String> {
            if (spec.lead == 0 || spec.leadColumn == null) return emptyList()
            val text = rawAnsi(r.cells.firstOrNull().orEmpty().trim())
            val mark = spec.leadMark
            return when (spec.lead) {
                1 -> listOf("$mark $text$eol")
                else -> List(spec.lead - 1) { "$mark $eol" } + "$mark $text$eol"
            }.let { new ->
                if (template == null || template.lead.isEmpty()) new
                else {
                    // keep the row's own comment lines; only the last (its name) follows the page
                    val old = template.lead.map { all[it] }
                    val lastOld = old.last()
                    val shownOld = shownAnsi(lastOld.trimStart().removePrefix(mark).trim())
                    if (shownOld == r.cells.first().trim()) old else old.dropLast(1) + (lastOld.substring(0, lastOld.indexOf(mark) + mark.length) + " " + text + eol)
                }
            }
        }

        private fun dataLine(template: Place?, r: Row, like: Place?): String {
            val values = dataOf(r)
            if (!spec.comma) values.forEachIndexed { i, v ->
                require(v.isNotEmpty()) { "${spec.dataColumns.getOrNull(i)?.name ?: "a field"} cannot be left empty in a line whose fields are split by blanks (type 0)" }
                require(v.none { it.isWhitespace() }) { "${spec.dataColumns.getOrNull(i)?.name ?: "a field"} cannot hold a blank (the line's fields are split by blanks)" }
            }
            if (template != null) {
                val s = Split.of(all[template.data], spec.comma)
                if (s.tokens == values) return all[template.data]
                // a number the file writes zero-padded ("021", a TACAN channel) keeps its width
                val padded = values.mapIndexed { i, v ->
                    val old = s.tokens.getOrNull(i) ?: return@mapIndexed v
                    if (old.length > 1 && old[0] == '0' && old.all { it.isDigit() } && v.all { it.isDigit() } && v.length < old.length) v.padStart(old.length, '0') else v
                }
                return s.join(padded, if (spec.comma) ", " else "\t")
            }
            val model = like?.let { Split.of(all[it.data], spec.comma) }
            val tail = model?.tail?.takeIf { it.endsWith("\n") } ?: eol
            return (model?.let { Split(it.head, it.tokens, it.seps, tail) } ?: Split("", emptyList(), emptyList(), eol)).join(values, if (spec.comma) ", " else "\t")
        }

        override fun write(rows: List<Row>): String {
            val out = StringBuilder()
            for (i in 0 until prefixEnd) out.append(all[i])
            var like: Place? = places.lastOrNull()
            val kept = rows.mapNotNull { r -> r.key.takeIf { it >= 0 } }.toSet()
            var next = 0
            // the lines that followed a row now deleted (banners, blank lines) stay where they were
            fun trailsOfDeletedBefore(k: Int) {
                while (next < k) {
                    if (next !in kept) places[next].let { p -> for (i in p.data + 1 until p.trailEnd) out.append(all[i]) }
                    next++
                }
            }
            for (r in rows) {
                val p = places.getOrNull(r.key)?.takeIf { r.key >= 0 }
                if (p != null) { trailsOfDeletedBefore(r.key); next = r.key + 1; like = p }
                if (p != null && r.same(this.rows[r.key])) {
                    for (i in (p.lead.firstOrNull() ?: p.data) until p.trailEnd) out.append(all[i])
                    continue
                }
                leadLines(p, r).forEach { out.append(it) }
                out.append(dataLine(p, r, like).let { if (it.endsWith("\n")) it else it + eol })
                if (p != null) for (i in p.data + 1 until p.trailEnd) out.append(all[i])
            }
            trailsOfDeletedBefore(places.size)
            if (places.isEmpty()) for (i in prefixEnd until all.size) out.append(all[i])
            return out.toString()
        }
    }

    /** XML entities in a leaf's text. */
    private fun xmlUnescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun xmlAttrEscape(s: String) = xmlEscape(s).replace("\"", "&quot;")

    /** Which XML file: the row elements (with their key attribute) and, for MissionData, the team around them. */
    internal enum class XmlSpec { MISSION_DATA, CAMP_OBJ }

    /**
     * An XML file whose rows are elements of leaf elements: every leaf is a column (in the order the file first has
     * them), edited in place in the text, so the declaration, the byte-order mark, the indentation and every element
     * the page does not change stay exactly as they were. Rows are neither added nor deleted.
     */
    private class XmlLayout(val text: String, val spec: XmlSpec) : Layout {
        /** a value's place in the text */
        private class Span(val start: Int, val end: Int)
        private val spans = ArrayList<List<Span?>>()
        override val problems = ArrayList<String>()
        override val canAddRows = false
        override val columns: List<Column>
        override val rows: List<Row>
        private val keyCols: Int

        init {
            val leafRx = Regex("<([A-Za-z_][\\w.-]*)>([^<]*)</\\1>")
            val found = ArrayList<Pair<List<String>, List<Triple<String, String, Span>>>>()
            when (spec) {
                XmlSpec.MISSION_DATA -> {
                    val teamRx = Regex("<Team\\s+Num=\"(-?\\d+)\"\\s*>")
                    for (tm in teamRx.findAll(text)) {
                        val tEnd = text.indexOf("</Team>", tm.range.last).let { if (it < 0) text.length else it }
                        val body = tm.range.last + 1
                        val elRx = Regex("<([A-Za-z_][\\w.-]*)>\\s*<")
                        var p = body
                        while (p < tEnd) {
                            val m = elRx.find(text, p) ?: break
                            if (m.range.first >= tEnd) break
                            val tag = m.groupValues[1]
                            val close = text.indexOf("</$tag>", m.range.last)
                            if (close < 0 || close > tEnd) { problems += "<$tag> of team ${tm.groupValues[1]} is not closed."; break }
                            val leaves = leafRx.findAll(text, m.range.first + tag.length + 2).takeWhile { it.range.last < close }
                                .map { l -> Triple(l.groupValues[1], l.groupValues[2], Span(l.groups[2]!!.range.first, l.groups[2]!!.range.last + 1)) }.toList()
                            found += listOf(tm.groupValues[1], tag) to leaves
                            p = close + tag.length + 3
                        }
                    }
                }
                XmlSpec.CAMP_OBJ -> {
                    val objRx = Regex("<CampObj\\s+CampId=\"([^\"]*)\"\\s*>")
                    for (om in objRx.findAll(text)) {
                        val close = text.indexOf("</CampObj>", om.range.last)
                        if (close < 0) { problems += "<CampObj CampId=\"${om.groupValues[1]}\"> is not closed."; break }
                        val leaves = leafRx.findAll(text, om.range.last + 1).takeWhile { it.range.last < close }
                            .map { l -> Triple(l.groupValues[1], l.groupValues[2], Span(l.groups[2]!!.range.first, l.groups[2]!!.range.last + 1)) }.toList()
                        found += listOf(om.groupValues[1]) to leaves
                    }
                }
            }
            val tags = LinkedHashSet<String>()
            found.forEach { (_, leaves) -> leaves.forEach { tags += it.first } }
            val keyNames = if (spec == XmlSpec.MISSION_DATA) listOf("Team", "Mission type") else listOf("CampId")
            keyCols = keyNames.size
            columns = keyNames.map { Column(it, it != "Mission type", false) } +
                tags.map { Column(it, numeric = it != "CampName") }
            rows = found.mapIndexed { i, (keys, leaves) ->
                val byTag = leaves.associateBy { it.first }
                spans += tags.map { t -> byTag[t]?.third }
                Row(i, keys + tags.map { t -> byTag[t]?.let { xmlUnescape(shownUtf8(it.second)) } ?: "" })
            }
            if (found.isEmpty()) problems += "No ${if (spec == XmlSpec.MISSION_DATA) "<Team>" else "<CampObj>"} records were found."
        }

        override fun write(rows: List<Row>): String {
            require(rows.size == this.rows.size && rows.indices.all { rows[it].key == it }) { "rows cannot be added to, deleted from or moved in this file" }
            val edits = ArrayList<Pair<Span, String>>()
            rows.forEachIndexed { i, r ->
                val old = this.rows[i]
                for (c in keyCols until columns.size) {
                    val v = r.cells.getOrElse(c) { "" }
                    if (v == old.cells[c]) continue
                    val span = spans[i][c - keyCols] ?: error("${columns[c].name} is not in row ${i + 1} of the file, so it cannot be set")
                    edits += span to rawUtf8(xmlEscape(v.trim()))
                }
            }
            val sb = StringBuilder(text)
            edits.sortedByDescending { it.first.start }.forEach { (s, v) -> sb.replace(s.start, s.end, v) }
            return sb.toString()
        }
    }

    /**
     * `InvalidAcTypes.XML`: a `<Sqd Type="name">` per squadron class with its `<CtIndex>` — 4.38.1 ships it empty
     * (`<InvalidAcTypes/>`). Rows are (Type, CtIndex); the file is written whole from them, its declaration kept.
     */
    private class InvalidLayout(val text: String) : Layout {
        private val eol = eolOf(text)
        override val problems = ArrayList<String>()
        override val canAddRows = true
        override val columns = listOf(Column("Type", false, true, "The squadron's name as the file gives it"), Column("CtIndex", true, true, "The squadron's class-table index"))
        override val rows: List<Row>
        private val declaration: String

        init {
            val decl = Regex("^\\uFEFF?(\\xEF\\xBB\\xBF)?<\\?xml[^>]*\\?>\\s*").find(text)
            declaration = decl?.value?.trimEnd()?.plus(eol) ?: ""
            if (!text.contains("<InvalidAcTypes")) problems += "There is no <InvalidAcTypes> element."
            val out = ArrayList<Row>()
            val sqdRx = Regex("<Sqd\\s+Type=\"([^\"]*)\"\\s*>(.*?)</Sqd>", RegexOption.DOT_MATCHES_ALL)
            for (m in sqdRx.findAll(text)) {
                val type = xmlUnescape(shownUtf8(m.groupValues[1]))
                val idx = Regex("<CtIndex>([^<]*)</CtIndex>").findAll(m.groupValues[2]).map { it.groupValues[1].trim() }.toList()
                if (idx.isEmpty()) out += Row(out.size, listOf(type, "")) else idx.forEach { out += Row(out.size, listOf(type, it)) }
            }
            rows = out
        }

        override fun write(rows: List<Row>): String = if (rows.size == this.rows.size && rows.indices.all { rows[it].same(this.rows[it]) }) text else buildString {
            append(declaration)
            val kept = rows.filter { it.cells.getOrElse(1) { "" }.isNotBlank() || it.cells.getOrElse(0) { "" }.isNotBlank() }
            if (kept.isEmpty()) { append("<InvalidAcTypes/>").append(eol); return@buildString }
            append("<InvalidAcTypes>").append(eol)
            for (r in kept) {
                val t = rawUtf8(xmlAttrEscape(r.cells.getOrElse(0) { "" }.trim()))
                val c = r.cells.getOrElse(1) { "" }.trim()
                require(c.isEmpty() || c.toIntOrNull() != null) { "CtIndex \"$c\" is not a whole number" }
                append("    <Sqd Type=\"").append(t).append("\">").append(eol)
                if (c.isNotEmpty()) append("        <CtIndex>").append(c).append("</CtIndex>").append(eol)
                append("    </Sqd>").append(eol)
            }
            append("</InvalidAcTypes>").append(eol)
        }
    }

    // ---------------------------------------------------------------- the checks of a value

    /** Why a frequency of [col] (a Stations or RadioMap column) is out of BMS's bands, null when it is fine or empty. */
    fun frequencyProblem(col: String, value: String): String? {
        if (!(col.startsWith("ILS ") || col.contains("UHF") || col.contains("VHF"))) return null
        val v = value.trim()
        if (v.isEmpty() || v == "0" || v.equals("NONE", ignoreCase = true)) return null
        val n = v.toIntOrNull() ?: return "not a number"
        return when {
            col.startsWith("ILS") -> if (n !in 10800..11195 || n % 5 != 0) "an ILS goes from 10800 (108.00) to 11195 in steps of 5 (50 kHz)" else null
            col.contains("VHF") -> if (!(n in 118000..143975 || n in 30000..87975) || n % 25 != 0) "VHF goes from 118.000 to 143.975 MHz (or 30-88 FM) in 25 kHz steps" else null
            col.contains("UHF") -> if (n !in 225000..399975 || n % 25 != 0) "UHF goes from 225.000 to 399.975 MHz in 25 kHz steps" else null
            else -> null
        }
    }

    /**
     * Which runway end each of a station's four ILS slots serves, as Falcon BMS works it out (the sim's rule, the
     * airfield data's `ilsEnds`): within each RunwayNumber q (at most the objective class's runway count, held to 2),
     * the runway headers (point header type 1) in PHD order; slot 2q is runway q's last header, slot 2q + 1 its first.
     * Answers slot → (runway number, the header's course in degrees); a runway with one header serves none.
     */
    fun ilsSlots(tables: ClassTablesMore.Tables, ocd: Int): Map<Int, Pair<Int, Float>> {
        val out = HashMap<Int, Pair<Int, Float>>()
        val count = (tables.ocd[ocd]?.dataRate ?: 2).let { if (it > 0) it else 2 }.coerceAtMost(2)
        val headers = tables.pointHeaders(ocd).filter { it.type == 1 }
        for (q in 0 until count) {
            val list = headers.filter { it.runwayNumber == q }
            if (list.size < 2) continue
            out[2 * q] = q to list.last().data
            out[2 * q + 1] = q to list.first().data
        }
        return out
    }
}
