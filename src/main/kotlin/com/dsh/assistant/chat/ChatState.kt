package com.dsh.assistant.chat

import com.dsh.assistant.changes.ChangeTracker
import com.dsh.assistant.sdk.DshSdkClient
import com.dsh.assistant.sdk.DshSessionStore
import com.dsh.assistant.settings.DshAppSettings
import com.dsh.assistant.settings.DshProjectSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap

data class EditorContext(
    val path: String,
    val selection: String?,
    val excerpt: String,
)

/**
 * Logic of a single chat: SDK requests, context, metrics, checkpoints, history.
 * Display — the unified shell.html page (ChatShell), operations go through the dispatcher.
 */
class ChatState(
    private val project: Project,
    private val sdk: DshSdkClient,
    private val store: DshSessionStore,
    private val tracker: ChangeTracker,
    private val projectSettings: DshProjectSettings,
    var sessionId: String,
    private val editorContextProvider: () -> EditorContext?,
    private val onTitleChanged: (String) -> Unit,
    private val onTurnFinished: () -> Unit,
    private val onModelChange: (provider: String, model: String, effort: String) -> Unit,
    private val onStopRequested: () -> Unit,
    private val dispatcher: (ObjectNode) -> Unit,
) {

    class ContextItem(val path: String, val content: String)

    class PendingImage(val id: String, val path: String, val base64: String, val mime: String)

    private val app = DshAppSettings.getInstance()
    private val mapper = ObjectMapper()
    private val metrics = ChatMetrics()

    @Volatile
    private var running = false

    @Volatile
    private var historyLoaded = false

    private var customTitlePinned = false
    private var customTitle: String? = null

    @Volatile
    private var lastEventTime = System.currentTimeMillis()

    @Volatile
    private var stuckHintShown = false

    private val contextItems = LinkedHashMap<String, ContextItem>()
    private val autoContextPaths = java.util.HashSet<String>()
    private val pendingImages = java.util.ArrayList<PendingImage>()
    private var lastMetricsUiUpdate = 0L
    val chatId: String = sessionId
    @Volatile
    private var toolsSent = false
    private val toolDescriptions = HashMap<String, String>()
    @Volatile
    private var contentFlag = false

    /**
     * The chat already had a saved history (it was opened from "Chat history").
     * Without this flag, closing a reopened chat treated it as empty and deleted
     * records from SQLite — the chat stayed in history but opened empty.
     */
    @Volatile
    private var persistedContent = false

    /** Whether the chat had at least one request/attachment — empty chats do not go to history. */
    fun hasContent(): Boolean = contentFlag || persistedContent

    /**
     * Whether the chat has saved events in SQLite (even if it was opened from history
     * and the user has not sent anything new). Checked across the whole session
     * chain: after an id rotation the events live in the child sessions.
     */
    fun hasPersistedHistory(): Boolean {
        val chain = try {
            projectSettings.settings.sessionChains[chatId].orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
        val ids = (chain + chatId + sessionId).distinct()
        val db = com.dsh.assistant.db.DshDatabase.getInstance(project)
        return ids.any { db.eventCount(it) > 0 }
    }

    private var lastPromptText: String = ""

    /** A message sent while the agent was working: it goes to DSH after the turn ends. */
    class QueuedPrompt(val id: String, val display: String, val prompt: String)

    private val queued = java.util.ArrayDeque<QueuedPrompt>()
    private var queuedSeq = 0

    /** Generation of the SDK process in which the current session id was created (see [prepareSession]). */
    @Volatile
    private var sessionGeneration = -1

    /** Last dialogue turns: carried over into a new session after the SDK restart. */
    private val dialogue = java.util.ArrayDeque<Pair<String, String>>()

    /** Context of the previous dialogue for the first request in a new session (one-shot). */
    @Volatile
    private var pendingCarryOver: String? = null

    private companion object {
        /** How many recent turns and characters we carry over into the new session. */
        const val CARRY_MESSAGES = 20
        const val CARRY_CHARS = 12_000
        const val CARRY_MESSAGE_CHARS = 4_000
    }

    init {
        // the user-set session name (if any) pins the title
        val pinned = ChatTitles.real(projectSettings.settings.sessionNames[sessionId])
        if (pinned != null) {
            customTitlePinned = true
            customTitle = pinned
        } else {
            // the title saved by the plugin: without it the tab showed "New session"
            // after an IDE restart and when opening a chat from history
            val chain = try {
                projectSettings.settings.sessionChains[chatId].orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            customTitle = try {
                ChatTitles.resolve(com.dsh.assistant.db.DshDatabase.getInstance(project), chatId, chain)
            } catch (e: Exception) {
                null
            }
            if (customTitle == null) loadTitleFromStore()
        }
        loadHistory()
    }

    /** For external DSH sessions the title lives in the jsonl journal — read it in the background. */
    private fun loadTitleFromStore() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val title = try {
                ChatTitles.resolve(store, sessionId)
            } catch (e: Exception) {
                null
            } ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                if (!customTitlePinned && customTitle == null) {
                    customTitle = title
                    setTitle(title)
                    onTitleChanged(title)
                }
            }
        }
    }

    // ---------- page and bridge ----------

    /** UI requests that belong to this chat (called from ChatShell on the EDT). */
    fun handleChatOp(node: JsonNode) {
        when (node.get("type")?.asText()) {
            "send" -> doSend(node.get("text")?.asText() ?: "", renderBubble = true)
            "unqueue" -> node.get("id")?.asText()?.let { unqueue(it) }
            "mode" -> node.get("mode")?.asText()?.let { app.appState.lastMode = it }
            "model" -> node.get("model")?.asText()?.let {
                onModelChange(app.appState.provider, it, currentEffort())
            }
            "effort" -> node.get("effort")?.asText()?.let {
                onModelChange(app.appState.provider, app.appState.model, it)
            }
            "policy" -> node.get("policy")?.asText()?.let {
                projectSettings.settings.sessionPolicies[sessionId] = it
                projectSettings.save()
            }
            "attachImage" -> attachImage()
            "attachFile" -> attachFile()
            "reset" -> resetContext()
            "stop" -> onStopRequested()
            "removeChip" -> node.get("path")?.asText()?.let {
                contextItems.remove(it)
                autoContextPaths.remove(it)
                pushChips()
            }
            "removeImage" -> node.get("id")?.asText()?.let { id ->
                pendingImages.removeIf { it.id == id }
                pushImages()
            }
            "answerAll" -> answerAll(node.get("answers"))
            "answerSkip" -> {
                setSessionState("new")
            }
            "pasteImage" -> pasteImage(
                node.get("name")?.asText() ?: "image",
                node.get("mime")?.asText() ?: "image/png",
                node.get("base64")?.asText() ?: "")
            "pasteFile" -> pasteFile(
                node.get("name")?.asText() ?: "file",
                node.get("content")?.asText() ?: "")
        }
    }

    private fun dispatchOp(op: String, body: ObjectNode) {
        body.put("op", op)
        body.put("chatId", chatId)
        dispatcher(body)
    }

    /** We save turn events to SQLite — the DSH SDK does not write them to the jsonl journal. */
    private fun persistEventToDb(type: String, event: JsonNode) {
        val reduced: JsonNode = when (type) {
            "assistant/message" -> {
                // no data.stream — it is heavy and not needed for history
                val copy = mapper.createObjectNode()
                copy.put("type", type)
                event.get("time")?.let { copy.set<JsonNode>("time", it) }
                val data = mapper.createObjectNode()
                event.get("data")?.get("message")?.let { data.set<JsonNode>("message", it) }
                event.get("data")?.get("usage")?.let { data.set<JsonNode>("usage", it) }
                event.get("data")?.get("turn")?.let { data.set<JsonNode>("turn", it) }
                copy.set<JsonNode>("data", data)
                copy
            }
            "user/message", "session/title", "turn/start", "turn/end", "compaction/summary" -> event
            else -> return
        }
        val json = mapper.writeValueAsString(reduced)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                com.dsh.assistant.db.DshDatabase.getInstance(project).appendEvent(sessionId, type, json)
                if (type == "session/title") {
                    val title = event.get("data")?.get("title")?.asText()
                    if (!title.isNullOrBlank()) {
                        // title only: upsertSession would overwrite the session's created_at/state
                        com.dsh.assistant.db.DshDatabase.getInstance(project)
                            .setSessionTitle(sessionId, title)
                    }
                }
            } catch (ignored: Exception) {
                // ignore
            }
        }
    }

    // ---------- public API (as before) ----------

    fun setTitle(t: String) {
        dispatchOp("title", mapper.createObjectNode().apply { put("text", t) })
    }

    /** User-set session name — pinned and never overwritten by an auto-generated title. */
    fun setCustomTitle(name: String) {
        customTitlePinned = true
        customTitle = name
        setTitle(name)
    }

    fun title(): String = customTitle ?: ChatTitles.DEFAULT

    /** Title to store in SQLite: null while there is no real title (we do not store the placeholder). */
    fun storedTitle(): String? = ChatTitles.real(customTitle)

    fun isRunning(): Boolean = running

    fun focusInput() {
        dispatchOp("focus", mapper.createObjectNode())
    }

    fun addContextItem(path: String, content: String) {
        contextItems[path] = ContextItem(path, content)
        pushChips()
    }

    fun addEditorContext(ctx: EditorContext) {
        val content = if (ctx.selection != null) ctx.selection else ctx.excerpt
        addContextItem(ctx.path, content)
    }

    fun send() {
        doSend("", renderBubble = false)
    }

    /** Inline editing of a selection (Ctrl+K-like). */
    fun sendInlineEdit(path: String, selection: String?, instruction: String) {
        val target = if (selection.isNullOrBlank()) {
            "Edit the file \"$path\"."
        } else {
            "Edit the file \"$path\"; the region below is selected by the user:\n```\n$selection\n```"
        }
        val prompt = "$target\n\nUser instruction: $instruction\n\n" +
            "Apply the changes to the file on disk and briefly describe what was done."
        val display = "Edit $path: $instruction"
        if (running) {
            enqueue(display, prompt)
            return
        }
        prepareSession()
        lastPromptText = prompt
        contentFlag = true
        rememberTurn("user", display)
        val op = mapper.createObjectNode()
        op.put("text", display)
        dispatchOp("userText", op)
        checkpoint("Before edit")
        val blocks = mapper.createArrayNode()
        appendCarryOver(blocks)
        val b = blocks.addObject()
        b.put("type", "text")
        b.put("text", prompt)
        sdk.prompt(sessionId, blocks)
        setRunning(true)
    }

    private fun doSend(text: String, renderBubble: Boolean) {
        if (text.isBlank() && pendingImages.isEmpty()) return
        // the agent is already working — the message will be queued (as in Cursor/DSH)
        if (running) {
            enqueue(text.ifBlank { "Attachment without text" }, text)
            return
        }
        prepareSession()
        lastPromptText = text
        contentFlag = true
        rememberTurn("user", text)
        refreshAutoContext()
        if (renderBubble && text.isNotBlank()) {
            val op = mapper.createObjectNode()
            op.put("text", text)
            dispatchOp("userText", op)
        }
        checkpoint("Before request")
        sdk.prompt(sessionId, buildBlocks(text))
        setRunning(true)
    }

    // ---------- sessions after an SDK restart ----------

    /**
     * The SDK creates a session by id and cannot continue an already saved one: for an existing
     * id it answers `session "…" already exists`. So before the first request in a new
     * process we pick a free id and honestly report that the model context has been restarted
     * (the visible chat history stays, it is read from the session journal).
     */
    private fun prepareSession() {
        val gen = sdk.processGeneration
        if (sessionGeneration == gen) return
        var rotated = false
        if (sessionHasLog()) {
            if (dialogue.isEmpty()) seedDialogueFromStore()
            pendingCarryOver = buildCarryOver()
            var guard = 0
            do {
                rotateSessionId()
                rotated = true
            } while (guard++ < 5 && sessionHasLog())
        }
        // we pin the generation not here but when the request is accepted (adoptGeneration):
        // the process might still be starting, and then the session gets created in the new generation
        if (rotated) notifyNewSession()
    }

    /** The SDK accepted the request: remember the process generation this session lives in. */
    fun adoptGeneration() {
        sessionGeneration = sdk.processGeneration
    }

    /** An id collision did happen (e.g. another process holds the journal) — take a new id and retry. */
    fun retryInNewSession() {
        setRunning(false)
        if (pendingCarryOver == null) pendingCarryOver = buildCarryOver()
        var guard = 0
        do {
            rotateSessionId()
        } while (guard++ < 5 && sessionHasLog())
        notifyNewSession()
        resendLastPrompt()
    }

    /** Pick up history from the journal if the request went out before the chat loaded it. */
    private fun seedDialogueFromStore() {
        try {
            val chain = projectSettings.settings.sessionChains[chatId] ?: mutableListOf(sessionId)
            val ids = (chain + sessionId).distinct()
            for (m in store.readChainHistory(ids, 30).takeLast(CARRY_MESSAGES)) {
                rememberTurn(m.role, m.text)
            }
        } catch (ignored: Exception) {
            // no history — the new session starts with a clean context
        }
    }

    /** Remembers a dialogue turn (with limits on size and depth). */
    private fun rememberTurn(role: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        dialogue.addLast(role to t.take(CARRY_MESSAGE_CHARS))
        while (dialogue.size > CARRY_MESSAGES) dialogue.removeFirst()
    }

    /** Ready-made "previous dialogue" text for continuing in a new session. */
    private fun buildCarryOver(): String? {
        if (dialogue.isEmpty()) return null
        val sb = StringBuilder()
        var total = 0
        for ((role, text) in dialogue) {
            val line = (if (role == "user") "User: " else "Assistant: ") + text + "\n\n"
            if (total + line.length > CARRY_CHARS) break
            sb.append(line)
            total += line.length
        }
        return if (total == 0) null else sb.toString()
    }

    /** Adds the carried-over context to the request blocks (first request of a new session only). */
    private fun appendCarryOver(arr: ArrayNode) {
        val ctx = pendingCarryOver ?: return
        pendingCarryOver = null
        val b = arr.addObject()
        b.put("type", "text")
        b.put("text", "Below is the previous dialogue of this chat from the last session " +
            "(after a restart the SDK does not resume a saved session, so the context is passed as text). " +
            "Continue taking it into account and do not repeat actions that were already done.\n\n" + ctx)
    }

    private fun sessionHasLog(): Boolean = try {
        store.hasSessionLog(sessionId)
    } catch (e: Exception) {
        false
    }

    /** Neutral (non-red) note: the SDK does not resume a saved session. */
    private fun notifyNewSession() {
        val text = if (pendingCarryOver != null) {
            "A new DSH session has started: after a restart the SDK does not resume a saved session, " +
                "so the latest dialogue turns were passed into the new session as context."
        } else {
            "A new DSH session has started: after a restart the SDK does not resume a saved session — " +
                "the model starts the dialogue over (the chat history above is preserved)."
        }
        dispatchOp("note", mapper.createObjectNode().apply { put("text", text) })
    }

    // ---------- message queue ----------

    /** Queues a message: a bubble marked "queued" appears in the chat right away. */
    fun enqueue(display: String, prompt: String) {
        val id = "$chatId-q${++queuedSeq}"
        queued.addLast(QueuedPrompt(id, display, prompt))
        val op = mapper.createObjectNode()
        op.put("id", id)
        op.put("text", display)
        dispatchOp("userQueued", op)
    }

    fun unqueue(id: String) {
        queued.removeAll { it.id == id }
        val op = mapper.createObjectNode()
        op.put("id", id)
        dispatchOp("unqueued", op)
    }

    fun queuedCount(): Int = queued.size

    /** Sends the next queued message (called when a turn ends). */
    fun flushQueue() {
        if (running) return
        val next = queued.pollFirst() ?: return
        val op = mapper.createObjectNode()
        op.put("id", next.id)
        dispatchOp("dequeue", op)
        if (next.prompt.isBlank() && pendingImages.isEmpty()) {
            flushQueue()
            return
        }
        prepareSession()
        lastPromptText = next.prompt
        contentFlag = true
        rememberTurn("user", next.prompt)
        refreshAutoContext()
        checkpoint("Before request")
        sdk.prompt(sessionId, buildBlocks(next.prompt))
        setRunning(true)
    }

    private fun checkpoint(name: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                tracker.createCheckpoint("$name " + SimpleDateFormat("HH:mm").format(Date()))
            } catch (ignored: Exception) {
                // best effort
            }
        }
    }

    private fun refreshAutoContext() {
        contextItems.entries.removeIf { (path, _) -> path in autoContextPaths }
        autoContextPaths.clear()
        val ctx = editorContextProvider() ?: run {
            pushChips()
            return
        }
        // we auto-attach ONLY an explicit selection: the open file
        // is never attached by itself (the user is against such behavior)
        if (ctx.selection == null) {
            pushChips()
            return
        }
        contextItems[ctx.path] = ContextItem(ctx.path, ctx.selection)
        autoContextPaths.add(ctx.path)
        pushChips()
    }

    /** Session context reset: removes all attachments. */
    fun resetContext() {
        contextItems.clear()
        autoContextPaths.clear()
        pendingImages.clear()
        pushChips()
        pushImages()
        val op = mapper.createObjectNode()
        op.put("text", "Session attachments are reset. DSH keeps the session history; " +
            "if the context overflows, DSH rebuilds it itself (visible in the metrics).")
        dispatchOp("note", op)
    }

    fun appendError(text: String, details: String? = null) {
        val op = mapper.createObjectNode()
        op.put("text", text)
        if (!details.isNullOrBlank()) op.put("details", details)
        dispatchOp("error", op)
    }

    fun checkStuck() {
        if (!running) return
        if (stuckHintShown) return
        if (System.currentTimeMillis() - lastEventTime < 120_000) return
        stuckHintShown = true
        appendError("The agent has not responded for 2 minutes. Possible reasons: the model/effort is unavailable " +
            "(try deepseek-v4-pro, effort \"auto\"), you are not signed in to a DeepSeek account, or the request is stuck. " +
            "You can press \"■ Stop\" and try again.")
    }

    fun refreshModelCombo() {
        val models = mapper.createObjectNode()
        val arr = models.putArray("items")
        for (m in app.appState.models.split(",").map { it.trim() }.filter { it.isNotEmpty() }) arr.add(m)
        models.put("current", app.appState.model)
        dispatchOp("models", models)

        dispatchOp("effort", mapper.createObjectNode().apply { put("current", app.appState.reasoningEffort) })
        dispatchOp("policy", mapper.createObjectNode().apply {
            put("current", projectSettings.settings.sessionPolicies[sessionId] ?: "never")
        })
        dispatchOp("mode", mapper.createObjectNode().apply { put("mode", app.appState.lastMode) })
    }

    fun setRunning(r: Boolean) {
        running = r
        dispatchOp("running", mapper.createObjectNode().apply { put("r", r) })
    }

    // ---------- attachments ----------

    private fun pushChips() {
        val op = mapper.createObjectNode()
        val arr = op.putArray("items")
        for ((path, item) in contextItems) {
            val c = arr.addObject()
            c.put("path", path)
            c.put("len", item.content.length)
        }
        dispatchOp("chips", op)
    }

    private fun pushImages() {
        val op = mapper.createObjectNode()
        val arr = op.putArray("items")
        for (img in pendingImages) {
            val c = arr.addObject()
            c.put("id", img.id)
            c.put("path", img.path)
            c.put("base64", img.base64)
            c.put("mime", img.mime)
        }
        dispatchOp("images", op)
    }

    private fun attachImage() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle("Attach image")
            .withFileFilter { vf ->
                val ext = vf.extension?.lowercase()
                ext == "png" || ext == "jpg" || ext == "jpeg" || ext == "webp" || ext == "gif"
            }
        com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null) { files ->
            for (vf in files) addImageFile(vf)
        }
    }

    private fun addImageFile(vf: com.intellij.openapi.vfs.VirtualFile) {
        try {
            val bytes = vf.contentsToByteArray()
            if (bytes.size > 8 * 1024 * 1024) {
                appendError("Image ${vf.name} is larger than 8 MB — skipped.")
                return
            }
            val ext = vf.extension?.lowercase() ?: return
            val mime = when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> return
            }
            pendingImages.add(
                PendingImage(
                    id = java.util.UUID.randomUUID().toString(),
                    path = vf.path,
                    base64 = java.util.Base64.getEncoder().encodeToString(bytes),
                    mime = mime))
            pushImages()
        } catch (e: Exception) {
            appendError("Could not read image ${vf.name}: ${e.message}")
        }
    }

    /** A pasted/dropped image. */
    private fun pasteImage(name: String, mime: String, base64: String) {
        if (base64.isBlank()) return
        val mimeOk = mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        if (!mimeOk) return
        if (base64.length > 11 * 1024 * 1024) {
            appendError("Image $name is larger than 8 MB — skipped.")
            return
        }
        pendingImages.add(PendingImage(java.util.UUID.randomUUID().toString(), name, base64, mime))
        contentFlag = true
        pushImages()
    }

    /** A pasted/dropped text file — goes into the session context. */
    private fun pasteFile(name: String, content: String) {
        if (content.isBlank()) return
        contentFlag = true
        addContextItem(name, content.take(app.appState.maxContextChars))
    }

    private fun attachFile() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Attach file to session")
            .withRoots(project.baseDir)
        com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null) { vf ->
            val text = try {
                com.intellij.openapi.vfs.VfsUtilCore.loadText(vf).take(app.appState.maxContextChars)
            } catch (e: Exception) {
                ""
            }
            val path = com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(vf, project.baseDir) ?: vf.path
            addContextItem(path, text)
        }
    }

    /** Answers to the agent's questions: shown in the chat as the user and sent to the agent. */
    /** Session id rotation after an SDK restart: DSH cannot recreate a session with the same id. */
    fun rotateSessionId(): String {
        val chain = projectSettings.settings.sessionChains.getOrPut(chatId) { java.util.ArrayList() }
        if (chain.isEmpty() || chain.last() != sessionId) {
            chain.add(sessionId)
        }
        var n = chain.size
        var newId: String
        do {
            n++
            newId = "$chatId-$n"
        } while (chain.contains(newId))
        chain.add(newId)
        sessionId = newId
        try {
            projectSettings.save()
        } catch (ignored: Exception) {
            // ignore
        }
        return newId
    }

    /** Re-sending the last request after a session rotation. */
    fun resendLastPrompt() {
        if (lastPromptText.isBlank() && pendingImages.isEmpty()) return
        sdk.prompt(sessionId, buildBlocks(lastPromptText))
        setRunning(true)
    }

    private fun answerAll(answersNode: JsonNode?) {
        if (answersNode == null || !answersNode.isArray || answersNode.size() == 0) return
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        for (a in answersNode) {
            val q = a.get("question")?.asText() ?: ""
            val ans = a.get("answer")?.asText() ?: "skipped"
            lines.add("$q → $ans")
            sb.append("Question: \"$q\"\nAnswer: \"$ans\"\n")
        }
        // the page (JS) draws the answer bubble itself — no need to duplicate it
        val blocks = mapper.createArrayNode()
        val b = blocks.addObject()
        b.put("type", "text")
        b.put("text", "Answers to the agent's questions:\n$sb")
        prepareSession()
        sdk.prompt(sessionId, blocks)
        setSessionState("answered")
    }

    private fun setSessionState(state: String) {
        projectSettings.settings.sessionStates[sessionId] = state
        try {
            com.dsh.assistant.db.DshDatabase.getInstance(project).setSessionState(sessionId, state)
            com.dsh.assistant.db.DshDatabase.getInstance(project).setChatState(sessionId, state)
        } catch (ignored: Exception) {
            // ignore
        }
        // deferred save in the background — do not block the EDT during streaming
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(1500)
            } catch (ignored: InterruptedException) {
                // ignore
            }
            try {
                projectSettings.save()
            } catch (ignored: Exception) {
                // ignore
            }
        }
    }

    private fun buildBlocks(userText: String): ArrayNode {
        val arr = mapper.createArrayNode()
        for (img in pendingImages) {
            val b = arr.addObject()
            b.put("type", "image")
            b.put("data", img.base64)
            b.put("mimeType", img.mime)
        }
        for (item in contextItems.values) {
            val b = arr.addObject()
            b.put("type", "text")
            b.put("text", "Context: file \"${item.path}\"\n```\n${item.content}\n```")
        }
        val mode = when (app.appState.lastMode) {
            "ASK" -> ModePrefix.ASK
            "PLAN" -> ModePrefix.PLAN
            else -> ModePrefix.AGENT
        }
        val sessionPolicy = projectSettings.settings.sessionPolicies[sessionId] ?: "never"
        val askPrefix = if (app.appState.lastMode != "ASK" && sessionPolicy == "ask") {
            "Before doing an important action (editing files, running commands, changing settings) — " +
                "first ask the user via ask_user_question and wait for the answer.\n\n"
        } else {
            ""
        }
        appendCarryOver(arr)
        val finalText = mode + askPrefix + userText
        if (finalText.isNotBlank()) {
            val b = arr.addObject()
            b.put("type", "text")
            b.put("text", finalText)
        }
        return arr
    }

    private fun currentEffort(): String = app.appState.reasoningEffort

    private object ModePrefix {
        const val ASK = "Reply with analysis and explanations only in the chat. Do not change files or run commands. " +
            "If an important action is required (file edits, running commands) — first ask the user " +
            "via ask_user_question and wait for the answer.\n\n"
        const val AGENT = ""
        const val PLAN = "First make a detailed plan and show it in the chat. " +
            "Do not change files or run commands until the user explicitly asks to continue.\n\n"
    }

    // ---------- SDK events ----------

    fun onSdkEvent(event: JsonNode) {
        val type = event.get("type")?.asText() ?: return
        persistEventToDb(type, event)
        metrics.onEvent(type, event)
        lastEventTime = System.currentTimeMillis()
        scheduleMetricsUiUpdate()
        when (type) {
            "turn/start" -> {
                dispatchOp("turnStart", mapper.createObjectNode())
                stuckHintShown = false
                // the agent started a turn — the file watcher must be on alert
                tracker.wakeWatcher()
            }
            "assistant/message" -> {
                val content = event.get("data")?.get("message")?.get("content") ?: return
                var hasText = false
                for (blk in content) {
                    if (blk.get("type")?.asText() == "text" && (blk.get("text")?.asText() ?: "").isNotBlank()) hasText = true
                }
                if (hasText) setSessionState("answered")
                run {
                    val collected = StringBuilder()
                    for (blk in content) {
                        if (blk.get("type")?.asText() == "text") collected.append(blk.get("text")?.asText() ?: "")
                    }
                    val last = dialogue.peekLast()
                    val textNow = collected.toString().trim().take(CARRY_MESSAGE_CHARS)
                    if (textNow.isNotEmpty() && !(last != null && last.first == "assistant" && last.second == textNow)) {
                        dialogue.addLast("assistant" to textNow)
                        while (dialogue.size > CARRY_MESSAGES) dialogue.removeFirst()
                    }
                }
                val dtByBlock = HashMap<Int, List<Int>>()
                val stream = event.get("data")?.get("stream")
                if (stream != null && stream.isArray) {
                    for (c in stream) {
                        val ct = c.get("type")?.asText()
                        if (ct == "reasoning-chunks" || ct == "text-chunks") {
                            val idx = c.get("index")?.asInt() ?: continue
                            val dtNode = c.get("dt") ?: continue
                            val dt = ArrayList<Int>()
                            for (v in dtNode) dt.add(v.asInt())
                            dtByBlock[idx] = dt
                        }
                    }
                }
                var blockIndex = 0
                for (block in content) {
                    when (block.get("type")?.asText()) {
                        "reasoning" -> pushStream("reasoning", block.get("text")?.asText() ?: "", dtByBlock[blockIndex])
                        "text" -> pushStream("text", block.get("text")?.asText() ?: "", dtByBlock[blockIndex])
                        "tool-call" -> {
                            val name = block.get("name")?.asText() ?: "tool"
                            val args = block.get("arguments")?.asText() ?: ""
                            val callId = block.get("id")?.asText()
                            if (name == "ask_user_question") {
                                setSessionState("question")
                                val op = mapper.createObjectNode()
                                op.put("callId", callId ?: "")
                                op.set<ArrayNode>("questions", parseQuestions(args))
                                dispatchOp("question", op)
                            } else {
                                val op = mapper.createObjectNode()
                                op.put("name", name)
                                op.put("args", args.take(2000))
                                op.put("callId", callId ?: "")
                                toolDescriptions[name]?.let { op.put("description", it.take(200)) }
                                dispatchOp("tool", op)
                            }
                        }
                    }
                    blockIndex++
                }
            }
            "assistant/attempt" -> {
                val errorText = attemptErrorText(event)
                if (errorText != null) {
                    appendError("Model error: $errorText", eventDetails(event))
                    val hint = hintForModelError(errorText)
                    if (hint.isNotEmpty()) appendError(hint)
                    maybeAutoFixModel(errorText)
                }
            }
            "tool/result" -> {
                val data = event.get("data") ?: return
                val callId = data.get("callId")?.asText()
                    ?: data.get("message")?.get("source")?.get("callId")?.asText()
                    ?: data.get("message")?.get("toolCallId")?.asText()
                if (callId != null) {
                    dispatchOp("questionClosed", mapper.createObjectNode().apply { put("callId", callId) })
                }
                // a tool result — into the expandable call card
                val resultText = toolResultText(data)
                if (callId != null && resultText.isNotBlank()) {
                    val op = mapper.createObjectNode()
                    op.put("callId", callId)
                    op.put("text", resultText.take(3000))
                    dispatchOp("toolResult", op)
                }
            }
            "turn/end" -> {
                dispatchOp("turnEnd", mapper.createObjectNode())
                setRunning(false)
                // Final file snapshot — straight to history, without waiting for the VFS.
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        tracker.recordFinalVersions()
                    } catch (ignored: Exception) {
                        // best-effort
                    }
                }
                tracker.wakeWatcher()
                val reason = event.get("data")?.get("reason")
                if (reason?.get("kind")?.asText() == "error") {
                    setSessionState("error")
                    val msg = reason.get("error")?.get("message")?.asText()
                        ?: reason.get("error")?.get("code")?.asText()
                        ?: "unknown error"
                    appendError("The turn finished with an error: $msg", eventDetails(event))
                    val hint = hintForModelError(msg)
                    if (hint.isNotEmpty()) appendError(hint)
                    maybeAutoFixModel(msg)
                } else {
                    val pending = try {
                        tracker.pendingCount()
                    } catch (e: Exception) {
                        0
                    }
                    val text = if (pending > 0) {
                        "The agent has finished — you can send a new request. " +
                            "Changed files ($pending) are in the \"Changes\" panel."
                    } else {
                        "The agent has finished — you can send a new request."
                    }
                    dispatchOp("note", mapper.createObjectNode().apply { put("text", text) })
                }
                onTurnFinished()
                flushQueue()
            }
            "session/title" -> {
                val t = event.get("data")?.get("title")?.asText()
                if (!t.isNullOrBlank() && !customTitlePinned) {
                    customTitle = t
                    setTitle(t)
                    onTitleChanged(t)
                }
            }
            "workspace/changes" -> {
                // DSH reports that the turn's files changed: check immediately,
                // without waiting for the next watcher tick.
                tracker.wakeWatcher()
                dispatchOp("note", mapper.createObjectNode().apply { put("text", "Project files changed.") })
            }
            "request/header" -> {
                if (!toolsSent) {
                    val tools = event.get("data")?.get("header")?.get("tools")
                    if (tools != null && tools.isArray && tools.size() > 0) {
                        toolsSent = true
                        val op = mapper.createObjectNode()
                        val arr = op.putArray("items")
                        for (t in tools) {
                            val name = t.get("name")?.asText() ?: ""
                            val desc = t.get("description")?.asText() ?: ""
                            toolDescriptions[name] = desc
                            val o = arr.addObject()
                            o.put("name", name)
                            o.put("description", desc.take(400))
                        }
                        dispatchOp("tools", op)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun pushStream(kind: String, text: String, dt: List<Int>?) {
        val op = mapper.createObjectNode()
        op.put("kind", kind)
        op.put("text", text)
        val dtArr = op.putArray("dt")
        if (dt != null) {
            for (d in dt) dtArr.add(d)
        }
        dispatchOp("stream", op)
    }

    private fun parseQuestions(args: String): ArrayNode {
        val arr = mapper.createArrayNode()
        try {
            val n = mapper.readTree(args)
            val list = n.get("questions") ?: return arr
            for (item in list) {
                val q = arr.addObject()
                q.put("question", item.get("question")?.asText() ?: "")
                q.put("header", item.get("header")?.asText() ?: "")
                q.put("multi", item.get("multi_select")?.asBoolean() ?: false)
                val opts = q.putArray("options")
                item.get("options")?.forEach { o ->
                    o.get("label")?.asText()?.let { opts.add(it) }
                }
            }
        } catch (ignored: Exception) {
            // ignore
        }
        return arr
    }

    /** Technical details of the DSH event — they can be expanded and copied from the error card. */
    private fun eventDetails(event: JsonNode): String {
        val sb = StringBuilder()
        sb.append("Session: ").append(sessionId).append('\n')
        sb.append("Model: ").append(app.appState.model)
            .append(" · provider: ").append(app.appState.provider)
            .append(" · effort: ").append(app.appState.reasoningEffort.ifBlank { "auto" }).append('\n')
        sb.append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())).append('\n')
        sb.append('\n').append("DSH event:").append('\n')
        val json = try {
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(event)
        } catch (e: Exception) {
            event.toString()
        }
        sb.append(json.take(6000))
        return sb.toString()
    }

    private fun attemptErrorText(event: JsonNode): String? {
        val stream = event.get("data")?.get("stream")
        if (stream == null || !stream.isArray) return null
        for (chunk in stream) {
            val reason = chunk.get("chunk")?.get("reason")
            if (reason?.get("kind")?.asText() == "error") {
                return reason.get("failure")?.get("message")?.asText()
                    ?: reason.get("failure")?.get("code")?.asText()
                    ?: "model error"
            }
        }
        return null
    }

    private fun hintForModelError(msg: String): String {
        return when {
            msg.contains("Sign in", ignoreCase = true) || msg.contains("ACCOUNT_SIGN_IN", ignoreCase = true) ->
                "You need to sign in to your DeepSeek account: open \"⋯ → Web DSH\" and sign in, or configure an API key in Settings → Tools → DSH Assistant."
            msg.contains("model", ignoreCase = true) || msg.contains("not found", ignoreCase = true) ->
                "The model seems unavailable. Available: deepseek-v4-pro and deepseek-flash — pick one in the switcher at the bottom of the chat."
            else -> ""
        }
    }

    /** Self-healing: if the error names supported models, we switch automatically. */
    private fun maybeAutoFixModel(msg: String) {
        val match = Regex("supported API model names? are ([\\w-]+(?:,\\s*[\\w-]+)*)", RegexOption.IGNORE_CASE).find(msg) ?: return
        val models = match.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (models.isEmpty()) return
        if (models.contains(app.appState.model)) return
        val chosen = models.firstOrNull { it.contains("v4-pro") } ?: models.first()
        app.appState.models = models.joinToString(",")
        onModelChange(app.appState.provider, chosen, "")
        appendError("The model was switched automatically to \"$chosen\" (supported: ${models.joinToString(", ")}) — send the request again.")
    }

    /** Extracts readable text from a tool result (content blocks or error). */
    private fun toolResultText(data: JsonNode): String {
        val content = data.get("content")
            ?: data.get("message")?.get("content")
        if (content != null && content.isArray) {
            val sb = StringBuilder()
            for (b in content) {
                when (b.get("type")?.asText()) {
                    "text" -> sb.append(b.get("text")?.asText() ?: "")
                    else -> sb.append(b.get("text")?.asText() ?: b.toString().take(200))
                }
                sb.append("\n")
            }
            return sb.toString().trim()
        }
        if (content != null && content.isTextual) return content.asText().take(3000)
        data.get("error")?.let { return it.get("reason")?.asText() ?: it.toString() }
        return ""
    }

    private fun toolSummary(name: String, args: String): String {
        val desc = try {
            val n = mapper.readTree(args)
            n.get("description")?.asText() ?: n.get("command")?.asText()
        } catch (e: Exception) {
            null
        }
        val base = desc ?: args
        val oneLine = base.replace('\n', ' ').trim()
        return "$name · ${oneLine.take(90)}"
    }

    // ---------- metrics ----------

    private fun scheduleMetricsUiUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastMetricsUiUpdate < 1500) return
        lastMetricsUiUpdate = now
        ApplicationManager.getApplication().invokeLater { pushMetrics() }
    }

    /** Public push of current metrics (for ChatShell when a tab is added). */
    fun pushMetricsIfAny() {
        pushMetrics()
    }

    private fun pushMetrics() {
        val s = metrics.summary()
        val m = mapper.createObjectNode()
        m.put("total", s.total)
        m.put("input", s.input)
        m.put("output", s.output)
        m.put("reasoning", s.reasoning)
        m.put("cacheRead", s.cacheRead)
        m.put("cacheWrite", s.cacheWrite)
        m.put("prompt", s.totalPrompt)
        m.put("cacheHit", s.cacheHitPercent)
        m.put("speed", s.speedTokensPerSec)
        m.put("turns", s.turns)
        m.put("duration", s.durationMs)
        m.put("lastReset", s.lastCompactionTime?.let {
            SimpleDateFormat("dd.MM HH:mm:ss").format(Date(it))
        } ?: "n/a")
        m.put("compacted", s.compactedTokens)
        m.put("lastInput", s.lastInput)
        m.put("model", app.appState.model)
        val rows = m.putArray("rows")
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss")
        for (r in metrics.records().asReversed().take(40)) {
            val row = rows.addObject()
            row.put("time", fmt.format(Date(r.time)))
            row.put("input", ChatMetrics.fmtTokens(r.input + r.cacheRead))
            row.put("output", ChatMetrics.fmtTokens(r.output))
            val cachePct = if (r.input + r.cacheRead == 0L) 0 else (r.cacheRead * 100 / (r.input + r.cacheRead)).toInt()
            row.put("cachePct", "$cachePct%")
            val speed = if (r.durationMs <= 0L) 0.0 else r.output.toDouble() * 1000.0 / r.durationMs
            row.put("speed", "%.0f".format(speed))
            row.put("dur", ChatMetrics.fmtDuration(r.durationMs))
        }
        dispatchOp("metrics", m)
    }

    // ---------- history ----------

    private fun loadHistory() {
        if (historyLoaded) return
        historyLoaded = true
        ApplicationManager.getApplication().executeOnPooledThread {
            // read history across the WHOLE session chain (history is not lost after id rotations)
            val chain = projectSettings.settings.sessionChains[chatId] ?: mutableListOf(sessionId)
            val ids = (chain + sessionId).distinct()
            val msgs = try {
                store.readChainHistory(ids)
            } catch (e: Exception) {
                emptyList()
            }
            if (msgs.isNotEmpty()) persistedContent = true
            val (metricRecords, compactionTime, compacted) = try {
                store.readChainMetrics(ids)
            } catch (e: Exception) {
                Triple(emptyList(), null, 0L)
            }
            ApplicationManager.getApplication().invokeLater {
                metrics.seed(metricRecords, compactionTime, compacted)
                pushMetrics()
                refreshModelCombo()
                // status from history (if not already set by live events)
                if (projectSettings.settings.sessionStates[sessionId] == null) {
                    val histState = try {
                        store.readSessionState(sessionId)
                    } catch (e: Exception) {
                        "new"
                    }
                    if (histState != "new") setSessionState(histState)
                }
                if (dialogue.isEmpty()) {
                    for (m in msgs.takeLast(CARRY_MESSAGES)) rememberTurn(m.role, m.text)
                }
                for (m in msgs) {
                    val op = mapper.createObjectNode()
                    op.put("text", m.text)
                    if (m.role == "user") {
                        dispatchOp("userText", op)
                    } else {
                        dispatchOp("assistantText", op)
                    }
                }
                if (msgs.isEmpty()) {
                    dispatchOp("welcome", mapper.createObjectNode())
                }
            }
        }
    }
}
