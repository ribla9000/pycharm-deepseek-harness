package com.dsh.assistant.changes

import com.dsh.assistant.settings.DshAppSettings
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks project file changes (including those the DSH agent makes through the file system),
 * keeps a list of changes with their before snapshots, and can accept/reject each change.
 */
@Service(Service.Level.PROJECT)
class ChangeTracker(private val project: Project) : BulkFileListener, Disposable {

    enum class Kind { MODIFIED, CREATED, DELETED }
    enum class Status { PENDING, ACCEPTED, REJECTED }

    class Entry(
        val path: String,
        val display: String,
        @Volatile var kind: Kind,
        @Volatile var beforeText: String?,
        @Volatile var beforeExists: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        @Volatile var status: Status = Status.PENDING
        @Volatile var added: Int = -1
        @Volatile var deleted: Int = -1
        @Volatile var revertUnavailable: Boolean = false
    }

    private val app = DshAppSettings.getInstance()
    private val baseline = project.getService(BaselineStore::class.java)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val restoring = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var changeListener: (() -> Unit)? = null

    init {
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, this)
        if (app.appState.trackOnProjectOpen) {
            baseline.startScan()
        }
    }

    fun entriesList(): List<Entry> = entries.toList()

    fun pendingCount(): Int = entries.count { it.status == Status.PENDING }

    fun acceptedCount(): Int = entries.count { it.status == Status.ACCEPTED }

    fun rejectedCount(): Int = entries.count { it.status == Status.REJECTED }

    override fun after(events: MutableList<out VFileEvent>) {
        if (!app.appState.trackOnProjectOpen) return
        val found = LinkedHashMap<String, String>()
        for (e in events) {
            when (e) {
                is VFileContentChangeEvent -> maybeAdd(e.file, "MODIFIED", found)
                is VFileCreateEvent -> maybeAdd(e.file, "CREATED", found)
                is VFileDeleteEvent -> maybeAdd(e.file, "DELETED", found)
                else -> Unit
            }
        }
        if (found.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            for ((path, kind) in found) {
                try {
                    process(path, kind)
                } catch (ignored: Exception) {
                    // best-effort
                }
            }
            fireChanged()
        }
    }

    /**
     * Batch of changes found by the watcher ([ChangesWatchdog]) directly on the
     * file system — VFS may report the same edits later.
     * Duplicates are safe: [process] finds the already pending entry and only
     * recomputes the counters, without adding a new version to the history.
     */
    fun externalChanges(changes: Map<String, Kind>) {
        if (!app.appState.trackOnProjectOpen) return
        var touched = false
        for ((path, kind) in changes) {
            if (!isTracked(path)) continue
            try {
                process(path, kind.name)
                touched = true
            } catch (ignored: Exception) {
                // best-effort
            }
        }
        if (touched) fireChanged()
    }

    /** Wake the watcher immediately (agent signal: files changed / turn finished). */
    fun wakeWatcher() {
        try {
            ChangesWatchdog.getInstance(project).kick()
        } catch (ignored: Exception) {
            // best-effort
        }
    }

    private fun maybeAdd(f: VirtualFile?, kind: String, map: MutableMap<String, String>) {
        if (f == null || f.isDirectory || !f.isInLocalFileSystem) return
        val path = f.path
        if (!isTracked(path)) return
        map.putIfAbsent(path, kind)
    }

    private fun isTracked(path: String): Boolean {
        val rel = projectRelative(path) ?: return false
        val excluded = baseline.excludedDirNames()
        for (seg in rel.split('/')) {
            if (seg in excluded || seg == ".dsh") return false
        }
        return true
    }

    private fun projectRelative(path: String): String? {
        val base = project.basePath ?: return null
        if (path == base) return ""
        val prefix = base + "/"
        return if (path.startsWith(prefix)) path.substring(prefix.length) else null
    }

    // The watcher and VFS events can arrive at the same time: the "entry already exists"
    // check and appending a new entry share one lock, otherwise duplicates appeared.
    @Synchronized
    private fun process(path: String, kindStr: String) {
        val kind = Kind.valueOf(kindStr)
        val existing = entries.firstOrNull { it.path == path && it.status == Status.PENDING }

        if (restoring.contains(path)) return

        val f = File(path)

        if (kind == Kind.MODIFIED && existing == null) {
            // Is this our own rollback? If the current content matches the snapshot, it is a restore.
            val before = baseline.before(path)
            if (before != null && f.isFile && f.length() <= app.appState.maxFileBytes) {
                val current = try {
                    f.readText(StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    null
                }
                if (current != null && current == before) return
            }
        }

        // Too large or binary files are not tracked.
        if (f.isFile) {
            if (f.length() > app.appState.maxFileBytes) return
            val head = try {
                f.inputStream().use { s -> s.readNBytes(8192) }
            } catch (e: Exception) {
                null
            }
            if (head != null && head.any { it == 0.toByte() }) return
        }

        if (existing != null) {
            // The file is already pending: update its kind (e.g. modified → deleted).
            if (kind == Kind.DELETED && existing.kind != Kind.DELETED) {
                existing.kind = Kind.DELETED
                existing.beforeExists = true
            }
            recomputeCounts(existing)
            return
        }

        val display = projectRelative(path) ?: path
        var beforeText = baseline.before(path)
        val entry = Entry(path, display, kind, beforeText, beforeText != null)

        when (kind) {
            Kind.CREATED -> {
                entry.beforeExists = false
                entry.beforeText = null
                beforeText = null
            }
            Kind.DELETED -> {
                entry.beforeExists = true
                if (beforeText == null) {
                    beforeText = baseline.gitBefore(display)
                    entry.beforeText = beforeText
                    if (beforeText == null) entry.revertUnavailable = true
                }
            }
            Kind.MODIFIED -> {
                entry.beforeExists = true
                if (beforeText == null) {
                    beforeText = baseline.gitBefore(display)
                    entry.beforeText = beforeText
                    if (beforeText == null) entry.revertUnavailable = true
                }
            }
        }
        computeCounts(entry)
        entries.add(0, entry)
        recordVersion(entry)
    }

    /** Version chain of a file: before = the previous after, after = the current content. */
    private val recordedAfter = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun recordVersion(entry: Entry) {
        recordCurrentVersion(entry)
    }

    /**
     * Records the current file content as the next history version.
     * @return true if the version was actually added.
     */
    private fun recordCurrentVersion(entry: Entry): Boolean {
        if (entry.kind == Kind.DELETED) return false
        val after = currentText(entry.path) ?: return false
        val before = recordedAfter[entry.path] ?: entry.beforeText ?: ""
        if (before == after) return false
        return try {
            com.dsh.assistant.db.DshDatabase.getInstance(project)
                .recordFileVersion(entry.path, before, after, entry.added, entry.deleted)
            // advance the chain only after a successful write — if the DB fails,
            // the version will be retried on the next save/accept
            recordedAfter[entry.path] = after
            true
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * Appends the final file state to the history once the turn is finished.
     * While the agent is running, a file may change several times, but a version
     * is recorded only when an entry appears in the list — so "History" used to
     * receive an intermediate snapshot. This method captures the turn result, so
     * the history shows up right when the agent is done.
     */
    @Synchronized
    fun recordFinalVersions() {
        if (!app.appState.trackOnProjectOpen) return
        var recorded = false
        for (e in entriesList()) {
            if (e.status != Status.PENDING) continue
            if (recordCurrentVersion(e)) recorded = true
        }
        if (recorded) fireChanged()
    }

    private fun currentText(path: String): String? {
        val f = File(path)
        if (!f.isFile) return null
        if (f.length() > app.appState.maxFileBytes) return null
        return try {
            f.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun computeCounts(entry: Entry) {
        entry.added = 0
        entry.deleted = 0
        val before = entry.beforeText ?: ""
        val after = when (entry.kind) {
            Kind.DELETED -> ""
            else -> currentText(entry.path) ?: ""
        }
        if (before.isEmpty() && after.isEmpty()) return
        try {
            if (before.length + after.length > 2_000_000) return
            val fragments = ComparisonManager.getInstance()
                .compareLines(before, after, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
            var added = 0
            var deleted = 0
            for (fr in fragments) {
                added += fr.endLine2 - fr.startLine2
                deleted += fr.endLine1 - fr.startLine1
            }
            entry.added = added
            entry.deleted = deleted
        } catch (e: Exception) {
            // large or failed diff — keep the counters at zero
        }
    }

    private fun recomputeCounts(entry: Entry) {
        ApplicationManager.getApplication().executeOnPooledThread {
            computeCounts(entry)
            fireChanged()
        }
    }

    // ---------- Accept / reject ----------

    @Synchronized
    fun accept(entry: Entry) {
        if (entry.status != Status.PENDING) return
        // Before resetting the chain, capture the final content: otherwise the
        // history kept an intermediate snapshot and the last edit was lost.
        recordCurrentVersion(entry)
        entry.status = Status.ACCEPTED
        recordedAfter.remove(entry.path)
        when (entry.kind) {
            Kind.DELETED -> baseline.remove(entry.path)
            else -> baseline.update(entry.path)
        }
        fireChanged()
    }

    fun reject(entry: Entry) {
        if (entry.status != Status.PENDING || entry.revertUnavailable) return
        recordedAfter.remove(entry.path)
        restoring.add(entry.path)
        try {
            when (entry.kind) {
                Kind.MODIFIED -> {
                    val t = entry.beforeText ?: return
                    writeFile(entry.path, t)
                    entry.status = Status.REJECTED
                    baseline.update(entry.path)
                }
                Kind.CREATED -> {
                    deleteFile(entry.path)
                    entry.status = Status.REJECTED
                    baseline.remove(entry.path)
                }
                Kind.DELETED -> {
                    val t = entry.beforeText ?: return
                    writeFile(entry.path, t)
                    entry.status = Status.REJECTED
                    baseline.update(entry.path)
                }
            }
        } finally {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    Thread.sleep(4000)
                } catch (ignored: InterruptedException) {
                    // ignore
                }
                restoring.remove(entry.path)
            }
        }
        fireChanged()
    }

    fun hasPending(path: String): Boolean =
        entries.any { it.path == path && it.status == Status.PENDING }

    // ---------- checkpoints (as in Cursor) ----------

    class Checkpoint(
        val name: String,
        val time: Long,
        val snapshot: BaselineStore.Snapshot,
    )

    private val checkpoints = java.util.ArrayDeque<Checkpoint>()

    fun createCheckpoint(name: String): Checkpoint? {
        val snap = try {
            baseline.snapshotFiles()
        } catch (e: Exception) {
            return null
        }
        val cp = Checkpoint(name, System.currentTimeMillis(), snap)
        synchronized(checkpoints) {
            checkpoints.addLast(cp)
            val cap = app.appState.maxCheckpoints.coerceAtLeast(1)
            while (checkpoints.size > cap) checkpoints.removeFirst()
        }
        fireChanged()
        return cp
    }

    fun checkpointsList(): List<Checkpoint> = synchronized(checkpoints) { checkpoints.toList() }

    /** Restores project files to the state at the checkpoint. */
    fun rollbackTo(cp: Checkpoint) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val guardPaths = ConcurrentHashMap.newKeySet<String>()
            try {
                val snap = cp.snapshot
                // 1. collect the list of files we touch
                for ((path, content) in snap.files) {
                    val f = File(path)
                    val current = if (f.isFile && f.length() <= 2_000_000L) {
                        try {
                            f.readText(StandardCharsets.UTF_8)
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    if (current != content) guardPaths.add(path)
                }
                // 2. files created after the checkpoint — delete them
                if (!snap.partial) {
                    for (path in baseline.listAllPaths()) {
                        if (!snap.allPaths.contains(path)) guardPaths.add(path)
                    }
                }
                restoring.addAll(guardPaths)
                // 3. apply
                for (path in guardPaths) {
                    val content = snap.files[path]
                    if (content == null) {
                        deleteFile(path)
                    } else {
                        writeFile(path, content)
                    }
                }
                entries.clear()
                baseline.startScan()
            } catch (e: Exception) {
                // best effort
            } finally {
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        Thread.sleep(6000)
                    } catch (ignored: InterruptedException) {
                        // ignore
                    }
                    restoring.removeAll(guardPaths)
                }
            }
            fireChanged()
        }
    }

    fun acceptAllPending() {
        for (e in entriesList()) {
            if (e.status == Status.PENDING) accept(e)
        }
    }

    fun rejectAllPending() {
        for (e in entriesList()) {
            if (e.status == Status.PENDING) reject(e)
        }
    }

    fun clearFinished() {
        entries.removeIf { it.status != Status.PENDING }
        fireChanged()
    }

    private fun fireChanged() {
        ApplicationManager.getApplication().invokeLater {
            changeListener?.invoke()
        }
    }

    private fun writeFile(path: String, text: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(text, StandardCharsets.UTF_8)
        refreshVfs()
    }

    private fun deleteFile(path: String) {
        val f = File(path)
        FileUtil.delete(f)
        refreshVfs()
    }

    /** Asynchronous VFS refresh — a synchronous refresh requires a write action and fails. */
    private fun refreshVfs() {
        try {
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh { }
        } catch (ignored: Throwable) {
            // ignore
        }
    }

    override fun dispose() = Unit
}
