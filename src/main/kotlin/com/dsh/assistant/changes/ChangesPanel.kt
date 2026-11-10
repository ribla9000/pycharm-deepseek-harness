package com.dsh.assistant.changes

import com.dsh.assistant.changes.ChangeTracker.Entry
import com.dsh.assistant.changes.ChangeTracker.Status
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel

/**
 * "Changes" panel on HTML/JCEF: a file tree grouped by folders (like in Explorer),
 * search, opening in PyCharm, unique action icons, checkpoints.
 * The logic (tracker, baseline, rollback) stays in Java.
 */
class ChangesPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val tracker = project.getService(ChangeTracker::class.java)
    private val mapper = ObjectMapper()

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) {
        val builder = JBCefBrowser.createBuilder()
        if (!com.dsh.assistant.settings.DshAppSettings.getInstance().appState.useHardwareBrowser) {
            builder.setOffScreenRendering(true)
        }
        builder.build()
    } else {
        null
    }
    private var jsQuery: JBCefJSQuery? = null
    private val pendingOps = CopyOnWriteArrayList<String>()

    @Volatile
    private var jsReady = false

    init {
        tracker.changeListener = { refresh() }
        val b = browser
        if (b != null) {
            add(b.component, BorderLayout.CENTER)
            jsQuery = JBCefJSQuery.create(b)
            jsQuery!!.addHandler { request ->
                try {
                    handleQuery(request)
                } catch (e: Exception) {
                    // ignore
                }
                JBCefJSQuery.Response("")
            }
            loadPage()
        } else {
            add(JBLabel("<html><div style='padding:16px'>JCEF is not available — PyCharm 2024.3+ is required.</div></html>"), BorderLayout.CENTER)
        }
    }

    // ---------- page and bridge ----------

    private fun loadPage() {
        val b = browser ?: return
        val html = try {
            javaClass.getResourceAsStream("/changes/changes.html")?.bufferedReader(Charsets.UTF_8)?.readText()
        } catch (e: Exception) {
            null
        } ?: "<html><body>changes.html not found</body></html>"

        val init = mapper.createObjectNode()
        init.put("theme", "dark")
        init.put("queryFunc", jsQuery?.funcName ?: "")
        val script = "<script>window.DSH_INIT = ${mapper.writeValueAsString(init)}</script>"
        val full = html.replace("</head>", script + "</head>")
        b.loadHTML(full)
    }

    private fun handleQuery(request: String) {
        // JBCefJSQuery runs on the CEF thread — all IDE model work happens on the EDT
        ApplicationManager.getApplication().invokeLater {
            handleQueryOnEdt(request)
        }
    }

    private fun handleQueryOnEdt(request: String) {
        val node = try {
            mapper.readTree(request)
        } catch (e: Exception) {
            return
        }
        val path = node.get("path")?.asText()
        when (node.get("type")?.asText()) {
            "ready" -> {
                jsReady = true
                flushPendingOps()
                tracker.wakeWatcher()
                refresh()
            }
            "accept" -> path?.let { p -> entryOf(p)?.let { tracker.accept(it) } }
            "reject" -> path?.let { p -> entryOf(p)?.let { tracker.reject(it) } }
            "acceptAll" -> tracker.acceptAllPending()
            "rejectAll" -> tracker.rejectAllPending()
            "diff" -> path?.let { showDiff(it) }
            "open" -> path?.let { openFile(it) }
            "versionDiff" -> {
                val p = path ?: return
                val id = node.get("id")?.asLong() ?: return
                val v = com.dsh.assistant.db.DshDatabase.getInstance(project).fileVersion(p, id) ?: return
                showTextDiff("${p} — version ${SimpleDateFormat("dd.MM HH:mm:ss").format(java.util.Date(v.createdAt))}", v.before, v.after)
            }
            "refresh" -> {
                VirtualFileManager.getInstance().asyncRefresh { }
                // do not wait for VFS: the watcher checks the files directly and refreshes the panel
                tracker.wakeWatcher()
                refresh()
            }
            "clear" -> tracker.clearFinished()
            "snapshot" -> {
                tracker.createCheckpoint("Snapshot " + SimpleDateFormat("HH:mm:ss").format(Date()))
                refresh()
            }
            "rollback" -> {
                val idx = node.get("index")?.asInt() ?: return
                val cp = tracker.checkpointsList().getOrNull(idx) ?: return
                val yes = Messages.showYesNoDialog(
                    project,
                    "Restore the project files to the state of \"${cp.name}\"?\n" +
                        "Changes made after the snapshot will be reverted.",
                    "DSH Assistant",
                    Messages.getQuestionIcon())
                if (yes == Messages.YES) {
                    tracker.rollbackTo(cp)
                }
            }
        }
    }

    private fun entryOf(path: String): Entry? =
        tracker.entriesList().firstOrNull { it.path == path }

    private fun dispatchOp(op: String, body: ObjectNode) {
        body.put("op", op)
        dispatchRaw(mapper.writeValueAsString(body))
    }

    private fun dispatchRaw(json: String) {
        val b = browser ?: return
        if (!jsReady) {
            pendingOps.add(json)
            if (pendingOps.size > 200) pendingOps.removeAt(0)
            return
        }
        try {
            b.cefBrowser.executeJavaScript("window.dsh && window.dsh.dispatch($json)", b.cefBrowser.url, 0)
        } catch (ignored: Exception) {
            // ignore
        }
    }

    private fun flushPendingOps() {
        val b = browser ?: return
        for (op in pendingOps) {
            try {
                b.cefBrowser.executeJavaScript("window.dsh && window.dsh.dispatch($op)", b.cefBrowser.url, 0)
            } catch (ignored: Exception) {
                // ignore
            }
        }
        pendingOps.clear()
    }

    // ---------- data ----------

    fun refresh() {
        val entries = tracker.entriesList()
            .sortedWith(compareBy({ it.status != Status.PENDING }, { it.display }))
        val op = mapper.createObjectNode()
        val arr = op.putArray("entries")
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss")
        val db = com.dsh.assistant.db.DshDatabase.getInstance(project)
        for (e in entries) {
            val o = arr.addObject()
            o.put("path", e.path)
            o.put("display", e.display)
            o.put("kind", e.kind.name)
            o.put("status", e.status.name)
            o.put("added", e.added)
            o.put("deleted", e.deleted)
            o.put("revertUnavailable", e.revertUnavailable)
            // file version history (git-like)
            // important: putObject into the element itself. addObject() here added an
            // extra object without path/display to the entries array and broke the "All" filter.
            val versions = o.putObject("versions")
            val va = versions.putArray("items")
            for (v in db.fileVersions(e.path)) {
                val vo = va.addObject()
                vo.put("id", v.id)
                vo.put("time", fmt.format(java.util.Date(v.createdAt)))
                vo.put("added", v.added)
                vo.put("deleted", v.deleted)
            }
        }
        dispatchOp("entries", op)

        val counts = mapper.createObjectNode()
        counts.put("pending", tracker.pendingCount())
        counts.put("accepted", tracker.acceptedCount())
        counts.put("rejected", tracker.rejectedCount())
        dispatchOp("counts", counts)

        pushCheckpoints()
    }

    private fun pushCheckpoints() {
        val op = mapper.createObjectNode()
        val arr = op.putArray("items")
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss")
        for (cp in tracker.checkpointsList()) {
            val o = arr.addObject()
            o.put("name", cp.name)
            o.put("time", fmt.format(Date(cp.time)))
        }
        dispatchOp("checkpoints", op)
    }

    // ---------- diff and opening ----------

    private fun showTextDiff(title: String, before: String, after: String) {
        try {
            val factory = DiffContentFactory.getInstance()
            val request = SimpleDiffRequest(title, factory.create(before), factory.create(after), "Before", "After")
            DiffManager.getInstance().showDiff(project, request)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not show the diff: ${e.message}", "DSH Assistant")
        }
    }

    private fun showDiff(path: String) {
        try {
            val entry = entryOf(path) ?: return
            val before = entry.beforeText ?: ""
            val after = when (entry.kind) {
                ChangeTracker.Kind.DELETED -> ""
                else -> readCurrent(path)
            }
            val factory = DiffContentFactory.getInstance()
            val title1 = when (entry.kind) {
                ChangeTracker.Kind.CREATED -> "File did not exist"
                else -> "Before (pre-change state)"
            }
            val title2 = when (entry.kind) {
                ChangeTracker.Kind.DELETED -> "File was deleted"
                else -> "After"
            }
            val request = SimpleDiffRequest(
                "${entry.display} — changes",
                factory.create(before),
                factory.create(after),
                title1,
                title2)
            DiffManager.getInstance().showDiff(project, request)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Could not show the diff: ${e.message}",
                "DSH Assistant")
        }
    }

    private fun readCurrent(path: String): String {
        val f = File(path)
        if (!f.isFile) return ""
        return try {
            f.readText(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun openFile(path: String) {
        try {
            val f = File(path)
            val vf = LocalFileSystem.getInstance().findFileByIoFile(f)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
                ?: return
            FileEditorManager.getInstance(project).openFile(vf, true)
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Could not open the file: ${e.message}",
                "DSH Assistant")
        }
    }

    override fun dispose() {
        tracker.changeListener = null
        jsQuery?.let { com.intellij.openapi.util.Disposer.dispose(it) }
        jsQuery = null
        browser?.dispose()
    }
}
