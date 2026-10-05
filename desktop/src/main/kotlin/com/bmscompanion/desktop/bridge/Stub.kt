package com.bmscompanion.desktop.bridge

/**
 * What a route that is not built yet answers: JSON, 501, and a sentence the page can show (the clients read a 404 as
 * "the PC is an older version", so a stub must never answer 404). Shared by the round-3 stubs; kept in its own file so a
 * batch replacing one stub does not take it from the others.
 */
internal object Stub {
    fun notBuilt(what: String): ApiResponse =
        ApiResponse.json("""{"error":"BMS Companion on the PC cannot do this yet ($what is not built)."}""", 501)
}
