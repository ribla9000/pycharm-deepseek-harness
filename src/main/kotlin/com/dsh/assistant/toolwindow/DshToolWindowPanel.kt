package com.dsh.assistant.toolwindow

import com.dsh.assistant.changes.ChangeTracker
import com.dsh.assistant.changes.ChangesPanel
import com.dsh.assistant.chat.ChatShell
import com.dsh.assistant.chat.ChatState
import com.dsh.assistant.chat.EditorContext
import com.dsh.assistant.sdk.DshSdkClient
import com.dsh.assistant.sdk.DshSessionStore
import com.dsh.assistant.settings.AgentsMdSync
import com.dsh.assistant.settings.DshAppSettings
import com.dsh.assistant.settings.DshProjectSettings
import com.dsh.assistant.db.DshDatabase
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.OnePixelSplitter
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Tool window panel: a single HTML interface (ChatShell) on the left and the
 * HTML "Changes" panel on the right (a thin PyCharm splitter). Error banner on top.
 */
class DshToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        private val KEY = com.intellij.openapi.util.Key.create<DshToolWindowPanel>("dsh.toolWindow.panel")

        fun obtain(project: Project): DshToolWindowPanel? = project.getUserData(KEY)

        fun getInstance(project: Project): DshToolWindowPanel? = project.getUserData(KEY)

        fun attach(project: Project): DshToolWindowPanel {
            project.getUserData(KEY)?.let { return it }
            val panel = DshToolWindowPanel(project)
            project.putUserData(KEY, panel)
            return panel
        }
    }

    private val appSettings = DshAppSettings.getInstance()
    private val projectSettings = DshProjectSettings(project)
    private val store = DshSessionStore(project)
    private val db: DshDatabase
        get() = DshDatabase.getInstance(project)
    private val sdk = DshSdkClient(project, appSettings)

    private val changesPanel = ChangesPanel(project)
    private val shell: ChatShell
    private var splitter: OnePixelSplitter? = null

    private var stuckTimer: javax.swing.Timer? = null
    private var sdkModelSnapshot = ""

    // deduplicate identical SDK errors so the chat does not get flooded with cards
    private var lastSdkErrorKey = ""
    private var lastSdkErrorAt = 0L

    init {
        layout = BorderLayout(0, 0)

        shell = ChatShell(
            project = project,
            sdk = sdk,
            store = store,
            tracker = project.getService(ChangeTracker::class.java),
            projectSettings = projectSettings,
            editorContextProvider = { editorContext() },
            onTurnFinished = { changesPanel.refresh() },
            onModelChange = { p, m, e -> changeModel(p, m, e) },
            onStopRequested = { stopAgent() },
            onChatsChanged = { persistChats() },
            onLayoutRequested = { cycleLayout() },
            onChangesToggle = { toggleChangesPanel() },
        )

        buildSplitter()

        // SDK events
        sdk.addListener(object : DshSdkClient.Listener {
            override fun onEvent(sessionId: String, event: com.fasterxml.jackson.databind.JsonNode) {
                SwingUtilities.invokeLater { shell.onSdkEvent(sessionId, event) }
            }

            override fun onStatus(status: DshSdkClient.Status, detail: String?) {
                SwingUtilities.invokeLater {
                    when (status) {
                        DshSdkClient.Status.READY -> shell.dispatchSdkStatus("ready")
                        DshSdkClient.Status.BUSY -> shell.dispatchSdkStatus("busy")
                        DshSdkClient.Status.STARTING -> shell.dispatchSdkStatus("starting", detail)
                        DshSdkClient.Status.FAILED -> {
                            shell.setAllRunning(false)
                            shell.dispatchSdkStatus("failed", detail)
                            reportSdkFailure("DSH SDK is unavailable: ${detail ?: "unknown error"}", detail)
                        }
                        DshSdkClient.Status.DOWN -> {
                            shell.setAllRunning(false)
                            shell.dispatchSdkStatus("down", detail)
                        }
                    }
                }
            }

            override fun onSessionStatus(sessionId: String, running: Boolean) {
                // turn states also come through events — here we only force a reset
                if (!running) {
                    SwingUtilities.invokeLater {
                        // the agent is done — look at the files right away so the history
                        // appears without delay, not after the IDE window is activated
                        project.getService(ChangeTracker::class.java).wakeWatcher()
                        changesPanel.refresh()
                    }
                }
            }

            override fun onPromptAccepted(sessionId: String) {
                SwingUtilities.invokeLater { shell.onPromptAccepted(sessionId) }
            }

            override fun onPromptError(sessionId: String, message: String) {
                SwingUtilities.invokeLater { shell.onPromptError(sessionId, message) }
            }

            override fun onProcessExit(code: Int?) {
                // the details are already shown via onStatus(FAILED) — no second card needed
            }
        })

        // settings changed through DSH (.dsh/pycharm-assistant.json) are applied on the fly
        sdkModelSnapshot = appSettings.appState.model
        projectSettings.addListener {
            ApplicationManager.getApplication().invokeLater {
                shell.refreshModelCombos()
                if (appSettings.appState.model != sdkModelSnapshot) {
                    sdkModelSnapshot = appSettings.appState.model
                    sdk.restart()
                }
            }
        }

        // check for "stuck" turns
        stuckTimer = javax.swing.Timer(30_000) {
            shell.checkStuck()
        }
        stuckTimer!!.start()

        importDshSettings()

        restoreChats()
        if (shell.chatIds().isEmpty()) {
            ensureInitialChat()
        }
        ApplicationManager.getApplication().invokeLater {
            AgentsMdSync.sync(project, appSettings.appState.syncAgentsMd)
        }
        if (appSettings.appState.autoConnect) {
            startSdk()
        }
        scanSessionStates()
    }

    // ---------- layout ----------

    private fun currentVertical(): Boolean = when (appSettings.appState.layoutMode) {
        "VERTICAL" -> true
        "HORIZONTAL" -> false
        else -> width <= height * 1.25
    }

    private fun buildSplitter() {
        splitter?.let { remove(it) }
        splitter = null
        if (!appSettings.appState.showChangesPanel) {
            add(shell, BorderLayout.CENTER)
            revalidate()
            repaint()
            return
        }
        val vertical = currentVertical()
        val s = OnePixelSplitter(vertical, if (vertical) 0.7f else 0.62f)
        s.firstComponent = shell
        s.secondComponent = changesPanel
        splitter = s
        add(s, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /** Show/hide the "Changes" panel (the splitter disappears with it). */
    private fun toggleChangesPanel() {
        appSettings.appState.showChangesPanel = !appSettings.appState.showChangesPanel
        if (appSettings.appState.showChangesPanel) {
            changesPanel.refresh()
        }
        buildSplitter()
    }

    private fun cycleLayout() {
        val modes = listOf("AUTO", "HORIZONTAL", "VERTICAL")
        val next = modes[(modes.indexOf(appSettings.appState.layoutMode) + 1) % modes.size]
        appSettings.appState.layoutMode = next
        if (!appSettings.appState.showChangesPanel) {
            appSettings.appState.showChangesPanel = true
        }
        buildSplitter()
    }

    // ---------- chats ----------

    fun newChat(): ChatState = shell.newChat()

    fun currentChat(): ChatState? = shell.currentChatState()

    fun focusInput() = shell.focusInput()

    private fun persistChats() {
        projectSettings.settings.openChats.clear()
        projectSettings.settings.openChats.addAll(shell.chatIds())
        projectSettings.save()
        appSettings.appState.chats.clear()
        appSettings.appState.chats.addAll(shell.chatIds())
        // persist EVERY tab, not just the active one: otherwise the title of a background
        // chat was lost and the IDE showed "New session" after a restart
        for (st in shell.allChatStates()) {
            db.upsertChat(
                id = st.chatId,
                title = st.storedTitle(),
                archived = false,
                state = projectSettings.settings.sessionStates[st.sessionId])
        }
    }

    private fun restoreChats() {
        // 1) SQLite database (survives a plugin reinstall)
        val dbChats = db.chats().filter { !it.archived && it.open }
        if (dbChats.isNotEmpty()) {
            for (c in dbChats) {
                if (c.id.isNotBlank()) shell.openChat(c.id)
            }
            if (shell.chatIds().isNotEmpty()) return
        }
        val ids = projectSettings.settings.openChats.toList()
        if (ids.isEmpty()) {
            val legacy = appSettings.appState.chats.toList()
            if (legacy.isNotEmpty()) {
                projectSettings.settings.openChats.addAll(legacy)
                projectSettings.save()
                for (id in legacy) {
                    if (id.isNotBlank()) shell.openChat(id)
                }
                return
            }
        }
        for (id in ids) {
            if (id.isNotBlank()) shell.openChat(id)
        }
    }

    /** If there are no tabs, open the latest project session instead of an empty new chat. */
    private fun ensureInitialChat() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val latest = try {
                store.listSessions().firstOrNull()
            } catch (e: Exception) {
                null
            }
            ApplicationManager.getApplication().invokeLater {
                if (shell.chatIds().isNotEmpty()) return@invokeLater
                if (latest != null) {
                    shell.openChat(latest.id)
                } else if (appSettings.appState.openChatOnStartup) {
                    shell.newChat()
                }
            }
        }
    }

    // ---------- session states ----------

    private fun scanSessionStates() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val infos = try {
                store.listSessions()
            } catch (e: Exception) {
                emptyList()
            }
            var changed = false
            for (info in infos) {
                if (projectSettings.settings.sessionStates[info.id] == null) {
                    val state = try {
                        store.readSessionState(info.id)
                    } catch (e: Exception) {
                        "new"
                    }
                    if (state != "new") {
                        projectSettings.settings.sessionStates[info.id] = state
                        db.setSessionState(info.id, state)
                        changed = true
                    }
                }
            }
            if (changed) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        projectSettings.save()
                    } catch (ignored: Exception) {
                        // ignore
                    }
                }
            }
        }
    }

    /** Settings and models come from the original DSH (~/.dsh) instead of being kept locally. */
    private fun importDshSettings() {
        if (!appSettings.appState.syncWithDsh) return
        val dsh = try {
            com.dsh.assistant.settings.DshConfigReader.read(project, appSettings.appState.dshCommand)
        } catch (e: Exception) {
            return
        }
        dsh.provider?.takeIf { it.isNotBlank() }?.let { appSettings.appState.provider = it }
        dsh.model?.takeIf { it.isNotBlank() }?.let { appSettings.appState.model = it }
        dsh.reasoningEffort?.let { appSettings.appState.reasoningEffort = it }
        if (dsh.models.isNotEmpty()) {
            appSettings.appState.models = dsh.models.joinToString(",") { it.id }
            appSettings.appState.modelLabels = dsh.models.joinToString(";") { "${it.id}=${it.name}" }
        }
    }

    // ---------- SDK ----------

    private fun startSdk() {
        sdkModelSnapshot = appSettings.appState.model
        sdk.start()
    }

    private fun stopAgent() {
        // instant stop: restart the SDK (session history is preserved)
        sdk.restart()
        shell.setAllRunning(false)
        // messages sent during a turn are still delivered
        shell.flushQueues()
    }

    private fun changeModel(provider: String, model: String, effort: String) {
        val unchanged = appSettings.appState.provider == provider &&
            appSettings.appState.model == model &&
            appSettings.appState.reasoningEffort == effort
        appSettings.appState.provider = provider
        appSettings.appState.model = model
        appSettings.appState.reasoningEffort = effort
        appSettings.appState.models = appSettings.appState.models.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .plus(model)
            .distinct()
            .joinToString(",")
        // choosing the same value must not restart the SDK process:
        // every restart = a new session (the SDK does not pick up saved sessions)
        if (unchanged) return
        sdkModelSnapshot = model
        sdk.restart()
        shell.setAllRunning(false)
    }

    // ---------- SDK errors ----------

    /**
     * An error goes to the chat as a card with an expandable traceback (which can be copied),
     * not to the top banner: the banner appeared/disappeared and pushed the whole panel around.
     */
    private fun reportSdkFailure(message: String, detail: String?) {
        val details = sdkFailureDetails(detail)
        val key = message + "\u0000" + details
        val now = System.currentTimeMillis()
        if (key == lastSdkErrorKey && now - lastSdkErrorAt < 60_000) return
        lastSdkErrorKey = key
        lastSdkErrorAt = now
        try {
            shell.reportSdkFailure(message, details)
        } catch (ignored: Exception) {
            // ignore
        }
    }

    /** Full failure context: command, code, time, stderr tail. */
    private fun sdkFailureDetails(detail: String?): String {
        val sb = StringBuilder()
        sb.append("Process: ").append(appSettings.appState.dshCommand.trim())
            .append(" --profile sdk").append('\n')
        sb.append("Project: ").append(project.basePath ?: "n/a").append('\n')
        sb.append("Model: ").append(appSettings.appState.model)
            .append(" · effort: ").append(appSettings.appState.reasoningEffort.ifBlank { "auto" }).append('\n')
        sb.append("Time: ")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())).append('\n')
        if (!detail.isNullOrBlank()) sb.append("Reason: ").append(detail).append('\n')
        sb.append('\n').append("stderr (last lines):").append('\n')
        val tail = try {
            sdk.recentStderr()
        } catch (ignored: Exception) {
            emptyList()
        }
        sb.append(if (tail.isEmpty()) "(empty)" else tail.joinToString("\n"))
        return sb.toString()
    }

    // ---------- editor context ----------

    private fun editorContext(): EditorContext? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val path = VfsUtilCore.getRelativePath(file, project.baseDir) ?: file.path
        val text = editor.document.text
        val sel = editor.selectionModel.selectedText
        val selection = if (sel.isNullOrBlank()) null else sel
        val excerpt = (selection ?: text).take(appSettings.appState.maxContextChars)
        return EditorContext(path, selection, excerpt)
    }

    /** Cursor Ctrl+L: ask about the selection/the file. */
    fun askAboutSelection() {
        val ctx = editorContext() ?: return
        val chat = currentChat() ?: newChat()
        chat.addEditorContext(ctx)
        showToolWindow()
        focusInput()
    }

    /** Cursor Ctrl+K: edit the selection according to an instruction. */
    fun editSelection(instruction: String) {
        val ctx = editorContext() ?: return
        val chat = currentChat() ?: newChat()
        chat.sendInlineEdit(ctx.path, ctx.selection, instruction)
        showToolWindow()
    }

    fun showToolWindow() {
        val tw = ToolWindowManager.getInstance(project).getToolWindow("DSH Assistant") ?: return
        if (!tw.isVisible) tw.show { }
        tw.activate(null)
    }

    override fun dispose() {
        project.putUserData(KEY, null)
        stuckTimer?.stop()
        stuckTimer = null
        if (shell.chatIds().isNotEmpty()) persistChats()
        Disposer.dispose(shell)
        sdk.dispose()
        if (changesPanel is Disposable) changesPanel.dispose()
        AgentsMdSync.sync(project, appSettings.appState.syncAgentsMd)
    }
}
