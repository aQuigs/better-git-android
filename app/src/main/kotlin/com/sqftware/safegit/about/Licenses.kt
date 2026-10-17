package com.sqftware.safegit.about

/** A Termux package that git or its libraries came from, as the build fetched it, with the license text it ships. */
data class BundledPackage(val name: String, val version: String, val license: String)

/** The licenses of what SafeGit ships besides its own code. */
data class Licenses(val packages: List<BundledPackage>, val apache: String)

/** Where the licenses come from. */
interface LicenseSource {
    fun load(): Licenses
}

/** Reads the files `scripts/fetch-git.sh` and the app put in the APK's assets; [read] gives an asset's text by its path. */
class AssetLicenses(private val read: (String) -> String) : LicenseSource {
    override fun load() = Licenses(
        packages = read("git/packages").lines().filter { it.isNotBlank() }.map { line ->
            val (name, version) = line.split(' ', limit = 2)
            BundledPackage(name, version, read("git/licenses/$name"))
        },
        apache = read("licenses/Apache-2.0.txt"),
    )
}
