package com.bmscompanion.web

import kotlinx.coroutines.await
import kotlin.js.Promise

// Small bridges to the browser's fetch, storage and sharing APIs.

class HttpException(val status: Int, message: String, val body: String = "") : Exception(message)

@JsFun(
    """(url, method, body, timeout) => {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeout);
    const init = { method: method, cache: 'no-store', signal: ctl.signal };
    if (body != null) { init.body = body; init.headers = { 'Content-Type': 'application/json' }; }
    return fetch(url, init).then(async (r) => {
        const text = await r.text();
        clearTimeout(timer);
        return { status: r.status, text: text };
    }, (e) => { clearTimeout(timer); return { status: e && e.name === 'AbortError' ? -2 : -1, text: '' }; });
}"""
)
private external fun jsFetch(url: String, method: String, body: String?, timeout: Int): Promise<JsAny>

@JsFun("(r) => r.status") private external fun jsStatus(r: JsAny): Int
@JsFun("(r) => r.text") private external fun jsText(r: JsAny): String

/** GET or POST returning the body text; throws [HttpException] (status -1 = network error, -2 = timeout). */
suspend fun httpText(url: String, method: String = "GET", body: String? = null, timeoutMs: Int = 15_000): String {
    val r = jsFetch(url, method, body, timeoutMs).await<JsAny>()
    val status = jsStatus(r)
    val text = jsText(r)
    if (status !in 200..299) throw HttpException(status, text.takeIf { it.isNotBlank() && it.length < 300 } ?: "HTTP $status", text)
    return text
}

@JsFun(
    """(url, timeout) => {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), timeout);
    return fetch(url, { signal: ctl.signal }).then((r) => r.ok ? r.arrayBuffer() : null)
        .then((b) => { clearTimeout(timer); return b ? new Int8Array(b) : null; }, () => { clearTimeout(timer); return null; });
}"""
)
private external fun jsFetchBytes(url: String, timeout: Int): Promise<JsAny?>

@JsFun("(a) => a.length") private external fun jsLength(a: JsAny): Int
@JsFun("(a, i) => a[i]") private external fun jsByteAt(a: JsAny, i: Int): Byte

/** Downloads a file as bytes, or null. */
suspend fun httpBytes(url: String, timeoutMs: Int = 30_000): ByteArray? {
    val arr = jsFetchBytes(url, timeoutMs).await<JsAny?>() ?: return null
    val n = jsLength(arr)
    return ByteArray(n) { jsByteAt(arr, it) }
}

/**
 * Asks the VR program to reshape the board to this pixel size, and says whether anything was listening.
 *
 * OpenKneeboard injects this API into a Web Dashboard tab; in any ordinary browser there is nothing there and the
 * call does nothing. Newer versions answer with a promise, older ones with nothing, so both are allowed for.
 */
@JsFun("(w, h) => { try { const api = window.OpenKneeboard; if (api && api.SetPreferredPixelSize) { Promise.resolve(api.SetPreferredPixelSize(w, h)).catch(() => {}); return true; } } catch (e) {} return false; }")
external fun askBoardPixelSize(w: Int, h: Int): Boolean

/**
 * Gives this board its pages in OpenKneeboard, so OpenKneeboard's own next/previous page binding turns them.
 *
 * Four things have to be right, and each of them has broken this:
 *
 * 1. **The feature has to be enabled before anything else is asked for.** OpenKneeboard adds `SetPages` and
 *    `GetPages` to its API object only once `PageBasedContent` has been enabled — so a guard that looked for
 *    `SetPages` first found nothing, decided there was no OpenKneeboard, and no board ever published a page in a
 *    real headset. The feature is enabled first now, and the functions are looked for afterwards.
 * 2. **The page ids cannot come from the browser's random-UUID call.** A board is served from the BMS PC over
 *    plain HTTP at a LAN address, which is not a secure context, and there that call does not exist at all.
 * 3. **They have to be the same ids every time**, so a board that reloads mid-flight does not leave the binding
 *    pointing at pages that no longer exist. They are worked out from the board number and the page position.
 * 4. **The count is decided once, from what this kind of board can need**, not from what it happens to hold while
 *    the mission is still arriving. Pages the content does not reach say so rather than being missing.
 * 5. **OpenKneeboard stops at the last page it was given** — its next-page button greys out, and nothing is sent.
 *    A kneeboard that will not come back round to its first sheet is a kneeboard the pilot has to reach up and
 *    fix mid-flight, so the tab is given a **run** several times longer than the board has sheets and the run is
 *    mapped round them: published page i shows sheet (base + i) mod sheets. Near the end of the run a fresh run
 *    is laid down, beginning at the sheet in front of the pilot, so the turning never reaches a wall.
 *
 * [slot] is the board number and [want] the page count. Whatever happened is left on the window object, and the
 * board prints it along its own foot: a headset has no console to read.
 */
