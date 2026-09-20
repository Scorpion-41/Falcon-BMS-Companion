package com.bmscompanion.app.data.update

import com.bmscompanion.app.AppVersion
import com.bmscompanion.app.data.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Finding, fetching and installing a newer BMS Companion.
 *
 * Releases are published on GitHub, so that is where the app looks: the releases API lists every version with its
 * notes and its files, which is everything needed to say "1.3.4 is out, here is what changed, shall I install it".
 * The work is split in two — this object knows about versions and release notes on every platform, and [Installer]
 * does the part only a platform can do: keep a file, hash it, run it. The browser has no installer and says so.
 */
@Serializable
data class ReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
    /** GitHub states this as "sha256:<hex>" on newer releases; older ones have nothing and a checksums file is used. */
    val digest: String? = null,
)

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val published: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    /** "v1.3.4" and "1.3.4" are the same release; the tag is what GitHub sorts by, the number is what pilots read. */
    val version: String get() = tag.trimStart('v', 'V')
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: "Version $version"
    val date: String get() = published?.take(10).orEmpty()
}

/** What the About page shows: nothing, a newer version, or what went wrong looking. */
data class UpdateState(
    val checking: Boolean = false,
    val checkedAt: Long = 0,
    val error: String? = null,
    /** Every release newer than the running version, newest first — the whole story since this build, not just the top. */
    val newer: List<Release> = emptyList(),
    val progress: Progress? = null,
) {
    val latest: Release? get() = newer.firstOrNull()
    val available: Boolean get() = latest != null
}

/** Where a download or an install has got to. */
data class Progress(
    val stage: Stage,
    /** 0..1 while downloading, or null when there is nothing to measure. */
    val fraction: Float? = null,
    val text: String = "",
    val done: Long = 0,
    val total: Long = 0,
    /** Smoothed, so the figure on screen is one a pilot can read rather than one that flickers. */
    val bytesPerSecond: Double = 0.0,
) {
    enum class Stage { CHECKING, DOWNLOADING, VERIFYING, READY, INSTALLING, FAILED }
}

/** "278 MB", "6.4 MB/s" — kotlin only, because the browser build has no String.format worth the name. */
fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "${oneDecimal(bytes / gb)} GB"
        bytes >= mb -> if (bytes >= 10 * mb) "${(bytes / mb).toInt()} MB" else "${oneDecimal(bytes / mb)} MB"
        bytes >= kb -> "${(bytes / kb).toInt()} KB"
        else -> "$bytes B"
    }
}

/** "45 s", "3 min 20 s", "1 h 12 min" — never "212 seconds". */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> (s / 60).let { m -> if (s % 60 == 0L || m >= 10) "$m min" else "$m min ${s % 60} s" }
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

