package com.sqftware.safegit.diagnostics

/**
 * Takes out of error text what a bug report must not carry: each of [hidden]'s strings, such as a token, a folder or a
 * file the repo holds, becomes its label, and anything else that could be a secret, an address, an email or a file's
 * name becomes a placeholder. A file's name cannot be told from other words, so beyond the ones it is given, every word
 * with a dot or a slash in it goes, and every quoted string, which is how git names a path.
 */
class Scrubber(hidden: Map<String, String>) {
    private val labels = hidden.filterKeys { it.isNotBlank() }

    // One pattern, longest first, so a folder goes as a whole before a file name inside it, and a label is never searched
    private val known = labels.keys.sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.let { texts -> Regex("(?<!\\w)(?:${texts.joinToString("|") { Regex.escape(it) }})(?!\\w)") }

    fun scrub(text: String): String {
        var scrubbed = known?.replace(text) { labels.getValue(it.value) } ?: text
        for ((pattern, replacement) in PATTERNS) scrubbed = pattern.replace(scrubbed, replacement)
        scrubbed = QUOTED.replace(scrubbed) { quoted ->
            val quote = quoted.value.first()
            if (PLACEHOLDER.matches(quoted.value.drop(1).dropLast(1))) quoted.value else "$quote$NAME$quote"
        }
        return WORD.replace(scrubbed) { word(it.value) }
    }

    /** [word], or [NAME] in place of what could be a file's name, keeping the punctuation around it. */
    private fun word(word: String): String {
        val lead = word.takeWhile { it in "([{" }
        val trail = word.drop(lead.length).takeLastWhile { it in ".,:;!?)]}" }
        val core = word.substring(lead.length, word.length - trail.length)
        val named = ('.' in core || '/' in core) && core.any { it.isLetter() } && !CODE.containsMatchIn(core)
        return if (named) "$lead$NAME$trail" else word
    }

    private companion object {
        const val NAME = "<name>"

        val PATTERNS = listOf(
            Regex("(?i)(authorization:\\s*\\w+\\s+)\\S+") to "$1<secret>",
            Regex("\\b(?:gh[opsur]_|github_pat_)\\w+") to "<secret>",
            // Short of the punctuation a sentence puts after it
            Regex("\\b[A-Za-z][\\w+.-]*://[^\\s'\"]*[^\\s'\".,:;!?)\\]]") to "<url>",
            // An SSH address, user@host:path, before it reads as an email
            Regex("[\\w.-]+@[\\w.-]+:[^\\s'\"]*") to "<url>",
            Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+") to "<email>",
            Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b") to "<address>",
            // A host on the local network can go by a name with no dot
            Regex("(?i)(resolve host:?\\s*)[^\\s'\"]+") to "$1<host>",
        )

        // Never the apostrophe inside a word, as in "couldn't"
        val QUOTED = Regex("(?<!\\w)(?:'[^'\\n]+'|\"[^\"\\n]+\")(?!\\w)")
        val PLACEHOLDER = Regex("<[a-z ]+>")
        val WORD = Regex("[^\\s'\"<>]+")

        // The app's own exceptions, as a failed sync reports them
        val CODE = Regex("^(?:java|javax|kotlin|kotlinx|android|androidx|org\\.json|com\\.sqftware)\\.")
    }
}
