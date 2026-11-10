package com.dsh.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Global plugin settings (stored in the IDE config, dsh-assistant.xml).
 * Settings synchronized through DSH live in DshProjectSettings (.dsh/pycharm-assistant.json).
 */
@Service(Service.Level.APP)
@State(name = "DshAssistantAppSettings", storages = [Storage("dsh-assistant.xml")])
class DshAppSettings : PersistentStateComponent<DshAppSettings.State> {

    class WindowRef {
        var id: String = ""
        var name: String = ""
    }

    class State {
        /** DSH launch command (for example "dsh" or the full path to the binary). */
        var dshCommand: String = "dsh"

        /** Web server launch arguments. */
        var launchArguments: String = "web --no-open"

        /** DSH port. */
        var port: Int = 3080

        /** Start the SDK and connect automatically when the panel opens. */
        var autoConnect: Boolean = true

        /** Open a chat when the project opens. */
        var openChatOnStartup: Boolean = true

        /** Saved chat tabs (DSH session ids). */
        var chats: MutableList<String> = mutableListOf()

        /** Panel layout: AUTO (based on the window size) / VERTICAL / HORIZONTAL. */
        var layoutMode: String = "AUTO"

        /** Show the "Changes" panel. */
        var showChangesPanel: Boolean = true

        // --- SDK / model ---

        /** LLM provider for the SDK (deepseek-official / deepseek-account / custom). */
        var provider: String = "deepseek-official"

        /** Model for the SDK. */
        var model: String = "deepseek-v4-pro"

        /** Models available in the chat switcher (comma-separated). */
        var models: String = "deepseek-v4-pro,deepseek-flash"

        /** Reasoning effort (empty = default). */
        var reasoningEffort: String = ""

        /**
         * DSH SDK permission mode: "danger-full-access" — never ask for confirmation
         * (everything is allowed), "workspace-write" — ask before important actions.
         */
        var permissionMode: String = "danger-full-access"

        /** Output token limit (0 = default). */
        var maxTokens: Int = 0

        // --- context ---

        /** Automatically add the whole open file to the request (selection is always added). */
        var autoContext: Boolean = false

        /** Max characters of the file/selection in the context. */
        var maxContextChars: Int = 40000

        // --- TAB completion ---

        /** Enable DSH inline suggestions: gray text, Tab to accept. */
        var tabCompletionEnabled: Boolean = true

        /**
         * Extended TAB help: allows long completions (up to 12,000 tokens).
         * When off — short suggestions (1–10 lines, 1024 token limit).
         */
        var tabExtendedHelp: Boolean = false

        /** How many milliseconds after Esc to retry the request (3 s by default). */
        var tabRetryDelayMs: Int = 3000

        /** Chat text scale, % (80–140). */
        var chatFontScale: Int = 100

        /** Model labels from DSH: "id=Name;id2=Name2" (filled in automatically). */
        var modelLabels: String = ""

        /** Synchronize settings/models with the original DSH (read from ~/.dsh). */
        var syncWithDsh: Boolean = true

        /** true — hardware JCEF rendering (smoother); false — off-screen (OSR). */
        var useHardwareBrowser: Boolean = true

        /** Last selected chat mode. */
        var lastMode: String = "AGENT"

        // --- changes ---

        /** Track file changes (the "Changes" panel). */
        var trackOnProjectOpen: Boolean = true

        /** Maximum file size (bytes) for the baseline snapshot and diff. */
        var maxFileBytes: Int = 524288

        /** Directories excluded from tracking (comma-separated). */
        var excludedDirs: String =
            ".git,.idea,.venv,venv,env,node_modules,__pycache__,build,dist,target,out,.gradle,.svn,.hg,.tox,.mypy_cache,.pytest_cache"

        /** How many checkpoints to keep (rollback as in Cursor). */
        var maxCheckpoints: Int = 10

        /** Add a block describing the plugin settings to the project AGENTS.md. */
        var syncAgentsMd: Boolean = true

        /** Stop the DSH started by the plugin when the project closes. */
        var stopDshOnClose: Boolean = false
    }

    var appState: State = State()

    override fun getState(): State = appState

    override fun loadState(s: State) {
        appState = s
    }

    companion object {
        @JvmStatic
        fun getInstance(): DshAppSettings =
            ApplicationManager.getApplication().getService(DshAppSettings::class.java)
    }
}