private fun oneDecimal(v: Double): String {
    val tenths = (v * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * The part of updating that only a platform can do.
 *
 * Deliberately small: a place to keep the file, a hash, a download with progress, and a way to run the thing. The PC
 * hands the MSI to Windows Installer; Android hands the APK to the package installer; the browser has neither and
 * leaves [canInstall] false, which turns the About page into a link to the release instead.
 */
interface Installer {
    /** False in the browser: a page cannot install anything, so it only offers the release page. */
    val canInstall: Boolean

    /** Which file of a release this platform wants — ".msi" on Windows, ".apk" on Android. */
    val assetSuffix: String

    /** The SHA-256 of a file already downloaded, or null when it is not there. */
    suspend fun cachedHash(name: String): String?

    /** What is in the cache now, so a download for a version already installed can be thrown away. */
    fun cachedFiles(): List<String>

    /**
     * Downloads to the cache. [onProgress] is called with the bytes fetched so far and the total the server
     * states, often enough for a rate and a time remaining to be worked out from it.
     * Returns the file name it kept, or throws.
     */
    suspend fun download(url: String, name: String, size: Long, onProgress: (done: Long, total: Long) -> Unit): String

    /** Hands the file to the system installer. Returns a message to show, or null when the app is about to close. */
    suspend fun install(name: String): String?

    /** Throws away anything cached except [keep] — after an install, and at start-up. */
    fun clearCache(keep: String? = null)
}

object Updates {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Releases are checked at most once an hour; the About page asks every time it opens. */
    private const val REVISIT_MS = 60 * 60 * 1000L

    val installer: Installer? get() = Platform.installer

    /**
     * The version everything is compared against: this build, except in the `--updatetest` developer check, where
     * pretending to be an older one is the only way to exercise fetching and verifying a real release.
     */
    var baseline: String = AppVersion.NAME

    /**
     * Reads the release list and works out what is newer than this build.
     *
     * Pre-releases and drafts are left out: a pilot updating from inside the app should land on something the author
     * decided to publish. A version that cannot be parsed is ignored rather than guessed at.
     */
    suspend fun check(force: Boolean = false) {
        val now = Platform.nowMillis()
        val s = _state.value
        if (!force && s.checkedAt > 0 && now - s.checkedAt < REVISIT_MS) return
        if (s.checking) return
        _state.value = s.copy(checking = true, error = null)
        val result = runCatching {
            val fetch = Platform.fetchText ?: error("This build cannot reach GitHub.")
            val json = fetch("${apiBase()}/releases?per_page=20")
            com.bmscompanion.app.data.Repo.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Release.serializer()), json)
        }
        _state.value = result.fold(
            onSuccess = { list ->
                val newer = list
                    .filter { !it.draft && !it.prerelease }
                    .filter { compareVersions(it.version, baseline) > 0 }
                    .sortedWith { a, b -> compareVersions(b.version, a.version) }
                _state.value.copy(checking = false, checkedAt = now, error = null, newer = newer)
            },
            onFailure = { _state.value.copy(checking = false, checkedAt = now, error = reason(it)) },
        )
    }

    /** The asset this platform should download from a release, if the release has one. */
    fun assetFor(release: Release): ReleaseAsset? {
        val suffix = installer?.assetSuffix ?: return null
        return release.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
    }

    /**
     * Downloads (or reuses) the release file and installs it.
     *
     * A download interrupted halfway — a lost connection, a headset taken off, Android asking for the permission to
     * install and not getting it — leaves a file in the cache. That file is hashed first: if it matches the release
     * there is nothing to download and the install can go straight ahead, which is the difference between waiting
     * four minutes again and waiting none.
     */
    suspend fun update(release: Release) {
        val install = installer ?: return
        val file = fetch(release) ?: return
        runCatching {
            progress(Progress(Progress.Stage.INSTALLING, text = "Starting the installer…"))
            val message = install.install(file)
            progress(Progress(Progress.Stage.READY, text = message ?: "The installer is running."))
        }.onFailure { progress(Progress(Progress.Stage.FAILED, text = reason(it))) }
    }

    /**
     * Everything up to the install: the file in the cache, checked, with the progress the pilot watches.
     *
     * Separate from [update] because it is the half that can be run for real without changing anything on the PC —
     * which is what the `--updatetest` developer check does.
     */
    suspend fun fetch(release: Release): String? {
        val install = installer ?: return null
        val asset = assetFor(release) ?: run {
            progress(Progress(Progress.Stage.FAILED, text = "Release ${release.version} has no ${install.assetSuffix} file."))
            return null
        }
        val want = expectedHash(release, asset)
        val file = cacheName(release, asset)
        return runCatching {
            progress(Progress(Progress.Stage.VERIFYING, text = "Looking for a copy already downloaded…"))
            val have = install.cachedHash(file)
            val usable = have != null && (want == null || have.equals(want, ignoreCase = true))
            if (have != null && !usable) {
                // a half-finished or corrupted file is worse than none: it would install as a broken program
                install.clearCache()
            }
            if (!usable) {
                progress(Progress(Progress.Stage.DOWNLOADING, 0f, "Downloading ${asset.name}…"))
                // Rate and time left, smoothed. A raw reading swings wildly on a home connection — a bar that says
                // "12 seconds left" and then "4 minutes left" is worse than one that says nothing.
                var lastAt = Platform.nowMillis()
                var lastDone = 0L
                var rate = 0.0
                install.download(asset.url, file, asset.size) { done, total ->
                    val now = Platform.nowMillis()
                    if (now - lastAt >= 300) {
                        val instant = (done - lastDone) * 1000.0 / (now - lastAt)
                        rate = if (rate <= 0.0) instant else rate * 0.7 + instant * 0.3
                        lastAt = now
                        lastDone = done
                    }
                    val fraction = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
                    progress(
                        Progress(
                            Progress.Stage.DOWNLOADING, fraction, downloadText(asset, done, total, rate),
                            done = done, total = total, bytesPerSecond = rate,
                        ),
                    )
                }
                progress(Progress(Progress.Stage.VERIFYING, text = "Checking the download…"))
                val got = install.cachedHash(file)
                if (want != null && !want.equals(got, ignoreCase = true)) {
                    install.clearCache()
                    error("The download did not match the checksum in the release. Nothing was installed.")
                }
            }
            file
        }.onFailure { progress(Progress(Progress.Stage.FAILED, text = reason(it))) }.getOrNull()
    }

    /**
     * Throws away an installer for a version that is already running, and keeps one that is still ahead.
     *
     * Both halves matter. A pilot who updated last week should not still be carrying the old MSI around; a pilot
     * whose download was interrupted — and on Android that happens every time the install permission prompt is
     * dismissed — should not have to fetch a few hundred megabytes twice. The cached file is named after its
     * release for exactly this, because the published names (`BMS-Companion.apk`) carry no version of their own.
     */
    fun tidyCache() {
        runCatching {
            val install = installer ?: return
            // keep the newest download that is still ahead of this build, and nothing else
            val keep = install.cachedFiles()
                .filter { compareVersions(versionOf(it), AppVersion.NAME) > 0 }
                .maxWithOrNull { a, b -> compareVersions(versionOf(a), versionOf(b)) }
            install.clearCache(keep = keep)
        }
    }

    /** `1.3.4-BMS-Companion.apk`: the release it belongs to, then the name it was published under. */
    private fun cacheName(release: Release, asset: ReleaseAsset) = "${release.version}-${asset.name}"

    private fun versionOf(cacheName: String) = cacheName.substringBefore('-', "")

    /**
     * "131 MB of 278 MB · 6.4 MB/s · 23 s left".
     *
     * A percentage on its own tells a pilot nothing about whether to wait or to go and make coffee — these are
     * three hundred megabyte downloads. How much, how fast, and how long is what a download has to say.
     */
    private fun downloadText(asset: ReleaseAsset, done: Long, total: Long, bytesPerSecond: Double): String {
        val size = if (total > 0) total else asset.size
        val parts = ArrayList<String>(3)
        parts += if (size > 0) "${formatBytes(done)} of ${formatBytes(size)}" else formatBytes(done)
        if (bytesPerSecond > 0) parts += "${formatBytes(bytesPerSecond.toLong())}/s"
        if (bytesPerSecond > 0 && size > done) parts += "${formatDuration(((size - done) / bytesPerSecond).toLong())} left"
        return parts.joinToString(" · ")
    }

    fun dismissProgress() = progress(null)

    private fun progress(p: Progress?) { _state.value = _state.value.copy(progress = p) }

    /**
     * The checksum a release states for [asset]: GitHub's own digest where there is one, otherwise a line in a
     * checksums file published beside the download. Null when the release says nothing, and then the download is
     * taken on trust — with a warning, rather than refusing to update at all.
     */
    private suspend fun expectedHash(release: Release, asset: ReleaseAsset): String? {
        asset.digest?.substringAfter("sha256:", "")?.takeIf { it.length == 64 }?.let { return it }
        val sums = release.assets.firstOrNull {
            it.name.equals("SHA256SUMS.txt", true) || it.name.equals("SHA256SUMS", true) || it.name.equals("${asset.name}.sha256", true)
        } ?: return null
        val fetch = Platform.fetchText ?: return null
        val text = runCatching { fetch(sums.url) }.getOrNull() ?: return null
        // "<hex>  <file>" per line, the format sha256sum writes
        return text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(asset.name, true) || (sums.name.endsWith(".sha256") && it.length >= 64) }
            ?.take(64)
            ?.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' || c in 'A'..'F' } }
    }

    /** github.com/owner/repo -> api.github.com/repos/owner/repo */
    private fun apiBase(): String {
        val path = AppVersion.REPO.substringAfter("github.com/").trim('/')
        return "https://api.github.com/repos/$path"
    }

    private fun reason(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("403") -> "GitHub is rate-limiting this connection. Try again later."
            m.contains("404") -> "The release list could not be found."
            m.isBlank() -> e::class.simpleName ?: "Could not reach GitHub"
            else -> m
        }
    }
}

/** 1.3.10 is newer than 1.3.9: compared number by number, not as text. */
fun compareVersions(a: String, b: String): Int {
    val x = versionKey(a)
    val y = versionKey(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
        if (d != 0) return if (d > 0) 1 else -1
    }
    return 0
}

fun versionKey(v: String): List<Int> =
    v.trim().trimStart('v', 'V').split('.', '-', '_').mapNotNull { part ->
        part.takeWhile { it.isDigit() }.toIntOrNull()
    }
