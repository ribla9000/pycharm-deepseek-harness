package com.dsh.assistant.changes

import com.dsh.assistant.settings.DshAppSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores "before the change" snapshots of the project text files.
 * Needed to reject (revert) a change: restore the file to its state before the agent's edits.
 * It can also take full project snapshots for checkpoints (as in Cursor).
 *
 * For files that did not make it into the snapshot (too large/new), a git
 * fallback is used: the file version from HEAD/the index.
 */
@Service(Service.Level.PROJECT)
class BaselineStore(private val project: Project) : Disposable {

    /** Full project snapshot for a checkpoint. */
    class Snapshot(
        /** path → content (text files ≤ maxFileBytes, ≤ 20 MB in total). */
        val files: Map<String, String>,
        /** all project paths at the time of the snapshot (including binary/large ones). */
        val allPaths: Set<String>,
        val partial: Boolean,
    )

    private val app = DshAppSettings.getInstance()
    private val contents = ConcurrentHashMap<String, String>()
    private val storedBytes = AtomicLong()

    private val budgetBytes = 128L * 1024 * 1024

    @Volatile
    var scanning = false
        private set

    @Volatile
    var complete = false
        private set

    @Volatile
    var scannedFiles = 0
        private set

    fun excludedDirs(): Set<String> =
        app.appState.excludedDirs.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun excludedDirNames(): Set<String> = excludedDirs()

    private fun contentRoots(): List<File> =
        ProjectRootManager.getInstance(project).contentRoots
            .map { File(it.path) }
            .filter { it.isDirectory }

    /** Project content roots (needed by the file watcher). */
    fun contentRootDirs(): List<File> = contentRoots()

    /** Background scan of the project content roots. */
    fun startScan() {
        if (scanning) return
        scanning = true
        complete = false
        val roots = contentRoots()
        val excludes = excludedDirs()
        val maxBytes = app.appState.maxFileBytes
        Thread({
            try {
                var count = 0
                for (root in roots) {
                    count += walk(root, excludes) { f ->
                        if (capture(f, maxBytes)) 1 else 0
                    }
                    if (storedBytes.get() >= budgetBytes) break
                }
                scannedFiles = count
            } catch (ignored: Exception) {
                // scan is best-effort
            } finally {
                scanning = false
                complete = true
            }
        }, "dsh-baseline-scan").start()
    }

    /**
     * Walks the project files (same exclusions as the baseline) and passes each one to the callback.
     * @return false from the callback stops the walk.
     */
    fun walkProject(callback: (File) -> Boolean) {
        val excludes = excludedDirs()
        for (root in contentRoots()) {
            val r = walk(root, excludes) { f -> if (callback(f)) 0 else -1 }
            if (r < 0) return
        }
    }

    /** Project snapshot for a checkpoint. */
    fun snapshotFiles(): Snapshot {
        val files = LinkedHashMap<String, String>()
        val allPaths = LinkedHashSet<String>()
        var bytes = 0L
        val capBytes = 20L * 1024 * 1024
        val maxPaths = 50_000
        var partial = false
        val maxBytes = app.appState.maxFileBytes
        walkProject { f ->
            if (allPaths.size >= maxPaths) {
                partial = true
                false
            } else {
                allPaths.add(f.absolutePath)
                if (bytes < capBytes && f.length() <= maxBytes) {
                    val text = try {
                        val b = f.readBytes()
                        if (isBinary(b)) null else String(b, StandardCharsets.UTF_8)
                    } catch (e: Exception) {
                        null
                    }
                    if (text != null) {
                        files[f.absolutePath] = text
                        bytes += text.length
                    }
                }
                true
            }
        }
        return Snapshot(files, allPaths, partial)
    }

    /** All project file paths right now (for checkpoint rollback). */
    fun listAllPaths(): Set<String> {
        val paths = LinkedHashSet<String>()
        walkProject { f ->
            paths.add(f.absolutePath)
            true
        }
        return paths
    }

    /** visit: returns -1 = stop the walk, otherwise the number of files counted. */
    private fun walk(dir: File, excludes: Set<String>, visit: (File) -> Int): Int {
        val files = dir.listFiles() ?: return 0
        var count = 0
        for (f in files) {
            if (f.isDirectory) {
                val name = f.name
                if (name in excludes || name == ".dsh") continue
                val r = walk(f, excludes, visit)
                if (r < 0) return -1
                count += r
            } else if (f.isFile) {
                val r = visit(f)
                if (r < 0) return -1
                count += r
            }
        }
        return count
    }

    private fun capture(f: File, maxBytes: Int): Boolean {
        if (f.length() > maxBytes) return false
        // The file is already modified and awaits a decision — do not record the
        // changed content into the "before" snapshot (otherwise rollback restores the wrong version).
        if (project.getService(ChangeTracker::class.java).hasPending(f.absolutePath)) return false
        val bytes = try {
            f.readBytes()
        } catch (e: Exception) {
            return false
        }
        if (isBinary(bytes)) return false
        val path = f.absolutePath
        val old = contents[path]
        val text = String(bytes, StandardCharsets.UTF_8)
        if (old != null) storedBytes.addAndGet(-old.length.toLong())
        contents[path] = text
        storedBytes.addAndGet(text.length.toLong())
        return true
    }

    /** Snapshot before the change; null if the file did not exist or was not stored. */
    fun before(path: String): String? = contents[path]

    /** Refresh the snapshot (after a change is accepted/rejected). */
    fun update(path: String) {
        val f = File(path)
        if (f.isFile) capture(f, app.appState.maxFileBytes)
    }

    fun remove(path: String) {
        val old = contents.remove(path)
        if (old != null) storedBytes.addAndGet(-old.length.toLong())
    }

    /** File version from git (HEAD, then the index) — a fallback for rollback. */
    fun gitBefore(projectRelativePath: String): String? {
        val root = project.basePath ?: return null
        return runGit(root, listOf("show", "HEAD:$projectRelativePath"))
            ?: runGit(root, listOf("show", ":$projectRelativePath"))
    }

    private fun runGit(root: String, args: List<String>): String? {
        return try {
            val pb = ProcessBuilder(listOf("git", "-C", root) + args)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.readBytes()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return null
            }
            if (p.exitValue() != 0) null else String(out, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun isBinary(b: ByteArray): Boolean {
        val n = minOf(b.size, 8192)
        for (i in 0 until n) {
            if (b[i] == 0.toByte()) return true
        }
        return false
    }

    override fun dispose() = Unit
}
