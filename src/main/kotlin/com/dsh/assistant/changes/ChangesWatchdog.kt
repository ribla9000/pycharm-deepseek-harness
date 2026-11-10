package com.dsh.assistant.changes

import com.dsh.assistant.settings.DshAppSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project file change watchdog.
 *
 * The DSH agent edits files in a separate process, so the IDE may learn about it
 * with a delay (VFS does not notice external writes instantly — sometimes only when
 * the window gets activated). Because of that, the "Changes" panel and "History · N"
 * did not appear right after the turn finished.
 *
 * The watcher polls the file system itself every [POLL_INTERVAL_MS] and passes
 * the detected edits to [ChangeTracker] — without waiting for VFS. The agent can also
 * wake it up immediately ([kick]) — on `workspace/changes` and
 * `turn/end` events (see `ChatState`), so that the final file state reaches
 * the history as soon as the turn is over.
 *
 * Polling is incremental: each tick re-reads the attributes of already known files
 * and re-lists only the directories whose mtime has changed. The scheduler is
 * single-threaded with a fixed delay: if the project is very large and a tick
 * takes longer than 300 ms, the next one starts after the current one finishes
 * instead of piling on top of it.
 */
@Service(Service.Level.PROJECT)
class ChangesWatchdog(private val project: Project) : Disposable {

    companion object {
        /** Interval of the self-driven file polling. */
        const val POLL_INTERVAL_MS = 300L

        private val LOG = logger<ChangesWatchdog>()

        fun getInstance(project: Project): ChangesWatchdog = project.service()
    }

    private val app = DshAppSettings.getInstance()

    /** Lazy: a light service constructor must not pull other project services. */
    private val baseline by lazy { project.service<BaselineStore>() }

    /** Lazy: the watcher is started from [ChangeTracker], which is still being constructed. */
    @Volatile
    private var trackerRef: ChangeTracker? = null

