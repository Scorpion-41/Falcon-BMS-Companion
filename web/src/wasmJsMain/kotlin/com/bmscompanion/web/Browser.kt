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

@JsFun("() => { const s = document.getElementById('splash'); if (s) s.remove(); }")
external fun hideSplash()

@JsFun("() => Date.now()")
external fun nowMillis(): Double

@JsFun("() => performance.now()")
external fun perfMillis(): Double

@JsFun("(s) => { try { return decodeURIComponent(s); } catch (e) { return s; } }")
external fun decodeUriComponent(s: String): String
