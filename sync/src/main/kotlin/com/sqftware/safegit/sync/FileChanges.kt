package com.sqftware.safegit.sync

/** Files by path, as one side of a sync changes them. A submodule counts as one path. */
data class FileChanges(val changed: List<String>, val added: List<String>, val deleted: List<String>) {
    fun isEmpty() = changed.isEmpty() && added.isEmpty() && deleted.isEmpty()

    val size get() = changed.size + added.size + deleted.size

    /** These changes followed by [later], as one: a file added and then deleted, say, was never there. */
    internal fun then(later: FileChanges): FileChanges {
        val statuses = statuses().toMutableMap()
        for ((path, next) in later.statuses()) {
            when (val first = statuses[path]) {
                null -> statuses[path] = next
                Status.ADDED -> if (next == Status.DELETED) statuses.remove(path)
                Status.DELETED -> statuses[path] = Status.CHANGED
                Status.CHANGED -> statuses[path] = next
            }
        }
        return of(statuses)
    }

    private fun statuses() = changed.associateWith { Status.CHANGED } + added.associateWith { Status.ADDED } + deleted.associateWith { Status.DELETED }

    internal enum class Status { CHANGED, ADDED, DELETED }

    companion object {
        val NONE = FileChanges(emptyList(), emptyList(), emptyList())

        internal fun of(statuses: Map<String, Status>): FileChanges {
            fun with(status: Status) = statuses.filterValues { it == status }.keys.sorted()
            return FileChanges(changed = with(Status.CHANGED), added = with(Status.ADDED), deleted = with(Status.DELETED))
        }
    }
}

/** What a sync moved: the remote's changes it brought into the folder, and the changes it gave the remote. */
data class Transfer(val pulled: FileChanges = FileChanges.NONE, val pushed: FileChanges = FileChanges.NONE)
