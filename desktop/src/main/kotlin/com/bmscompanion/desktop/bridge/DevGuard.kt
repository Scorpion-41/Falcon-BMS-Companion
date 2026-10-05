package com.bmscompanion.desktop.bridge

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * Keeps developer runs away from the real Falcon BMS install.
 *
 * A developer check drives the same routes a pilot's buttons do — Save to DTC, Generate kneeboards, the Weather and
 * Config pages, Upd Kneeboard — and a check whose settings pointed at the real install once wrote into it (a
 * cartridge's target steerpoints were zeroed by a test's Clear and Save). So while a check runs, every route that
 * writes into a BMS folder first asks [refusal], and refuses unless the folder it would write is plainly a copy:
 *
 * - neither the folder nor any folder above it holds `Falcon BMS.exe` (at its top or in `Bin\x64`/`Bin\x86`);
 * - it is not, is not inside, and does not contain the folder the registry names as Falcon BMS's install (`baseDir`
 *   of every `Falcon BMS 4.x` key), whatever the settings' override says.
 *
 * **When it is on:** while a developer check runs ([SelfTest] sets the system property [PROPERTY] to `1` as soon as
 * a check is dispatched, and in-process UI checks run in that same process), or when the environment has
 * `BMSC_DEV_GUARD=1` (for a dev program started by hand). In the program a pilot runs it is off and costs nothing.
 * Writes to `%APPDATA%` (the settings, the sent plan) are not BMS folders and are never refused.
 */
object DevGuard {
    const val PROPERTY = "bmsc.devcheck"
    const val ENV = "BMSC_DEV_GUARD"
    /** The start of every refusal, so a check can recognise one. */
    const val REFUSAL = "developer run: writes go to a copy only"

    val on: Boolean get() = System.getProperty(PROPERTY) == "1" || System.getenv(ENV) == "1"

    /**
     * Null when a write into every folder of [targets] may go ahead; otherwise the sentence to answer with. Blank
     * and null targets are skipped (nothing known, nothing to write). Always null when the guard is off.
     */
    fun refusal(vararg targets: String?): String? {
        if (!on) return null
        val installs = registryBaseDirs()
        for (t in targets) {
            if (t.isNullOrBlank()) continue
            why(File(t), installs)?.let { return "$REFUSAL ($it)." }
        }
        return null
    }

    /** Why [target] is not a copy, or null when it is one. Public for the contract check. */
    fun why(target: File, installs: List<File> = registryBaseDirs()): String? = try {
        val c = canonical(target)
        val holder = generateSequence(c) { it.parentFile }.firstOrNull { up -> EXES.any { File(up, it).exists() } }
        if (holder != null) "${holder.path} holds Falcon BMS.exe"
        else installs.firstNotNullOfOrNull { i ->
            val ic = canonical(i)
            when {
                same(c, ic) -> "${c.path} is Falcon BMS's installed folder"
                inside(c, ic) -> "${c.path} is inside Falcon BMS's installed folder"
                inside(ic, c) -> "${c.path} contains Falcon BMS's installed folder"
                else -> null
            }
        }
    } catch (e: Throwable) {
        // a folder that cannot even be looked at is not known to be a copy
        "${target.path} could not be checked: ${e.message ?: e::class.java.simpleName}"
    }

    /** Every `baseDir` the registry gives for a Falcon BMS version (read only). */
    fun registryBaseDirs(): List<File> = try {
        if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, SIMS_KEY)) emptyList()
        else Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, SIMS_KEY)
            .filter { it.startsWith("Falcon BMS", ignoreCase = true) }
            .mapNotNull { name ->
                val v = runCatching { Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, "$SIMS_KEY\\$name")["baseDir"] }.getOrNull()
                val s = when (v) {
                    is ByteArray -> String(v, Charsets.US_ASCII)
                    is String -> v
                    else -> null
                }?.trimEnd(' ', ' ')?.trimEnd('\\')
                s?.takeIf { it.isNotBlank() }?.let(::File)
            }
    } catch (_: Throwable) {
        emptyList()
    }

    private const val SIMS_KEY = "SOFTWARE\\WOW6432Node\\Benchmark Sims"
    private val EXES = listOf("Falcon BMS.exe", "Bin\\x64\\Falcon BMS.exe", "Bin\\x86\\Falcon BMS.exe")

    private fun canonical(f: File): File = runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }
    private fun key(f: File) = f.path.trimEnd('\\', '/').lowercase()
    private fun same(a: File, b: File) = key(a) == key(b)
    /** [a] is strictly inside [b] */
    private fun inside(a: File, b: File) = key(a).startsWith(key(b) + File.separator)
}
