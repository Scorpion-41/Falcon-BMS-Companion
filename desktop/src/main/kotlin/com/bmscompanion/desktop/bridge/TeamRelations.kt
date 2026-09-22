package com.bmscompanion.desktop.bridge

import java.io.File

/**
 * Who is on whose side, as the campaign itself records it.
 *
 * The Tacview stream cannot answer that. What BMS writes into its `Coalition` property is the **country** — "Hellas",
 * "U.S.", "Albania" — and each country gets its own colour, so two air forces fighting side by side arrive as two
 * different coalitions. Comparing that string to your own made every allied aircraft of another nation hostile on
 * the map: an American Hornet escorting a Greek package was drawn as an enemy.
 *
 * The campaign knows better. Its team part (`.tea` in the `.cam`/`.tac`) holds one record per team, and each record
 * carries that team's stance toward every other: allied, friendly, neutral, hostile or at war. A team record is:
 *
 *     +0   id (8 bytes)          +10  team number        +11  controlling team
 *     +8   entity type (2)       +12  flags (2)          +14  member countries (8 bytes, 1 = belongs)
 *     +22  stance toward each of the 8 teams (8 × 16 bit): 0 none, 1 allied, 2 friendly, 3 neutral, 4 hostile, 5 war
 *
 *     +453 the team's name, NUL-terminated in a 20-byte field — the very string Tacview writes as the coalition
 *
 * (between the stances and the name: the pilot roster range, experience, and the team's stock and statistics). Records
 * are found by what they must look like rather than walked field by field, the same way the flight records are:
 * each carries the team entity type (learned from the first record), the team numbers come in order, and every stance
 * is one of the six values. The whole table is only
 * believed when exactly as many records are found as the part says it holds, in team order; otherwise nothing is
 * returned and the map falls back to comparing names, which is no worse than before.
 *
 * Read-only, like everything that opens the campaign files.
 */
internal object TeamRelations {

    /** The stances Falcon uses, in its own numbering. */
    const val NONE = 0
    const val ALLIED = 1
    const val FRIENDLY = 2
    const val NEUTRAL = 3
    const val HOSTILE = 4
    const val WAR = 5

    /** How many teams a campaign has, and so how long the member and stance arrays are. */
    private const val TEAMS = 8

    private const val STANCE_AT = 22
    private const val NAME_AT = 453
    private const val NAME_FIELD = 20

    data class Team(val number: Int, val name: String, val stance: List<Int>)

    /** One side's view of another, or null when the campaign does not say. */
    fun stance(teams: List<Team>, from: String?, toward: String?): Int? {
        if (from.isNullOrBlank() || toward.isNullOrBlank()) return null
        val a = teams.firstOrNull { it.name.isNotEmpty() && it.name.equals(from.trim(), true) } ?: return null
        val b = teams.firstOrNull { it.name.isNotEmpty() && it.name.equals(toward.trim(), true) } ?: return null
        return a.stance.getOrNull(b.number)
    }

    private var cacheKey: String? = null
    private var cached: List<Team> = emptyList()
    private var lookedAt = 0L
    private var lookedFor: String? = null

    /** How often the save is looked for again. Finding it walks the theater folders; the picture asks 4 times a second. */
    private const val RELOOK_MS = 15_000L

    /** The teams of the campaign BMS is flying, read again only when the save changes. */
    @Synchronized
    fun current(install: BmsInstall, theater: String?): List<Team> {
        val now = System.currentTimeMillis()
        if (theater == lookedFor && now - lookedAt < RELOOK_MS) return cached
        lookedAt = now
        lookedFor = theater
        val source = PlannedRoutes.sourceFor(install, theater) ?: return emptyList<Team>().also { cached = it; cacheKey = null }
        val key = "${source.file.path}|${source.file.lastModified()}|${source.file.length()}"
        if (key == cacheKey) return cached
        val teams = runCatching { read(source.file) }.getOrDefault(emptyList())
        cacheKey = key
        cached = teams
        return teams
    }

    fun read(file: File): List<Team> {
        val blob = file.readBytes()
        val part = MissionArchive.parts(blob).firstOrNull { it.name.endsWith(".tea", true) } ?: return emptyList()
        return parse(blob.copyOfRange(part.at, part.at + part.length))
    }

    fun parse(b: ByteArray): List<Team> {
        if (b.size < 2 + STANCE_AT + TEAMS * 2) return emptyList()
        val count = short(b, 0)
        if (count <= 0 || count > TEAMS) return emptyList()
        // The first record starts right after the count, and its entity type is the type every team record carries.
        // Learning it here rather than writing 0x0375 into the code keeps a future BMS that renumbers it working.
        val type = short(b, 2 + 8)

        val starts = ArrayList<Int>()
        var p = 2
        while (p + STANCE_AT + TEAMS * 2 <= b.size && starts.size < count) {
            if (looksLikeTeam(b, p, starts.size, type)) {
                starts += p
                p += STANCE_AT + TEAMS * 2
            } else p++
        }
        // the whole table or none of it: a half-read one would put somebody on the wrong side
        if (starts.size != count) return emptyList()

        return starts.mapIndexed { i, at ->
            val end = starts.getOrNull(i + 1) ?: b.size
            Team(
                number = b[at + 10].toInt() and 0xFF,
                name = nameAt(b, at + NAME_AT, end) ?: return emptyList(),
                stance = (0 until TEAMS).map { t -> short(b, at + STANCE_AT + t * 2) },
            )
        }
    }

    /**
     * Team [expected] starts at [p] if it carries the team entity type, its number is the next one, the team that
     * controls it is a team, and every stance is one of the six values.
     *
     * Each of the checks this used to make on top of that turned out false somewhere, and each one cost a whole theater
     * its table — twelve saves across nine theaters were read to find them:
     * - *allied with itself*: LHTO's N.Macedonia is neutral toward itself, and Hellas WCP's first team has no
     *   relations with anyone, itself included;
     * - *controls itself, and lists itself as a member*: in Korea the U.S. team is controlled by ROK, whose member
     *   list holds the U.S. country — which is also why every allied unit there, Abrams tanks included, reaches the
     *   Tacview stream as "ROK";
     * - *has a name*: the Falklands leaves three teams unnamed.
     */
    private fun looksLikeTeam(b: ByteArray, p: Int, expected: Int, type: Int): Boolean {
        if (short(b, p + 8) != type) return false
        val who = b[p + 10].toInt() and 0xFF
        val cteam = b[p + 11].toInt() and 0xFF
        if (who != expected || cteam >= TEAMS) return false
        return (0 until TEAMS).all { t -> short(b, p + STANCE_AT + t * 2) in NONE..WAR }
    }

    /**
     * The team's name, from its field, or "" for a team that has none. Measured, not assumed: 453 bytes into the
     * record in every 4.38 save read, from Korea to the Falklands. Bytes there that are not a name — a field moved by a
     * future BMS — give null, and the whole table with it, so the map falls back rather than guessing.
     */
    private fun nameAt(b: ByteArray, p: Int, until: Int): String? {
        if (p + NAME_FIELD > minOf(until, b.size)) return null
        var e = p
        while (e < p + NAME_FIELD && b[e].toInt() != 0) {
            if (!(b[e].toInt() and 0xFF).isNameChar()) return null
            e++
        }
        return String(b, p, e - p, Charsets.ISO_8859_1).trim()
    }

    private fun Int.isNameChar() = this in 32..126 || this in 0xC0..0xFF

    private fun short(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
}
