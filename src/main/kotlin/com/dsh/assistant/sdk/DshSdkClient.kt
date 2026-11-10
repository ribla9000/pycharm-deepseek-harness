package com.dsh.assistant.sdk

import com.dsh.assistant.settings.DshAppSettings
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * DSH SDK client: launches `dsh --profile sdk` (the same harness and the same settings
 * as the web app) and talks newline-delimited JSON-RPC 2.0 over stdio.
 *
 * Requests: initialize, session/prompt, shutdown.
 * Notifications: session.event (all session events: messages, tool calls, title…),
 * session.status (running/idle), subagent.*.
 *
 * Each chat tab = a separate sessionId = an independent DSH session.
 */
class DshSdkClient(
    private val project: Project,
    private val appSettings: DshAppSettings,
    private val options: Options = Options(),
) : Disposable {

    /**
     * Purpose of the process. A regular chat uses the profile with tools and session titles;
     * TAB completion uses a separate process with all tools disabled:
     * that turns the agent loop into a single clean text response (latency ~1–2 s).
     */
    class Options(
        /** Unique name: the name of the patch file in temp depends on it. */
        val purpose: String = "chat",
        /** Hard output token limit (otherwise taken from the settings). */
        val maxTokens: Int? = null,
        /** Completion profile: disable tools and LLM session titles. */
        val completion: Boolean = false,
        /** Override the permission mode (read-only for completion). */
        val permissionMode: String? = null,
    )

    enum class Status { DOWN, STARTING, READY, BUSY, FAILED }

    interface Listener {
        fun onStatus(status: Status, detail: String?) {}
        fun onEvent(sessionId: String, event: JsonNode) {}
        fun onSessionStatus(sessionId: String, running: Boolean) {}
        fun onProcessExit(code: Int?) {}

        /** The server accepted the request: session `sessionId` lives in the current process. */
        fun onPromptAccepted(sessionId: String) {}
        fun onPromptError(sessionId: String, message: String) {}
    }

    private val log = Logger.getInstance(DshSdkClient::class.java)
    private val mapper = ObjectMapper()

    @Volatile
    var status: Status = Status.DOWN
        private set

    @Volatile
    var statusDetail: String? = null
        private set

    @Volatile
    private var process: Process? = null
    private val writeLock = Object()

    /** Serializes process start/stop: otherwise the old onExit overwrote the new process status. */
    private val processLock = Object()
    private var writer: java.io.BufferedWriter? = null
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonNode>>()
    private val promptSessions = ConcurrentHashMap<Int, String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val runningSessions = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-sdk") }
    private val lifecycle = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-sdk-lifecycle") }
    private val stderrTail = java.util.ArrayDeque<String>()

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun isReady(): Boolean = status == Status.READY || status == Status.BUSY

    /**
     * Number of the last successful process start. The SDK cannot continue an already saved
     * session (`session/prompt` for an existing id answers "already exists"), so after every
     * process restart the sessions have to be created anew — by this number the plugin
     * understands that an old id is no longer valid in the new process.
     */
    @Volatile
    var processGeneration: Int = 0
        private set

    /** Starts the SDK if it is not running yet (idempotent, no races with restarts). */
    fun start() {
        lifecycle.execute { ensureStarted() }
    }

    private fun ensureStarted() {
        if (process?.isAlive == true && status != Status.FAILED) return
        if (process?.isAlive == true) killCurrent(3_000)
        doStart()
    }

    private fun doStart() {
        setStatus(Status.STARTING, null)
        try {
            // the command may contain arguments: "dsh" or "npx --yes @deepseek-ai/dsh"
            val tokens = appSettings.appState.dshCommand.trim()
                .split(Regex("\\s+")).filter { it.isNotBlank() }
            val command = tokens + listOf("--profile", "sdk") + patchArgs()
            val pb = ProcessBuilder(command)
            pb.directory(File(project.basePath ?: System.getProperty("user.home")))
            // Permissions: danger-full-access → "never" policy (never ask for confirmation)
            val perm = options.permissionMode ?: appSettings.appState.permissionMode.trim()
            if (perm.isNotEmpty()) {
                pb.environment()["DSH_PERMISSION_MODE"] = perm
            }
            val p = pb.start()
            synchronized(processLock) { process = p }
            synchronized(writeLock) {
                writer = p.outputStream.bufferedWriter(Charsets.UTF_8)
            }
            Thread(
                {
                    p.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        log.warn("dsh sdk stderr: $line")
                        synchronized(stderrTail) {
                            stderrTail.addLast(line)
                            while (stderrTail.size > 40) stderrTail.removeFirst()
                        }
                    }
                },
                "dsh-sdk-stderr").apply { isDaemon = true }.start()

            Thread(
                {
                    p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        try {
                            dispatch(line)
                        } catch (e: Exception) {
                            log.warn("sdk dispatch failed", e)
                        }
                    }
                },
                "dsh-sdk-reader").apply { isDaemon = true }.start()

            // hook process exit right away: if it dies during initialize,
            // the pending request wakes failPending instead of waiting for the timeout
            p.onExit().thenAccept { proc -> handleExit(proc) }

            val initResp = requestSync("initialize", initParams())
            log.info("DSH SDK initialized: $initResp")
            processGeneration += 1
            // no old session is running in the new process: otherwise a "stuck" id
            // from a killed process kept the panel in the "DSH busy" state forever
            runningSessions.clear()
            setStatus(Status.READY, null)
        } catch (e: Exception) {
            log.warn("DSH SDK start failed", e)
            if (patchAttempted && !retriedWithoutPatch && isCurrentAlive()) {
                retriedWithoutPatch = true
                killCurrent(3_000)
                doStart()
            } else {
                killCurrent(3_000)
                // if the process is already dead, handleExit has set the status and details —
                // do not overwrite them with a less useful message
                if (status != Status.FAILED && status != Status.DOWN) {
                    setStatus(Status.FAILED, friendlyStartError(e))
                }
            }
        }
    }

    private fun isCurrentAlive(): Boolean =
        synchronized(processLock) { process?.isAlive == true }

    /**
     * The process has exited. React only if it is still the "current" one:
     * the exit of a process killed on purpose (restart/stop/dispose) is not a crash
     * and must not put the SDK into FAILED — otherwise the UI showed a false "code 137".
     */
    private fun handleExit(proc: Process) {
        val current = synchronized(processLock) {
            if (process === proc) {
                process = null
                true
            } else {
                false
            }
        }
        if (!current) return
        synchronized(writeLock) { writer = null }
        runningSessions.clear()
        val code = try {
            proc.exitValue()
        } catch (e: Exception) {
            -1
        }
        val tail = synchronized(stderrTail) { stderrTail.joinToString("\n") }
        val detail = if (code != 0) {
            val hint = if (code == 137) " (137 — process killed, often due to out-of-memory)" else ""
            "the DSH process exited (code $code$hint)" +
                if (tail.isBlank()) "" else ": ${tail.take(300)}"
        } else {
            null
        }
        failPending("DSH SDK exited (code $code)")
        ApplicationManager.getApplication().invokeLater {
            setStatus(if (detail == null) Status.DOWN else Status.FAILED, detail ?: "the DSH process exited")
            for (l in listeners) l.onProcessExit(code)
        }
    }

    /** Kills the current process. After that its exit code no longer counts as a crash. */
    private fun killCurrent(waitMs: Long) {
        val p = synchronized(processLock) {
            val cur = process
            process = null
            cur
        }
        runningSessions.clear()
        synchronized(writeLock) { writer = null }
        if (p == null) return
        try {
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(waitMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }
        } catch (ignored: InterruptedException) {
            p.destroyForcibly()
        } catch (ignored: Exception) {
            // ignore
        }
    }

    /** Wakes everyone waiting for a response — the process is dead, no response will come. */
    private fun failPending(reason: String) {
        val ids = pending.keys.toList()
        for (id in ids) {
            pending.remove(id)?.completeExceptionally(IllegalStateException(reason))
        }
        promptSessions.clear()
    }

    /**
     * Patch for the SDK profile:
     *  - enables ask_user_question (in the web profile the client registers it),
     *  - enables LLM generation of session titles (disabled in the sdk profile).
     * For older dsh without these packages there is a fallback: start without the patch.
     */
    private var patchAttempted = false

    @Volatile
    private var retriedWithoutPatch = false

    private fun patchArgs(): List<String> {
        if (patchAttempted) return emptyList()
        patchAttempted = true
        return try {
            val dir = File(System.getProperty("java.io.tmpdir"))
            val patch = File(dir, "dsh-assistant-${options.purpose}.patch.yml")
            // always overwrite: the patch content may change between plugin versions
            patch.writeText(if (options.completion) COMPLETION_PATCH else CHAT_PATCH)
            listOf("--patch", patch.absolutePath)
        } catch (e: Exception) {
            log.warn("cannot write sdk patch", e)
            emptyList()
        }
    }

    private companion object {
        /**
         * Regular chat SDK profile: enable ask_user_question (in the web profile
         * the client registers it) and LLM session titles.
         */
        const val CHAT_PATCH =
            "# PyCharm DSH Assistant plugin: SDK profile extensions.\n" +
                "- insert:\n" +
                "    - id: ask-user\n" +
                "      name: '@deepseek-ai/dsh-tool-ask-user'\n" +
                "      config:\n" +
                "        timeout: 90\n" +
                "# Automatic session titles (LLM titles are disabled in the sdk profile).\n" +
                "- id: session-title-llm\n" +
                "  disabled: false\n"

        /**
         * TAB completion profile: keep the agent loop, but remove all tools and
         * extra system insertions. The model answers with a single message
         * containing code — that is much faster than an agent turn and cannot touch files.
         */
        const val COMPLETION_PATCH = """
# PyCharm DSH Assistant plugin: TAB completion profile.
# The tools service stays (other plugins depend on it), but no tool is registered.
- id: tool-bash
  disabled: true
- id: tool-pwsh
  disabled: true
- id: tool-jobs
  disabled: true
- id: tool-fs
  disabled: true
- id: tool-fs-search
  disabled: true
- id: tool-skill
  disabled: true
- id: tool-subagent-control
  disabled: true
- id: tool-subagent-list-agents
  disabled: true
- id: tool-subagent
  disabled: true
- id: tool-subagent-fork
  disabled: true
- id: tool-workflow
  disabled: true
- id: tool-todo
  disabled: true
- id: tool-goal
  disabled: true
- id: tool-web
  disabled: true
- id: mcp-resources
  disabled: true
# No project rules, skills, commands or goals — just a clean code completion.
- id: agent-instructions
  disabled: true
- id: skill
  disabled: true
- id: skill-filesystem
  disabled: true
# Session titles are not needed: completion sessions are technical and hidden from the list.
- id: session-title-llm
  disabled: true
"""
    }

    /** Last stderr lines of the SDK process (for diagnostics). */
    fun recentStderr(): List<String> = synchronized(stderrTail) { stderrTail.toList() }

    private fun friendlyStartError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("No such file") || msg.contains("error=2") || msg.contains("error=3") ->
                "Command \"${appSettings.appState.dshCommand.split(Regex("\\s+")).first()}\" was not found. " +
                    "Install DSH (npm i -g @deepseek-ai/dsh) or set the command in " +
                    "Settings → Tools → DSH Assistant (for example: npx --yes @deepseek-ai/dsh)."
            else -> msg
        }
    }

    private fun initParams(): ObjectNode {
        val n = mapper.createObjectNode()
        n.put("cwd", project.basePath ?: "")
        n.put("provider", appSettings.appState.provider.trim().ifBlank { "deepseek-official" })
        n.put("model", appSettings.appState.model.trim().ifBlank { "deepseek-v4-pro" })
        val effort = appSettings.appState.reasoningEffort.trim()
        if (effort.isNotEmpty()) n.put("reasoningEffort", effort)
        val maxTokens = options.maxTokens ?: appSettings.appState.maxTokens
        if (maxTokens > 0) n.put("maxTokens", maxTokens)
        return n
    }

    /** Synchronous request (only for initialize from a background thread). */
    private fun requestSync(method: String, params: ObjectNode): JsonNode {
        val id = nextId.getAndIncrement()
        val frame = mapper.createObjectNode()
        frame.put("jsonrpc", "2.0")
        frame.put("id", id)
        frame.put("method", method)
        frame.set<ObjectNode>("params", params)
        val future = CompletableFuture<JsonNode>()
        pending[id] = future
        writeFrame(frame)
        val resp = future.get(60, TimeUnit.SECONDS)
        if (resp.has("error")) {
            throw IllegalStateException(resp.get("error").get("message").asText("SDK error"))
        }
        return resp.get("result") ?: mapper.createObjectNode()
    }

    /**
     * Sends a prompt to the session. Asynchronous; events arrive through [Listener.onEvent].
     * If the SDK is not running yet (or has crashed), it starts it and waits for readiness.
     */
    fun prompt(sessionId: String, contentBlocks: ArrayNode) {
        executor.execute {
            if (!isReady()) {
                start()
            }
            if (waitReady()) {
                sendPrompt(sessionId, contentBlocks)
            } else {
                setStatus(Status.FAILED, "DSH SDK failed to start")
                notifyPromptError(sessionId, "DSH SDK failed to start")
            }
        }
    }

    private fun waitReady(timeoutMs: Long = 180_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isReady()) return true
            if (status == Status.FAILED) return false
            try {
                Thread.sleep(200)
            } catch (ignored: InterruptedException) {
                return false
            }
        }
        return isReady()
    }

    private fun sendPrompt(sessionId: String, contentBlocks: ArrayNode) {
        try {
            runningSessions.add(sessionId)
            updateBusyStatus()
            val id = nextId.getAndIncrement()
            val frame = mapper.createObjectNode()
            frame.put("jsonrpc", "2.0")
            frame.put("id", id)
            frame.put("method", "session/prompt")
            val params = mapper.createObjectNode()
            params.put("sessionId", sessionId)
            params.set<ArrayNode>("contentBlocks", contentBlocks)
            frame.set<ObjectNode>("params", params)
            pending[id] = CompletableFuture()
            promptSessions[id] = sessionId
            writeFrame(frame)
        } catch (e: Exception) {
            log.warn("prompt failed", e)
            // request not sent — the session is not running, otherwise the status stays "DSH busy"
            runningSessions.remove(sessionId)
            updateBusyStatus()
            val msg = e.message ?: e.javaClass.simpleName
            // if the process is being replaced right now (model change/"Stop"), this is no SDK error
            if (isCurrentAlive()) setStatus(Status.FAILED, msg)
            notifyPromptError(sessionId, "Could not send the request to DSH: $msg")
        }
    }

    /** Tells the chat the request was not sent: otherwise the UI would stay in "agent works" state. */
    private fun notifyPromptError(sessionId: String, message: String) {
        ApplicationManager.getApplication().invokeLater {
            for (l in listeners) {
                try {
                    l.onPromptError(sessionId, message)
                } catch (ignored: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun writeFrame(frame: ObjectNode) {
        synchronized(writeLock) {
            val w = writer ?: throw IllegalStateException("DSH SDK is not running")
            w.write(mapper.writeValueAsString(frame))
            w.newLine()
            w.flush()
        }
    }

    private fun dispatch(line: String) {
        if (line.isBlank()) return
        val node = try {
            mapper.readTree(line)
        } catch (e: Exception) {
            return
        }
        val idNode = node.get("id")
        if (idNode != null && idNode.isInt && (node.has("result") || node.has("error"))) {
            val id = idNode.asInt()
            pending.remove(id)?.complete(node)
            val sid = promptSessions.remove(id)
            if (sid != null) {
                if (node.has("error")) {
                    // request rejected (e.g. "session already exists"): no turn will start,
                    // so the session is off "busy", otherwise the status sticks forever
                    runningSessions.remove(sid)
                    updateBusyStatus()
                    val msg = node.get("error")?.get("message")?.asText("SDK error") ?: "SDK error"
                    ApplicationManager.getApplication().invokeLater {
                        for (l in listeners) {
                            try {
                                l.onPromptError(sid, msg)
                            } catch (ignored: Exception) {
                                // ignore
                            }
                        }
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        for (l in listeners) {
                            try {
                                l.onPromptAccepted(sid)
                            } catch (ignored: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            }
            return
        }
        val method = node.get("method")?.asText() ?: return
        val params = node.get("params") ?: return
        when (method) {
            "session.event" -> {
                val sid = params.get("sessionId")?.asText() ?: return
                val event = params.get("event") ?: return
                for (l in listeners) {
                    try {
                        l.onEvent(sid, event)
                    } catch (ignored: Exception) {
                        // ignore
                    }
                }
            }
            "session.status" -> {
                val sid = params.get("sessionId")?.asText() ?: return
                val running = "running" == params.get("status")?.asText()
                if (running) runningSessions.add(sid) else runningSessions.remove(sid)
                updateBusyStatus()
                for (l in listeners) {
                    try {
                        l.onSessionStatus(sid, running)
                    } catch (ignored: Exception) {
                        // ignore
                    }
                }
            }
            "subagent.finished" -> {
                // a child session (subagent) may have no idle of its own: if its id stays
                // in runningSessions, the panel would show "DSH busy" forever
                val child = params.get("childSessionId")?.asText() ?: params.get("agentId")?.asText()
                if (child != null && runningSessions.remove(child)) updateBusyStatus()
            }
            else -> Unit
        }
    }

    private fun updateBusyStatus() {
        if (status == Status.FAILED || status == Status.DOWN || status == Status.STARTING) return
        setStatus(if (runningSessions.isEmpty()) Status.READY else Status.BUSY, null)
    }

    private fun setStatus(s: Status, detail: String?) {
        status = s
        statusDetail = detail
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            for (l in listeners) l.onStatus(s, detail)
        } else {
            app.invokeLater {
                for (l in listeners) l.onStatus(s, detail)
            }
        }
    }

    fun isAnySessionRunning(): Boolean = runningSessions.isNotEmpty()

    /**
     * Process restart (model/effort change, "Stop"). Sessions are saved in ~/.dsh.
     * The old process is killed first and only then the new one starts: otherwise its exit code
     * arrived later and marked the SDK as crashed ("code 137") while the process was alive.
     */
    fun restart() {
        setStatus(Status.STARTING, null)
        lifecycle.execute {
            killCurrent(10_000)
            ensureStarted()
        }
    }

    /**
     * Stops the process while keeping the client ready for another start.
     * Needed by the completion service: after idle time the process frees memory,
     * and the next suggestion starts it again.
     */
    fun stop() {
        lifecycle.execute {
            killCurrent(3_000)
            failPending("DSH SDK stopped")
            setStatus(Status.DOWN, null)
        }
    }

    override fun dispose() {
        val p = synchronized(processLock) {
            val cur = process
            process = null
            cur
        }
        runningSessions.clear()
        try {
            synchronized(writeLock) {
                val w = writer
                if (w != null) {
                    w.write("{\"jsonrpc\":\"2.0\",\"id\":-1,\"method\":\"shutdown\",\"params\":{}}")
                    w.newLine()
                    w.flush()
                }
            }
            // wait for a clean shutdown: sessions must have time to flush to disk
            p?.waitFor(12, TimeUnit.SECONDS)
        } catch (ignored: Exception) {
            // ignore
        }
        if (p?.isAlive == true) p.destroyForcibly()
        synchronized(writeLock) { writer = null }
        executor.shutdownNow()
        lifecycle.shutdownNow()
    }
}
