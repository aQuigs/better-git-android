package com.sqftware.safegit.sync

private val SSH = Regex("^(?:ssh|git\\+ssh|ssh\\+git)://(?:[^@/]*@)?([^@:/]+)(?::\\d*)?/(.*)$")
private val SCP_LIKE = Regex("^(?:[^@/:]*@)?([^@/:]+):/?(.*)$")

/**
 * [url], or for an SSH URL the same host and path over HTTPS, as GitHub and most hosts serve a repo both ways. SafeGit
 * has no SSH client, so a submodule a laptop declared by its SSH URL is fetched this way, with the same sign-in.
 */
internal fun overHttps(url: String): String {
    val ssh = SSH.matchEntire(url) ?: SCP_LIKE.matchEntire(url)?.takeUnless { "://" in url || url.startsWith("file:") } ?: return url
    val (host, path) = ssh.destructured
    return "https://$host/$path"
}