@JsFun(
    """(slot, want, w, h) => {
        try {
            const api = window.OpenKneeboard;
            if (!api) { window.__bmscOkbState = 'no OpenKneeboard API'; return false; }
            if (typeof window.__bmscPage !== 'number') window.__bmscPage = -1;
            // a repeat of what is already published is ignored; a different count is not — a board whose kind is
            // changed on the PC has to be able to say so, and the first count may have been a placeholder
            if (window.__bmscSheets === want) return true;
            window.__bmscSheets = want;
            // three times round is enough that a pilot never reaches the end of a run in one flight; a fresh run is
            // laid down before they do in any case, and a short run is cheaper for OpenKneeboard to hold
            const run = want > 1 ? Math.min(want * 3, 24) : 1;
            window.__bmscRun = run;
            window.__bmscBase = 0;
            window.__bmscGen = 0;
            // a GUID of this board's own, the same one every time, without asking for a secure context. The run it
            // belongs to is part of it: a new run has to be new pages, or OpenKneeboard keeps the old ones.
            const guidFor = (gen, i) => {
                const seed = 'bmsc-board-' + slot + '-s' + want + '-g' + gen + '-page-' + i;
                let h2 = 0x811c9dc5;
                for (let k = 0; k < seed.length; k++) { h2 ^= seed.charCodeAt(k); h2 = Math.imul(h2, 0x01000193) >>> 0; }
                const part = (mix, len) => ((Math.imul(h2 ^ mix, 0x9e3779b1) >>> 0).toString(16).padStart(8, '0')).slice(0, len);
                return part(1, 8) + '-' + part(2, 4) + '-4' + part(3, 3) + '-8' + part(4, 3) + '-' + part(5, 8) + part(6, 4);
            };
            (async () => {
                // First, and only then do SetPages and GetPages exist. Once per page, too: a board that publishes
                // again — a different kind, a count that grew as the mission arrived — asked a second time and was
                // told the feature 'is already enabled', which is an error, and it lost its pages over it.
                if (!window.__bmscFeature) {
                    window.__bmscFeature = (async () => {
                        const feature = { name: 'PageBasedContent', version: 2024073001 };
                        try {
                            if (api.EnableExperimentalFeatures) await api.EnableExperimentalFeatures([feature]);
                            else if (api.EnableExperimentalFeature) await api.EnableExperimentalFeature(feature.name, feature.version);
                            else throw new Error('this OpenKneeboard cannot enable experimental features');
                        } catch (ex) {
                            const said = String((ex && ex.message) || ex);
                            if (!/already enabled/i.test(said)) { window.__bmscFeature = null; throw ex; }
                        }
                    })();
                }
                await window.__bmscFeature;
                if (!api.SetPages) throw new Error('page-based content is not available (' + Object.keys(api).join(' ') + ')');
                // The listener below is registered once and outlives this call, so the run it lays down is read
                // from the window rather than closed over: a board whose kind changed has a different run.
                window.__bmscLay = async (gen, base) => {
                    if (window.__bmscLaying) return;
                    window.__bmscLaying = true;
                    try {
                        const fresh = [];
                        for (let i = 0; i < run; i++) fresh.push(guidFor(gen, i));
                        window.__bmscGen = gen;
                        window.__bmscBase = base;
                        await api.SetPages(fresh.map((g) => ({ guid: g, pixelSize: { width: w, height: h } })));
                        window.__bmscPages = fresh;
                    } finally {
                        window.__bmscLaying = false;
                    }
                };
                const lay = (gen, base) => window.__bmscLay(gen, base);
                if (!window.__bmscListening) {
                    window.__bmscListening = true;
                    api.addEventListener('pageChanged', (e) => {
                        const d = e && e.detail;
                        const guid = (d && d.page && d.page.guid) || (d && d.guid) || null;
                        const known = window.__bmscPages || [];
                        window.__bmscFlips = (window.__bmscFlips || 0) + 1;
                        const i = guid ? known.indexOf(guid) : -1;
                        if (i < 0) { window.__bmscOkbState = 'flip to a page this board does not know'; return; }
                        const sheets = window.__bmscSheets || 1;
                        const sheet = (((window.__bmscBase || 0) + i) % sheets + sheets) % sheets;
                        window.__bmscPage = sheet;
                        // the last page of the run is the wall: lay a fresh run down from the sheet being read, so
                        // that the next turn has somewhere to go and lands on the sheet after this one
                        if (i >= (window.__bmscRun || 1) - 1 && (window.__bmscRun || 1) > 1 && !window.__bmscLaying) {
                            window.__bmscLay((window.__bmscGen || 0) + 1, sheet).catch((ex) => {
                                window.__bmscOkbState = 'new run failed: ' + (ex && ex.message ? ex.message : ex);
                            });
                        }
                    });
                }
                const guids = [];
                for (let i = 0; i < run; i++) guids.push(guidFor(0, i));
                // the documentation is firm: only publish when the tab has none of its own. A tab that already holds
                // the first run of this board's pages keeps them, which is what makes a reload harmless; a tab left
                // on a later run is laid out again, because a reload has forgotten which sheet its pages stood for.
                let have = null;
                if (api.GetPages) have = await api.GetPages().catch(() => null);
                const existing = (have && have.havePages && have.pages) ? have.pages.map((p) => p.guid) : [];
                const kept = existing.length === run && existing.every((g, i) => g === guids[i]);
                if (kept) window.__bmscPages = guids; else await lay(0, 0);
                window.__bmscOkbState = 'ok, ' + want + ' sheets over ' + run + (kept ? ' pages (kept)' : ' pages (published)');
            })().catch((ex) => {
                window.__bmscSheets = null;
                window.__bmscOkbState = 'pages failed: ' + (ex && ex.message ? ex.message : ex);
            });
            return true;
        } catch (e) { window.__bmscOkbState = 'pages threw: ' + e; return false; }
    }""",
)
external fun okbPublishPages(slot: Int, want: Int, w: Int, h: Int): Boolean

