package com.sqftware.safegit

import androidx.compose.runtime.saveable.listSaver

/** What opens from the repo list: beside it on a wide window, in its place on a phone. [repo] is the repo it is for. */
internal sealed interface Detail {
    val repo: String? get() = null

    data object Add : Detail
    data object About : Detail
    data class History(override val repo: String) : Detail
    data class Files(override val repo: String) : Detail
    /** The repo's id and the path of the marked file open */
    data class Resolve(override val repo: String, val path: String) : Detail

    companion object {
        val saver = listSaver<Detail?, String>(
            save = {
                when (it) {
                    null -> emptyList()
                    Add -> listOf("add")
                    About -> listOf("about")
                    is History -> listOf("history", it.repo)
                    is Files -> listOf("files", it.repo)
                    is Resolve -> listOf("resolve", it.repo, it.path)
                }
            },
            restore = {
                when (it.firstOrNull()) {
                    "add" -> Add
                    "about" -> About
                    "history" -> History(it[1])
                    "files" -> Files(it[1])
                    "resolve" -> Resolve(it[1], it[2])
                    else -> null
                }
            },
        )
    }
}
