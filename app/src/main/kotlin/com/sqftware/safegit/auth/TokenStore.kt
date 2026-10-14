package com.sqftware.safegit.auth

/** Where the signed-in account and its token are kept between runs. */
interface TokenStore {
    fun load(): Session?

    fun save(session: Session)

    fun clear()
}

/** An account signed in, with the token git sends for it. */
data class Session(val account: Account, val token: String) {
    override fun toString() = "Session(${account.login})"
}