/**
 * What went wrong when this board asked for its pages, for printing along the foot of the board itself — a headset
 * has no console to read.
 *
 * Only genuine failures. A board opened in an ordinary browser has no OpenKneeboard API at all, which is not a
 * fault: previewing a board that way is a step in the setup, and a red line about a missing API would be the only
 * thing on the page that looked wrong.
 */
@JsFun("() => { const s = window.__bmscOkbState; return (typeof s === 'string' && s !== 'no OpenKneeboard API' && s.slice(0, 3) !== 'ok,') ? s : ''; }")
external fun okbLastError(): String

/** Rounds the corners of the page itself, so the square ones are not painted at all. */
@JsFun("(px) => { try { const c = document.querySelector('canvas'); if (c) c.style.borderRadius = px + 'px'; return true; } catch (e) { return false; } }")
external fun setBoardCorner(px: Double): Boolean

/** The section OpenKneeboard has just flipped to, or -1. Reading it clears it. */
@JsFun("() => { const i = window.__bmscPage; window.__bmscPage = -1; return (typeof i === 'number') ? i : -1; }")
external fun okbTakePage(): Int

/** Names the page. OpenKneeboard reads it when a Web Dashboard tab is added, and so does a browser tab or bookmark. */
@JsFun("(t) => { try { document.title = t; } catch (e) {} }")
external fun setDocumentTitle(t: String)

@JsFun("(k) => { try { return localStorage.getItem(k); } catch (e) { return null; } }")
external fun storageGet(key: String): String?

@JsFun("(k, v) => { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} }")
external fun storageSet(key: String, value: String?)

@JsFun("(s) => encodeURIComponent(s)")
external fun encodeUriComponent(s: String): String

/** Starts a download of a same-origin URL under [name]. */
@JsFun("(url, name) => { const a = document.createElement('a'); a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove(); }")
external fun downloadUrl(url: String, name: String)

@JsFun("() => !!(navigator.canShare && navigator.share)")
external fun canShareFiles(): Boolean

/** Fetches [url] and opens the system share sheet with it as a file. Resolves to '' when shared, or an error text. */
@JsFun(
    """(url, name) => fetch(url).then((r) => r.blob()).then((b) => {
    const f = new File([b], name, { type: b.type || 'image/png' });
    if (!navigator.canShare || !navigator.canShare({ files: [f] })) return 'Sharing files is not supported by this browser';
    return navigator.share({ files: [f] }).then(() => '', (e) => e && e.name === 'AbortError' ? '' : String(e));
}, (e) => String(e))"""
)
private external fun jsShareUrl(url: String, name: String): Promise<JsString>

suspend fun shareUrl(url: String, name: String): String = jsShareUrl(url, name).await<JsString>().toString()

/**
 * Every file this browser cached for the app, and anything it kept for this session — but **not** the settings.
 *
 * Run when the version changes, so no code or data belonging to an older build is mixed into the new one. What a
 * pilot set up is theirs and stays: the Dashboard layouts, the map look, the PC to connect to, the board shape and
 * print size all live in localStorage and survive an update. Everything that reads them copes with a value written
 * by an older version (a layout that no longer parses falls back to the default, an unknown card is dropped).
 */
@JsFun(
    """() => {
        try { sessionStorage.clear(); } catch (e) {}
        try { if (window.caches && caches.keys) caches.keys().then((k) => k.forEach((n) => caches.delete(n))); } catch (e) {}
    }""",
)
external fun clearBrowserCaches()

/** Opens a link in a new tab, and asks the browser not to hand this page over to it. */
@JsFun("(url) => { window.open(url, '_blank', 'noopener,noreferrer'); }")
external fun openInNewTab(url: String)

@JsFun("() => { const s = document.getElementById('splash'); if (s) s.remove(); }")
external fun hideSplash()

@JsFun("() => Date.now()")
external fun nowMillis(): Double

@JsFun("() => performance.now()")
external fun perfMillis(): Double

@JsFun("(s) => { try { return decodeURIComponent(s); } catch (e) { return s; } }")
external fun decodeUriComponent(s: String): String

