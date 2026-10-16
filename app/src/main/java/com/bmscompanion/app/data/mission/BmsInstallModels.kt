package com.bmscompanion.app.data.mission

import kotlinx.serialization.Serializable

/**
 * The Falcon BMS folders the PC knows (1.4.1, `GET /api/bms/installs`; docs/PROTOCOL.md, "Falcon BMS installs"): every
 * install the registry names (each `Benchmark Sims\Falcon BMS 4.x` key, 32- and 64-bit views, its folder existing) and
 * every folder the pilot added, one of them **active** — the one everything in the program reads. Switching is one tap
 * on any device's Setup page or the PC's server page (`POST /api/bms/installs/active?id=`); a device names an install
 * by its [BmsInstallEntry.id], never by a path, except when it adds a folder.
 *
 * Nothing is adapted to an older Falcon BMS: a folder of another version is read as any folder is, and what does not
 * work with it says so in a sentence, as it would for a wrong folder.
 */
@Serializable
data class BmsInstallList(
    val installs: List<BmsInstallEntry> = emptyList(),
    /** the [BmsInstallEntry.id] in use now; null when no Falcon BMS folder is known */
    val active: String? = null,
    /** true while no install is chosen: the registry's newest install is used, as before 1.4.1 */
    val followsRegistry: Boolean = true,
    /** what the last change did, in a sentence (switched, added, removed) */
    val message: String? = null,
    /** why the last change was refused, in a sentence */
    val error: String? = null,
)

@Serializable
data class BmsInstallEntry(
    /** "r:<registry key>" for a registry install, "a:<hex>" for a folder the pilot added */
    val id: String = "",
    /** the pilot's label, else the registry key's version ("Falcon BMS 4.38"), else the folder's name */
    val name: String = "",
    val path: String = "",
    /** the registry key that names this folder ("Falcon BMS 4.38"); null for a folder the pilot added */
    val registryKey: String? = null,
    /** the pilot's own name for it, when one was given */
    val label: String? = null,
    /** in the pilot's list (Add folder…): it can be removed; a registry install cannot */
    val added: Boolean = false,
    /** the registry's newest install: the one used while none is chosen */
    val registryDefault: Boolean = false,
    val exists: Boolean = false,
    /** holds `Falcon BMS.exe` (at its top or in `Bin\x64`) or `Data\TerrData` */
    val looksLikeBms: Boolean = false,
    /** the folder everything is read from now */
    val active: Boolean = false,
    /** the registry key's current theater (`curTheater`), for a registry install */
    val theater: String? = null,
)