    private fun tracker(): ChangeTracker =
        trackerRef ?: project.service<ChangeTracker>().also { trackerRef = it }

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "dsh-changes-watchdog").apply { isDaemon = true }
        }

    private val started = AtomicBoolean(false)
    private val kickQueued = AtomicBoolean(false)

    @Volatile
    private var stopped = false

    /** Last known file state: mtime + size. */
    private data class Fingerprint(val mtime: Long, val size: Long)

    private val files = HashMap<String, Fingerprint>()
    private val dirs = HashMap<String, Long>()

    @Volatile
    private var initialized = false

    /** Starts background polling (a repeated call is safe). */
    fun start() {
        if (stopped || !started.compareAndSet(false, true)) return
        try {
            executor.scheduleWithFixedDelay({ tick() }, 0L, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            started.set(false)
            LOG.warn("Failed to start the change watcher", t)
        }
    }

    /** Check the files right now (agent signal: "I have finished/changed files"). */
    fun kick() {
        if (stopped) return
        start()
        if (!kickQueued.compareAndSet(false, true)) return
        try {
            executor.execute {
                kickQueued.set(false)
                tick()
            }
        } catch (t: Throwable) {
            kickQueued.set(false)
        }
    }

    // ---------- polling ----------

    private fun tick() {
        if (stopped || !app.appState.trackOnProjectOpen) return
        try {
            if (!initialized || rootsChanged()) {
                // content roots may not be ready yet (early project start) — retry later
                if (fullScan()) initialized = true
                return
            }
            val changes = LinkedHashMap<String, ChangeTracker.Kind>()
            scan(changes)
            if (changes.isNotEmpty()) {
                tracker().externalChanges(changes)
            }
        } catch (t: Throwable) {
            // the watcher must not break the IDE: on any error skip this tick
            LOG.warn("Change watcher: polling failed", t)
        }
    }

    /** Incremental check: known files + directories with a changed mtime. */
    private fun scan(changes: MutableMap<String, ChangeTracker.Kind>) {
        // 1. Modified and deleted files (read the attributes in a single syscall).
        //    Copy the keys: the map must not be modified while iterating.
        val deleted = ArrayList<String>()
        for (path in files.keys.toList()) {
            val old = files[path] ?: continue
            val now = fingerprint(path)
            if (now == null) {
                deleted.add(path)
                changes[path] = ChangeTracker.Kind.DELETED
            } else if (now != old) {
                files[path] = now
                changes[path] = ChangeTracker.Kind.MODIFIED
            }
        }
        for (path in deleted) files.remove(path)

        // 2. New files: they can only appear in a directory whose
        //    mtime has changed, so only those directories are listed.
        val excludes = baseline.excludedDirs()
        for (dirPath in dirs.keys.toList()) {
            val dir = File(dirPath)
            if (!dir.isDirectory) {
                dirs.remove(dirPath)
                continue
            }
            val mtime = dir.lastModified()
            if (mtime == dirs[dirPath]) continue
            dirs[dirPath] = mtime
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (isExcludedDir(child.name, excludes)) continue
                    if (!dirs.containsKey(child.absolutePath)) {
                        addDirRecursive(child, excludes, changes)
                    }
                } else if (child.isFile) {
                    val path = child.absolutePath
                    if (!files.containsKey(path)) {
                        files[path] = fingerprint(path)
                            ?: Fingerprint(child.lastModified(), child.length())
                        changes[path] = ChangeTracker.Kind.CREATED
                    }
                }
            }
        }
    }

    /** Initial walk: builds an index of the project files and directories. */
    private fun fullScan(): Boolean {
        files.clear()
        dirs.clear()
        val excludes = baseline.excludedDirs()
        val roots = scanRoots()
        for (root in roots) {
            scanDirRecursive(root, excludes)
        }
        rootsKey = roots.joinToString("\n") { it.absolutePath }
        return dirs.isNotEmpty() || files.isNotEmpty()
    }

    private var rootsKey = ""

    /** A content root appeared/disappeared — the index must be rebuilt. */
    private fun rootsChanged(): Boolean {
        val key = scanRoots().joinToString("\n") { it.absolutePath }
        if (key == rootsKey) return false
        rootsKey = key
        return true
    }

    /**
     * What to scan: the project root (like the tracker, it resolves project files
     * relative to basePath) plus content roots if they lie outside it.
     * Nested roots are dropped to avoid walking the tree twice.
     */
    private fun scanRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        project.basePath?.let { File(it) }?.takeIf { it.isDirectory }?.let { roots.add(it) }
        roots.addAll(baseline.contentRootDirs())
        return roots.filter { candidate ->
            roots.none { other ->
                other != candidate && candidate.path.startsWith(other.path + File.separator)
            }
        }
    }

    private fun scanDirRecursive(dir: File, excludes: Set<String>) {
        dirs[dir.absolutePath] = dir.lastModified()
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (isExcludedDir(child.name, excludes)) continue
                scanDirRecursive(child, excludes)
            } else if (child.isFile) {
                files[child.absolutePath] = fingerprint(child.absolutePath)
                    ?: Fingerprint(child.lastModified(), child.length())
            }
        }
    }

    /** New directory (created/renamed): all files inside it are CREATED. */
    private fun addDirRecursive(
        dir: File,
        excludes: Set<String>,
        changes: MutableMap<String, ChangeTracker.Kind>,
    ) {
        if (!dir.isDirectory) return
        dirs[dir.absolutePath] = dir.lastModified()
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (isExcludedDir(child.name, excludes)) continue
                addDirRecursive(child, excludes, changes)
            } else if (child.isFile) {
                val path = child.absolutePath
                if (!files.containsKey(path)) {
                    files[path] = fingerprint(path)
                        ?: Fingerprint(child.lastModified(), child.length())
                    changes[path] = ChangeTracker.Kind.CREATED
                }
            }
        }
    }

    private fun isExcludedDir(name: String, excludes: Set<String>): Boolean =
        name == ".dsh" || name in excludes

    private fun fingerprint(path: String): Fingerprint? = try {
        val attrs = Files.readAttributes(
            Paths.get(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (attrs.isRegularFile) {
            Fingerprint(attrs.lastModifiedTime().toMillis(), attrs.size())
        } else {
            null
        }
    } catch (ignored: Exception) {
        null
    }

    override fun dispose() {
        stopped = true
        try {
            executor.shutdownNow()
        } catch (ignored: Exception) {
            // ignore
        }
    }
}
