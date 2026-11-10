package com.dsh.assistant.chat

import com.dsh.assistant.changes.ChangeTracker
import com.dsh.assistant.db.DshDatabase
import com.dsh.assistant.sdk.DshSdkClient
import com.dsh.assistant.sdk.DshSessionStore
import com.dsh.assistant.settings.DshAppSettings
import com.dsh.assistant.settings.DshProjectSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel

/**
 * Single HTML chat UI: one page shell.html — tabs, chats, menu, chat history.
 * All chat logic lives in ChatState; the page receives operations with a chatId.
 */
class ChatShell(
    private val project: Project,
    private val sdk: DshSdkClient,
    private val store: DshSessionStore,
    private val tracker: ChangeTracker,
    private val projectSettings: DshProjectSettings,
    private val editorContextProvider: () -> EditorContext?,
    private val onTurnFinished: () -> Unit,
    private val onModelChange: (provider: String, model: String, effort: String) -> Unit,
    private val onStopRequested: () -> Unit,
    private val onChatsChanged: () -> Unit,
    private val onLayoutRequested: () -> Unit,
    private val onChangesToggle: () -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val app = DshAppSettings.getInstance()
    private val db: DshDatabase
        get() = DshDatabase.getInstance(project)
    private val mapper = ObjectMapper()

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) {
        // hardware rendering (the default) is noticeably smoother than OSR;
        // OSR stays available as an option in case of repaint issues
        val builder = JBCefBrowser.createBuilder()
        if (!app.appState.useHardwareBrowser) {
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

    /** chats by chatId (a stable id; sessionId changes on rotations). */
    private val states = LinkedHashMap<String, ChatState>()
    private val order = java.util.ArrayList<String>()
    private var activeId: String? = null

    @Volatile
    private var lastStuckCheck = System.currentTimeMillis()

    init {
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
        }
        // the editor scheme changed (Darcula/Light/custom) — refresh the code highlight colors in the chat
        project.messageBus.connect(this).subscribe(
            EditorColorsManager.TOPIC,
            EditorColorsListener { _ ->
                val o = mapper.createObjectNode()
                o.put("op", "codeColors")
                o.set<JsonNode>("colors", codeColors())
                dispatchNode(o)
            })

        // The OSR browser sometimes flickers on resize — ask for a repaint
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                browser?.component?.let { c ->
                    c.invalidate()
                    c.repaint()
                }
            }

            override fun componentShown(e: java.awt.event.ComponentEvent) {
                browser?.component?.repaint()
            }
        })
    }

    // ---------- page and bridge ----------

    private fun loadPage() {
        val b = browser ?: return
        val html = try {
            javaClass.getResourceAsStream("/shell/shell.html")?.bufferedReader(Charsets.UTF_8)?.readText()
        } catch (e: Exception) {
            null
        } ?: "<html><body>shell.html not found</body></html>"

        val init = mapper.createObjectNode()
        init.put("theme", "dark")
        init.put("mode", app.appState.lastMode)
        init.put("model", app.appState.model)
        val modelsArr = init.putArray("modelList")
        for (m in app.appState.models.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            modelsArr.add(m)
        }
        val labels = mapper.createObjectNode()
        for (pair in app.appState.modelLabels.split(";").map { it.trim() }.filter { it.contains("=") }) {
            val id = pair.substringBefore("=").trim()
            val name = pair.substringAfter("=").trim()
            if (id.isNotEmpty()) labels.put(id, name)
        }
        init.set<com.fasterxml.jackson.databind.JsonNode>("modelLabels", labels)
        init.put("effort", app.appState.reasoningEffort)
        init.put("policy", "never")
        init.put("fontScale", app.appState.chatFontScale)
        init.put("queryFunc", jsQuery?.funcName ?: "")
        init.set<com.fasterxml.jackson.databind.JsonNode>("codeColors", codeColors())

        val script = "<script>window.DSH_INIT = ${mapper.writeValueAsString(init)}</script>"
        val full = html.replace("</head>", script + "</head>")
        b.loadHTML(full)
    }

    /**
     * Code highlight colors from the PyCharm editor scheme: the chat is always dark, so
     * in a light theme we take a dark scheme (Darcula/Dark), otherwise the user's active one.
     */
    private fun codeColors(): ObjectNode = try {
        val mgr = EditorColorsManager.getInstance()
        val scheme: EditorColorsScheme? = if (mgr.isDarkEditor) {
            mgr.globalScheme
        } else {
            mgr.getScheme("Darcula") ?: mgr.getScheme("Dark") ?: mgr.globalScheme
        }
        codeColorsFor(scheme)
    } catch (e: Exception) {
        mapper.createObjectNode()
    }

    private fun codeColorsFor(scheme: EditorColorsScheme?): ObjectNode {
        val node = mapper.createObjectNode()
        if (scheme == null) return node
        fun put(name: String, c: java.awt.Color?) {
            if (c != null) node.put(name, String.format("#%06x", c.rgb and 0xFFFFFF))
        }
        fun fg(key: TextAttributesKey?): java.awt.Color? = try {
            key?.let { scheme.getAttributes(it, true)?.foregroundColor }
        } catch (e: Exception) {
            null
        }
        put("kw", fg(DefaultLanguageHighlighterColors.KEYWORD))
        put("str", fg(DefaultLanguageHighlighterColors.STRING))
        put("num", fg(DefaultLanguageHighlighterColors.NUMBER))
        put("com", fg(DefaultLanguageHighlighterColors.LINE_COMMENT))
        put("fn", fg(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION))
        put("dec", fg(DefaultLanguageHighlighterColors.METADATA))
        put("const", fg(DefaultLanguageHighlighterColors.CONSTANT))
        put("text", try { scheme.defaultForeground } catch (e: Exception) { null })
        put("bg", try { scheme.defaultBackground } catch (e: Exception) { null })
        val comment = try {
            scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT, true)
        } catch (e: Exception) {
            null
        }
        node.put("comItalic", comment != null && (comment.fontType and java.awt.Font.ITALIC) != 0)
        return node
    }

    private fun handleQuery(request: String) {
        // JBCefJSQuery runs on the CEF thread — all IDE model work goes to the EDT
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
        val chatId = node.get("chatId")?.asText()
        when (node.get("type")?.asText()) {
            "ready" -> {
                jsReady = true
                // pending operations already contain chatAdded for every chat —
                // re-sending them would create duplicate sections in the UI
                flushPendingOps()
            }
            "newChat" -> newChat()
            "closeChat" -> chatId?.let { closeChat(it) }
            "selectChat" -> chatId?.let { activate(it) }
            "renameChat" -> {
                val st = chatId?.let { states[it] } ?: return
                val name = node.get("name")?.asText()?.trim()
                if (!name.isNullOrBlank()) {
                    projectSettings.settings.sessionNames[st.chatId] = name
                    projectSettings.save()
                    st.setCustomTitle(name)
                    dispatchTitle(st)
                    onChatsChanged()
                }
            }
            "deleteChat" -> chatId?.let { id ->
                deleteChatWithConfirm(id, states[id]?.title())
            }
            "historyOpen" -> pushHistory()
            "historyOpenChat" -> node.get("id")?.asText()?.let { openChat(it) }
            "historyDelete" -> {
                val id = node.get("id")?.asText() ?: return
                val title = node.get("title")?.asText()
                if (confirmDelete(id, title)) {
                    deleteChatForever(id)
                    // rebuild the list only after the journal files are deleted,
                    // otherwise a deleted session managed to get back into history
                    pushHistory()
                }
            }
            "menuWeb" -> openWebDsh()
            "menuLayout" -> onLayoutRequested()
            "menuChanges" -> onChangesToggle()
            "menuRestart" -> onStopRequested()
            "menuSettings" -> {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "DSH Assistant")
            }
            "fontDec" -> changeFontScale(-10)
            "fontInc" -> changeFontScale(+10)
            else -> {
                // operations of a specific chat
                chatId?.let { states[it] }?.handleChatOp(node)
            }
        }
    }

    // ---------- dispatch ----------

    private fun dispatchNode(node: ObjectNode) {
        dispatchRaw(mapper.writeValueAsString(node))
    }

    private fun dispatchRaw(json: String) {
        val b = browser ?: return
        if (!jsReady) {
            pendingOps.add(json)
            if (pendingOps.size > 2000) pendingOps.removeAt(0)
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

    private fun dispatchOp(op: String, body: ObjectNode) {
        body.put("op", op)
        dispatchNode(body)
    }

    private fun dispatchChatAdded(st: ChatState) {
        val o = mapper.createObjectNode()
        o.put("op", "chatAdded")
        o.put("chatId", st.chatId)
        o.put("title", st.title())
        dispatchNode(o)
        st.refreshModelCombo()
        st.pushMetricsIfAny()
    }

    private fun dispatchTitle(st: ChatState) {
        val o = mapper.createObjectNode()
        o.put("op", "chatTitle")
        o.put("chatId", st.chatId)
        o.put("text", st.title())
        dispatchNode(o)
    }

    // ---------- chats ----------

    fun newChat(): ChatState {
        val id = "pycharm-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        return openChat(id)
    }

    fun openChat(sessionId: String): ChatState {
        states[sessionId]?.let { activate(it.chatId); return it }
        lateinit var st: ChatState
        st = ChatState(
            project = project,
            sdk = sdk,
            store = store,
            tracker = tracker,
            projectSettings = projectSettings,
            sessionId = sessionId,
            editorContextProvider = editorContextProvider,
            onTitleChanged = { t ->
                dispatchTitle(st)
                onChatsChanged()
            },
            onTurnFinished = { onTurnFinished() },
            onModelChange = { p, m, e -> onModelChange(p, m, e) },
            onStopRequested = { onStopRequested() },
            dispatcher = { node -> dispatchNode(node) },
        )
        states[st.chatId] = st
        order.add(st.chatId)
        // do not reset the status of an existing chat: opening from history must not
        // turn "answered/error" into "new session"
        if (projectSettings.settings.sessionStates[sessionId] == null) {
            projectSettings.settings.sessionStates[sessionId] = "new"
        }
        projectSettings.save()
        dispatchChatAdded(st)
        activate(st.chatId)
        onChatsChanged()
        return st
    }

    fun closeChat(chatId: String) {
        val st = states.remove(chatId) ?: return
        // A chat opened from "Chat history" might not have sent anything in this session,
        // but it already has history. It used to be treated as empty, and on close
        // its events were deleted — after reopening the chat was empty.
        if (st.hasContent() || st.hasPersistedHistory()) {
            db.upsertChat(
                id = st.chatId,
                title = st.storedTitle(),
                archived = false,
                state = projectSettings.settings.sessionStates[st.sessionId],
                open = false)
        } else {
            // a truly empty chat has no events, so there is nothing to delete
            db.deleteChat(st.chatId)
        }
        removeChatView(chatId)
    }

    /** Removes the tab from the UI without touching the saved chat data. */
    private fun removeChatView(chatId: String) {
        order.remove(chatId)
        val o = mapper.createObjectNode()
        o.put("op", "chatRemoved")
        o.put("chatId", chatId)
        dispatchNode(o)
        if (activeId == chatId) {
            activeId = order.lastOrNull()
            activate(activeId)
        }
        onChatsChanged()
    }

    /** Deletes a chat with confirmation: shared by history and the tab context menu. */
    private fun deleteChatWithConfirm(id: String, title: String?) {
        if (!confirmDelete(id, title)) return
        // remove the tab first so that persistChats does not put the record back into SQLite
        states.remove(id)
        removeChatView(id)
        deleteChatForever(id)
    }

    private fun confirmDelete(id: String, title: String?): Boolean {
        val name = ChatTitles.real(title)
            ?: ChatTitles.real(db.chatTitle(id))
            ?: id
        val yes = Messages.showYesNoDialog(
            project,
            "Delete chat \"${name.take(60)}\" permanently?\nThe history and the DSH session will be erased irreversibly.",
            "DSH Assistant",
            Messages.getQuestionIcon())
        return yes == Messages.YES
    }

    private fun activate(chatId: String?) {
        activeId = chatId
        val o = mapper.createObjectNode()
        o.put("op", "chatActive")
        if (chatId != null) o.put("chatId", chatId)
        dispatchNode(o)
    }

    fun currentChatState(): ChatState? = activeId?.let { states[it] }

    /** All open chats in tab order (needed to save the title of each one). */
    fun allChatStates(): List<ChatState> = order.mapNotNull { states[it] }

    fun chatIds(): List<String> = order.toList()

    /** Sends an SDK event to the chat that owns the session. */
    fun onSdkEvent(sessionId: String, event: JsonNode) {
        val st = states.values.firstOrNull { it.sessionId == sessionId } ?: return
        st.onSdkEvent(event)
    }

    /** The SDK accepted the request — pin the process generation for this session. */
    fun onPromptAccepted(sessionId: String) {
        states.values.firstOrNull { it.sessionId == sessionId }?.adoptGeneration()
    }

    fun onPromptError(sessionId: String, message: String) {
        val st = states.values.firstOrNull { it.sessionId == sessionId } ?: return
        // the request did not go out — there will be no turn, the UI must not stay "running"
        st.setRunning(false)
        if (message.contains("already exists", ignoreCase = true)) {
            // the id is taken in the store: the SDK does not pick up a saved session, so
            // retry the request in a new session — without a red error, with an explanation
            val old = st.sessionId
            st.retryInNewSession()
            if (old != st.chatId) db.deleteChat(old)
            onChatsChanged()
            return
        }
        projectSettings.settings.sessionStates[sessionId] = "error"
        projectSettings.save()
        st.appendError(message)
        // no turn started — the queue can be flushed
        st.flushQueue()
    }

    /** After the agent stops the queue must not get stuck — send the next message. */
    fun flushQueues() {
        for (st in states.values) st.flushQueue()
    }

    fun setAllRunning(r: Boolean) {
        for (st in states.values) st.setRunning(r)
    }

    fun refreshModelCombos() {
        for (st in states.values) st.refreshModelCombo()
    }

    fun checkStuck() {
        for (st in states.values) st.checkStuck()
    }

    fun dispatchSdkStatus(state: String, detail: String? = null) {
        val o = mapper.createObjectNode()
        o.put("op", "sdkStatus")
        o.put("state", state)
        if (!detail.isNullOrBlank()) o.put("detail", detail)
        dispatchNode(o)
    }

    /**
     * Shows an SDK error right in the chat: a short text + traceback/log under an expander
     * (can be copied and sent to support).
     */
    fun reportSdkFailure(message: String, details: String? = null) {
        val st = currentChatState() ?: if (states.isEmpty()) newChat() else states.values.first()
        st.appendError(message, details)
    }

    private fun changeFontScale(delta: Int) {
        val next = (app.appState.chatFontScale + delta).coerceIn(80, 140)
        app.appState.chatFontScale = next
        val o = mapper.createObjectNode()
        o.put("op", "fontScale")
        o.put("scale", next)
        dispatchNode(o)
    }

    // ---------- chat history ----------

    private fun pushHistory() {
        // ids of open tabs: they must not appear in "Chat history".
        // Snapshot on the EDT — states is not thread-safe, and reading runs in the background.
        val openIds = HashSet<String>()
        for (st in states.values) {
            openIds.add(st.chatId)
            openIds.add(st.sessionId)
            openIds.addAll(projectSettings.settings.sessionChains[st.chatId].orEmpty())
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val items = mapper.createArrayNode()
            val chats = ArrayList<Triple<String, String, Long>>()
            val seen = HashSet<String>()
            for (c in db.chats().filter { !it.open && it.id !in openIds }) {
                if (seen.add(c.id)) {
                    // old records may have kept the placeholder — recover the real title
                    val chain = projectSettings.settings.sessionChains[c.id].orEmpty()
                    val title = ChatTitles.real(c.title)
                        ?: ChatTitles.resolve(db, c.id, chain)
                        ?: c.id
                    chats.add(Triple(c.id, title, c.openedAt))
                }
            }
            try {
                for (info in store.listSessions()) {
                    if (info.id !in openIds && seen.add(info.id)) {
                        chats.add(Triple(info.id, info.title ?: info.id, info.createdAt ?: 0L))
                    }
                }
            } catch (ignored: Exception) {
                // ignore
            }
            chats.sortByDescending { it.third }
            val fmtDay = SimpleDateFormat("MM-dd-yyyy")
            val fmtTime = SimpleDateFormat("HH:mm")
            var currentDay = ""
            for ((id, title, time) in chats) {
                val day = fmtDay.format(Date(time))
                if (day != currentDay) {
                    currentDay = day
                    val g = items.addObject()
                    g.put("kind", "group")
                    g.put("label", day)
                }
                val c = items.addObject()
                c.put("kind", "chat")
                c.put("id", id)
                c.put("title", title)
                c.put("time", fmtTime.format(Date(time)))
                val state = projectSettings.settings.sessionStates[id]
                    ?: db.sessions().firstOrNull { it.id == id }?.state
                if (state != null) c.put("state", state)
            }
            ApplicationManager.getApplication().invokeLater {
                val o = mapper.createObjectNode()
                o.put("op", "history")
                o.put("visible", 20)
                o.set<JsonNode>("items", items)
                dispatchNode(o)
            }
        }
    }

    private fun deleteChatForever(id: String) {
        // after an id rotation the history lives in the child sessions of the chain:
        // without deleting them the chat came back to history under another id.
        // If a child id is selected in history, find the chain's owning chat.
        val owner = try {
            projectSettings.settings.sessionChains.entries
                .firstOrNull { id in it.value }?.key
        } catch (e: Exception) {
            null
        }
        val rootId = owner ?: id
        val chain = projectSettings.settings.sessionChains[rootId].orEmpty().toList()
        val ids = (chain + rootId + id).distinct()
        for (sid in ids) {
            projectSettings.settings.archivedSessions.remove(sid)
            projectSettings.settings.sessionNames.remove(sid)
            projectSettings.settings.sessionStates.remove(sid)
        }
        projectSettings.settings.sessionChains.remove(rootId)
        if (rootId != id) projectSettings.settings.sessionChains.remove(id)
        projectSettings.save()
        for (sid in ids) {
            db.deleteChat(sid)
            db.deleteEvents(sid)
            db.deleteSession(sid)
        }
        // delete the on-disk journal synchronously: pushHistory() is called right after
        // and otherwise managed to find the not-yet-deleted session again
        for (sid in ids) {
            try {
                store.deleteSession(sid)
            } catch (ignored: Exception) {
                // ignore
            }
        }
    }

    private fun openWebDsh() {
        // web DSH opens in an external browser — the embedded tab was replaced by the unified UI
        val url = projectSettings.settings.tokenUrl.ifBlank { projectSettings.settings.dshUrl }
        BrowserUtil.browse(url)
    }

    // ---------- API for the panel ----------

    fun focusInput() {
        val o = mapper.createObjectNode()
        o.put("op", "focus")
        activeId?.let { o.put("chatId", it) }
        dispatchNode(o)
    }

    fun sendActive() {
        val o = mapper.createObjectNode()
        o.put("op", "send")
        o.put("text", "")
        activeId?.let { o.put("chatId", it) }
        states.values.firstOrNull { it.chatId == activeId }?.handleChatOp(o)
    }

    fun addEditorContextToActive(ctx: EditorContext) {
        currentChatState()?.addEditorContext(ctx)
    }

    fun sendInlineEditToActive(path: String, selection: String?, instruction: String) {
        currentChatState()?.sendInlineEdit(path, selection, instruction)
    }

    fun disposeChats() {
        states.clear()
        order.clear()
        activeId = null
    }

    override fun dispose() {
        jsQuery?.let { Disposer.dispose(it) }
        jsQuery = null
        browser?.dispose()
    }
}
