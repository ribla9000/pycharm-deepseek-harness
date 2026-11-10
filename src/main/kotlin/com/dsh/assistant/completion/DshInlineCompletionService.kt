package com.dsh.assistant.completion

import com.dsh.assistant.sdk.DshSdkClient
import com.dsh.assistant.sdk.DshSessionStore
import com.dsh.assistant.settings.DshAppSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionEventListener
import com.intellij.codeInsight.inline.completion.InlineCompletionEventType
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.logs.InlineCompletionUsageTracker.ShownEvents.FinishType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.Alarm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TAB completion service (Cursor-like inline completion) built on top of DSH.
 *
 * The idea: we keep a separate `dsh --profile sdk` process with tools disabled.
 * Then an agent turn becomes a single text response from the model (~1–2 s) that is
 * shown as gray text in the editor. Tab accepts the suggestion (the platform's stock
 * behavior), Esc hides it and, after [DshAppSettings] `tabRetryDelayMs`, repeats the
 * request — this time over the whole file.
 */
@Service(Service.Level.PROJECT)
class DshInlineCompletionService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(DshInlineCompletionService::class.java)
    private val app = DshAppSettings.getInstance()
    private val mapper = ObjectMapper()

    private val clientLock = Any()
    private var engine: Engine? = null

    /** Time until which automatic (non-manual) requests are suppressed after Esc. */
    @Volatile
    private var suppressAutoUntil = 0L

    private val requestSeq = AtomicInteger()
    private val inFlight = AtomicBoolean(false)

    private val retryAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val idleAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** Editors whose inline-completion handler we have already attached to. */
    private val attachedEditors = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(WeakHashMap<Editor, Boolean>()))

    /** Per editor: whether a suggestion was actually returned (not just a request started). */
    private val suggestionShown = java.util.Collections.synchronizedMap(
        WeakHashMap<Editor, Boolean>())

    init {
        // Old technical completion journals are useless — clean them up in the background.
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                DshSessionStore(project).pruneCompletionSessions()
            } catch (ignored: Exception) {
                // not critical
            }
        }
    }

    fun isEnabled(): Boolean = app.appState.tabCompletionEnabled && !project.isDisposed

    fun isSuppressed(): Boolean = System.currentTimeMillis() < suppressAutoUntil

    /** Manual invocation (a menu action): drop the post-Esc suppression and ask for a suggestion. */
    fun trigger(editor: Editor) {
        retryAlarm.cancelAllRequests()
        suppressAutoUntil = 0
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        handler.invokeEvent(
            InlineCompletionEvent.DirectCall(
                editor,
                editor.caretModel.currentCaret,
                DataContext.EMPTY_CONTEXT))
    }

    /**
     * Returns the suggestion text for the current caret position, or null.
     * Called from [com.intellij.codeInsight.inline.completion.InlineCompletionProvider].
     */
    suspend fun suggestion(request: InlineCompletionRequest): String? {
        if (!isEnabled()) return null
        val editor = request.editor
        attachEscListener(editor)
        suggestionShown.remove(editor)

        val manual = request.event is InlineCompletionEvent.DirectCall ||
            request.event is InlineCompletionEvent.ManualCall
        // Do not wait for a manual retry (Esc): the file is needed right away, with fresh data.
        if (!manual) {
            if (isSuppressed()) return null
            val seq = requestSeq.incrementAndGet()
            delay(DEBOUNCE_MS)
            if (requestSeq.get() != seq) return null
            if (isSuppressed() || !isEnabled()) return null
        }

        // One request in flight: do not spawn paid calls for every character.
        if (!inFlight.compareAndSet(false, true)) return null
        try {
            val snapshot = snapshot(request) ?: return null
            if (snapshot.fileText.isBlank()) return null
            val extended = app.appState.tabExtendedHelp
            val prompt = buildPrompt(snapshot, extended)
            val raw = requestCompletion(prompt, extended) ?: return null
            val text = sanitize(raw, extended) ?: return null
            suggestionShown[editor] = true
            return text
        } catch (e: Exception) {
            log.debug("DSH inline completion failed", e)
            return null
        } finally {
            inFlight.set(false)
            scheduleIdleStop()
        }
    }

    // ---------- editor snapshot and prompt ----------

    private data class Snapshot(
        val path: String,
        val language: String,
        val fileText: String,
        val offset: Int,
        val line: Int,
        val column: Int,
    )

    private fun snapshot(request: InlineCompletionRequest): Snapshot? {
        if (request.startOffset != request.endOffset) return null
        return try {
            ReadAction.compute<Snapshot?, RuntimeException> {
                val document = request.document
                val text = document.text
                // The whole file goes into the prompt; skip gigantic files, otherwise
                // the request does not fit the model context and costs a fortune.
                if (text.length > MAX_FILE_CHARS) return@compute null
                val offset = request.endOffset.coerceIn(0, text.length)
                val file = request.file
                val virtualFile = file.virtualFile
                val path = if (virtualFile != null) {
                    VfsUtilCore.getRelativePath(virtualFile, project.baseDir) ?: virtualFile.path
                } else {
                    file.name
                }
                val line = document.getLineNumber(offset) + 1
                val column = offset - document.getLineStartOffset(line - 1) + 1
                Snapshot(
                    path = path,
                    language = file.language.displayName,
                    fileText = text,
                    offset = offset,
                    line = line,
                    column = column)
            }
        } catch (e: Exception) {
            log.debug("cannot read editor snapshot", e)
            null
        }
    }

    private fun buildPrompt(s: Snapshot, extended: Boolean): String {
        val before = s.fileText.substring(0, s.offset.coerceIn(0, s.fileText.length))
        val after = s.fileText.substring(s.offset.coerceIn(0, s.fileText.length))
        val lengthRule = if (extended) {
            "EXTENDED continuation: up to 12,000 tokens. Complete the current function/class, " +
                "implement the next logical block, or fill obvious TODOs. The result must be " +
                "substantial but syntactically valid, and must belong exactly at the insertion point."
        } else {
            "SHORT continuation: finish the current expression, statement or block. Usually 1–10 lines, " +
                "never more than ~40 lines. Do not start unrelated new features."
        }
        return buildString {
            append("You are an inline code completion engine integrated into PyCharm.\n")
            append("You receive the ENTIRE current file and the exact insertion point.\n\n")
            append("File: ").append(s.path).append('\n')
            append("Language: ").append(s.language).append('\n')
            append("Insertion point: line ").append(s.line).append(", column ").append(s.column)
                .append(" (offset ").append(s.offset).append(").\n")
            append("The marker ").append(CURSOR_MARKER).append(" below marks the insertion point. ")
                .append("It is NOT part of the file: never output it.\n\n")
            append("<file path=\"").append(xml(s.path)).append("\" language=\"")
                .append(xml(s.language)).append("\">\n")
            append(before)
            append(CURSOR_MARKER)
            append(after)
            append("\n</file>\n\n")
            append("Task: write the code that should be inserted exactly at ").append(CURSOR_MARKER)
                .append(" so the file continues naturally.\n\n")
            append("Rules:\n")
            append("- Return ONLY the inserted fragment. No surrounding file, no markdown fences, no commentary.\n")
            append("- Do not repeat or rewrite code that already exists before or after the cursor.\n")
            append("- Match the indentation and style of the surrounding code; keep it valid for the language.\n")
            append("- The text before the cursor already contains the current line's indentation. ")
                .append("Do not duplicate that existing indentation on the first line.\n")
            append("- ").append(lengthRule).append('\n')
            append("- If there is nothing useful to add, return an empty response.\n")
            append("- You have no tools and must not ask questions. This is a single pure text completion.\n")
        }
    }

    private fun xml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ---------- request to DSH ----------

    private suspend fun requestCompletion(prompt: String, extended: Boolean): String? {
        val maxTokens = if (extended) EXTENDED_MAX_TOKENS else NORMAL_MAX_TOKENS
        val eng = engineFor(maxTokens) ?: return null
        val timeout = if (extended) EXTENDED_TIMEOUT_MS else NORMAL_TIMEOUT_MS
        return eng.request(prompt, timeout)
    }

    private fun engineFor(maxTokens: Int): Engine? {
        synchronized(clientLock) {
            val current = engine
            if (current != null && current.maxTokens == maxTokens) return current
            if (current != null) {
                // The "Extended help" setting changed: shut the old process down carefully.
                current.closeAsync()
            }
            val fresh = Engine(project, mapper, app, maxTokens)
            engine = fresh
            return fresh
        }
    }

    private fun scheduleIdleStop() {
        idleAlarm.cancelAllRequests()
        idleAlarm.addRequest({
            synchronized(clientLock) {
                engine?.closeAsync()
                engine = null
            }
            log.debug("DSH completion process stopped after idle")
        }, IDLE_STOP_MS)
    }

    /**
     * A single DSH completion process and its technical sessions. Every request gets
     * its own `dsh-tab-…` id, so history does not leak between suggestions.
     */
    private class Engine(
        project: Project,
        private val mapper: ObjectMapper,
        app: DshAppSettings,
        val maxTokens: Int,
    ) : Disposable {

        private val log = Logger.getInstance(Engine::class.java)
        private val client = DshSdkClient(
            project,
            app,
            DshSdkClient.Options(
                purpose = "completion",
                maxTokens = maxTokens,
                completion = true,
                permissionMode = "read-only"))
        private val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
        private val lastText = ConcurrentHashMap<String, String>()
        @Volatile
        private var closed = false

        init {
            client.addListener(object : DshSdkClient.Listener {
                override fun onEvent(sessionId: String, event: JsonNode) {
                    if (!pending.containsKey(sessionId)) return
                    when (event.get("type")?.asText()) {
                        "assistant/message" -> {
                            val text = assistantText(event)
                            if (text.isNotBlank()) lastText[sessionId] = text
                        }
                        "turn/end" -> {
                            val text = lastText.remove(sessionId)
                            pending.remove(sessionId)?.complete(text)
                        }
                    }
                }

                override fun onSessionStatus(sessionId: String, running: Boolean) {
                    if (!running) {
                        // safety net: turn/end could have been lost
                        val text = lastText.remove(sessionId)
                        if (text != null) pending.remove(sessionId)?.complete(text)
                    }
                }

                override fun onPromptError(sessionId: String, message: String) {
                    log.debug("completion prompt error: $message")
                    lastText.remove(sessionId)
                    pending.remove(sessionId)?.complete(null)
                }

                override fun onProcessExit(code: Int?) = failAll()

                override fun onStatus(status: DshSdkClient.Status, detail: String?) {
                    if (status == DshSdkClient.Status.FAILED) {
                        log.warn("DSH completion process failed: ${detail ?: "unknown error"}")
                        failAll()
                    }
                }
            })
        }

        private fun failAll() {
            for (id in pending.keys.toList()) {
                pending.remove(id)?.complete(null)
            }
            lastText.clear()
        }

        suspend fun request(prompt: String, timeoutMs: Long): String? {
            if (closed) return null
            val sessionId = DshSessionStore.COMPLETION_SESSION_PREFIX + UUID.randomUUID()
            val deferred = CompletableDeferred<String?>()
            pending[sessionId] = deferred
            lastText.remove(sessionId)
            try {
                val blocks = mapper.createArrayNode()
                val block = blocks.addObject()
                block.put("type", "text")
                block.put("text", prompt)
                client.start()
                client.prompt(sessionId, blocks)
                return withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (e: Exception) {
                log.debug("completion request failed", e)
                return null
            } finally {
                pending.remove(sessionId)
                lastText.remove(sessionId)
            }
        }

        fun closeAsync() {
            if (closed) return
            closed = true
            failAll()
            client.stop()
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    client.dispose()
                } catch (ignored: Exception) {
                    // ignore
                }
            }
        }

        override fun dispose() {
            closed = true
            try {
                client.dispose()
            } catch (ignored: Exception) {
                // ignore
            }
        }

        private fun assistantText(event: JsonNode): String {
            val content = event.get("data")?.get("message")?.get("content") ?: return ""
            val sb = StringBuilder()
            for (block in content) {
                if (block.get("type")?.asText() == "text") {
                    sb.append(block.get("text")?.asText() ?: "")
                }
            }
            return sb.toString()
        }

    }

    // ---------- Esc: hide and retry in N seconds ----------

    private fun attachEscListener(editor: Editor) {
        if (!attachedEditors.add(editor)) return
        val handler = InlineCompletion.getHandlerOrNull(editor)
        if (handler == null) {
            attachedEditors.remove(editor)
            return
        }
        handler.addEventListener(object : InlineCompletionEventListener {
            override fun on(event: InlineCompletionEventType) {
                when (event) {
                    is InlineCompletionEventType.Hide -> {
                        // finishType=ESCAPE_PRESSED also arrives when Esc is pressed while
                        // waiting for a response. Retry only if a suggestion was actually
                        // shown (otherwise Esc = "cancel the request", with no pushy retry).
                        val wasShown = suggestionShown.remove(editor) == true
                        if (wasShown && event.finishType == FinishType.ESCAPE_PRESSED) onEscape(editor)
                    }
                    is InlineCompletionEventType.Insert,
                    is InlineCompletionEventType.AfterInsert -> {
                        suggestionShown.remove(editor)
                        cancelRetry()
                    }
                    else -> Unit
                }
            }
        }, this)
    }

    private fun onEscape(editor: Editor) {
        val delayMs = app.appState.tabRetryDelayMs.coerceIn(1_000, 30_000).toLong()
        suppressAutoUntil = System.currentTimeMillis() + delayMs
        retryAlarm.cancelAllRequests()
        retryAlarm.addRequest({
            if (project.isDisposed || editor.isDisposed) return@addRequest
            suppressAutoUntil = 0
            trigger(editor)
            scheduleIdleStop()
        }, delayMs.toInt())
        log.debug("Tab completion dismissed by Esc; retry in ${delayMs}ms with the whole file")
    }

    private fun cancelRetry() {
        retryAlarm.cancelAllRequests()
        suppressAutoUntil = 0
    }

    override fun dispose() {
        retryAlarm.cancelAllRequests()
        idleAlarm.cancelAllRequests()
        synchronized(clientLock) {
            engine?.closeAsync()
            engine = null
        }
        attachedEditors.clear()
        suggestionShown.clear()
    }

    private companion object {
        const val CURSOR_MARKER = "<<<DSH_CURSOR>>>"

        const val DEBOUNCE_MS = 450L
        const val NORMAL_MAX_TOKENS = 4096
        const val EXTENDED_MAX_TOKENS = 12_000
        const val NORMAL_TIMEOUT_MS = 90_000L
        const val EXTENDED_TIMEOUT_MS = 180_000L
        const val IDLE_STOP_MS = 10 * 60 * 1000

        /** Protection against gigantic files: the whole file up to this limit goes into the prompt. */
        const val MAX_FILE_CHARS = 400_000

        /** ~12,000 tokens of code; for the short mode there is headroom for 4096 tokens. */
        const val EXTENDED_MAX_CHARS = 48_000
        const val NORMAL_MAX_CHARS = 12_000

        fun sanitize(raw: String, extended: Boolean): String? {
            // Leading newlines are NOT trimmed: the model often starts an insertion with
            // a new line (for example after "{"), and without it the code glues to the current line.
            var s = raw.replace("\r\n", "\n").replace("\r", "\n")
                .replace(CURSOR_MARKER, "")
            if (s.isBlank()) return null

            // The model sometimes wraps code in ```...``` anyway — take the contents.
            val fence = Regex("```[\\w+.#-]*\\n([\\s\\S]*?)```").find(s)
            if (fence != null) {
                s = fence.groupValues[1]
            } else if (s.trimStart().startsWith("```")) {
                s = s.trimStart().substringAfter('\n').substringBeforeLast("```")
            }
            // Trim trailing spaces, but keep one final newline:
            // it separates the insertion from the code that already follows the caret.
            s = s.trimEnd(' ', '\t', '\r')
            while (s.endsWith("\n\n")) s = s.dropLast(1)
            if (s.isBlank()) return null

            val max = if (extended) EXTENDED_MAX_CHARS else NORMAL_MAX_CHARS
            if (s.length > max) {
                val cut = s.substring(0, max)
                s = cut.substringBeforeLast('\n', cut)
            }
            return s.ifBlank { null }
        }
    }
}
