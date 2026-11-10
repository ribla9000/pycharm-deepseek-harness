package com.dsh.assistant.settings

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project plugin settings synchronized through DSH.
 *
 * The `.dsh/pycharm-assistant.json` file lives in the project root, i.e. in the DSH workspace.
 * The DSH agent can read/change it right in the chat — the plugin watches the file through VFS
 * and applies the changes on the fly (live two-way synchronization).
 */
@Service(Service.Level.PROJECT)
class DshProjectSettings(private val project: Project) : Disposable {

    @JsonIgnoreProperties(ignoreUnknown = true)
    class Json {
        /** Base DSH address (without the token), e.g. http://127.0.0.1:3080/ */
        var dshUrl: String = "http://127.0.0.1:3080/"

        /** Full URL from `dsh web` (contains the token). */
        var tokenUrl: String = ""

        /** Auto-connect when the window opens. */
        var autoConnect: Boolean = true

        /** Project root (informational, the plugin updates it itself). */
        var workspace: String = ""

        /** Id of the last active window. */
        var lastWindow: String = ""

        /** User-defined session names (id → name). */
        var sessionNames: MutableMap<String, String> = mutableMapOf()

        /** Session archive (ids of sessions hidden from the list). */
        var archivedSessions: MutableList<String> = mutableListOf()

        /** Per-session confirmation policy: "never" — anything is allowed, "ask" — ask. */
        var sessionPolicies: MutableMap<String, String> = mutableMapOf()

        /** Open chat tabs (session ids) — stored in the project, restored on startup. */
        var openChats: MutableList<String> = mutableListOf()

        /** Session statuses for list indicators: "error" | "question" | "answered" | "new". */
        var sessionStates: MutableMap<String, String> = mutableMapOf()

        /** Session chains: chat id → DSH session ids in order (after SDK restarts). */
        var sessionChains: MutableMap<String, MutableList<String>> = mutableMapOf()

        // --- mirror of the global settings: everything is synchronized through DSH ---
        var provider: String? = null
        var model: String? = null
        var models: String? = null
        var reasoningEffort: String? = null
        var permissionMode: String? = null
        var autoContext: Boolean? = null
        var maxContextChars: Int? = null

        // --- TAB completion (mirror of the global settings) ---
        var tabCompletionEnabled: Boolean? = null
        var tabExtendedHelp: Boolean? = null
        var tabRetryDelayMs: Int? = null
    }

    @Volatile
    var settings: Json = Json()

    private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val listeners = CopyOnWriteArrayList<Runnable>()

    /** We only write the file ourselves; VFS events are ignored while we do. */
    @Volatile
    private var writing = false

    val file: File
        get() {
            val base = project.basePath ?: System.getProperty("user.home") ?: "."
            return File(base, ".dsh/pycharm-assistant.json")
        }

    init {
        load()
        ensureFileExists()
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (writing) return
                    val path = file.absolutePath
                    val touched = events.any { e ->
                        when (e) {
                            is VFileContentChangeEvent -> e.file.path == path
                            is VFileCreateEvent -> e.file?.path == path
                            is VFileDeleteEvent -> e.file.path == path
                            else -> false
                        }
                    }
                    if (touched) {
                        load()
                        notifyListeners()
                    }
                }
            })
    }

    fun load() {
        val f = file
        if (f.isFile) {
            settings = try {
                mapper.readValue(f, Json::class.java) ?: Json()
            } catch (e: Exception) {
                Json()
            }
        }
        if (settings.dshUrl.isBlank()) settings.dshUrl = "http://127.0.0.1:3080/"
        applyMirror()
    }

    /** Settings changed through DSH (in the JSON) are applied to the plugin on the fly. */
    private fun applyMirror() {
        val s = settings
        val app = DshAppSettings.getInstance()
        s.provider?.takeIf { it.isNotBlank() }?.let { app.appState.provider = it }
        s.model?.takeIf { it.isNotBlank() }?.let { app.appState.model = it }
        s.models?.takeIf { it.isNotBlank() }?.let { app.appState.models = it }
        s.reasoningEffort?.let { app.appState.reasoningEffort = it }
        s.permissionMode?.takeIf { it.isNotBlank() }?.let { app.appState.permissionMode = it }
        s.autoContext?.let { app.appState.autoContext = it }
        s.maxContextChars?.takeIf { it > 0 }?.let { app.appState.maxContextChars = it }
        s.tabCompletionEnabled?.let { app.appState.tabCompletionEnabled = it }
        s.tabExtendedHelp?.let { app.appState.tabExtendedHelp = it }
        s.tabRetryDelayMs?.takeIf { it in 500..60_000 }?.let { app.appState.tabRetryDelayMs = it }
    }

    fun save() {
        writing = true
        try {
            val f = file
            f.parentFile?.mkdirs()
            settings.workspace = project.basePath ?: ""
            // mirror the global settings into the file — DSH sees and changes them from the chat
            val app = DshAppSettings.getInstance()
            settings.provider = app.appState.provider
            settings.model = app.appState.model
            settings.models = app.appState.models
            settings.reasoningEffort = app.appState.reasoningEffort
            settings.permissionMode = app.appState.permissionMode
            settings.autoContext = app.appState.autoContext
            settings.maxContextChars = app.appState.maxContextChars
            settings.tabCompletionEnabled = app.appState.tabCompletionEnabled
            settings.tabExtendedHelp = app.appState.tabExtendedHelp
            settings.tabRetryDelayMs = app.appState.tabRetryDelayMs
            mapper.writeValue(f, settings)
        } catch (ignored: Exception) {
            // the settings file is unavailable — not critical
        } finally {
            writing = false
        }
    }

    private fun ensureFileExists() {
        if (!file.isFile) save()
    }

    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        for (l in listeners) {
            try {
                l.run()
            } catch (ignored: Exception) {
                // ignore
            }
        }
    }

    override fun dispose() = Unit
}
