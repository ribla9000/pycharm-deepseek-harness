package com.dsh.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import javax.swing.JSeparator
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/** Global plugin settings (shared by all projects). */
class DshApplicationConfigurable : Configurable {

    private val app = DshAppSettings.getInstance()

    private val dshCommandField = JBTextField()
    private val providerField = JBTextField()
    private val modelField = JBTextField()
    private val modelsField = JBTextField()
    private val reasoningEffortField = JBTextField()
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(0, 0, 1_000_000, 1000))
    private val permissionCombo = javax.swing.JComboBox(arrayOf(
        "Full access — never ask (recommended)",
        "Ask before important actions"))
    private val autoConnect = JBCheckBox("Start DSH SDK automatically when a project opens")
    private val openOnStartup = JBCheckBox("Open a chat when a project opens")
    private val autoContext = JBCheckBox("Add the whole open file to the request (selection is always added)")
    private val maxContextSpinner = JSpinner(SpinnerNumberModel(40000, 1000, 500_000, 1000))
    private val tabCompletion = JBCheckBox(
        "Enable DSH TAB completion (gray inline suggestions in the editor, Tab to accept)")
    private val tabExtendedHelp = JBCheckBox(
        "Extended TAB help — large continuations (up to 12,000 tokens)")
    private val tabRetrySpinner = JSpinner(SpinnerNumberModel(3, 1, 30, 1))
    private val trackOnOpen = JBCheckBox("Track file changes (the \"Changes\" panel)")
    private val maxFileBytesSpinner = JSpinner(SpinnerNumberModel(524288, 65536, 20 * 1024 * 1024, 65536))
    private val maxCheckpointsSpinner = JSpinner(SpinnerNumberModel(10, 1, 50, 1))
    private val excludedDirsField = JBTextField()
    private val syncAgentsMd = JBCheckBox("Add a plugin section to the project AGENTS.md (so DSH knows about the settings)")
    private val stopDshOnClose = JBCheckBox("Stop the DSH started by the plugin when the project closes")
    private val syncWithDsh = JBCheckBox("Take provider, model, effort and model list from the original DSH (~/.dsh)")
    private val hardwareBrowser = JBCheckBox("Hardware-accelerated UI rendering (smoother; disable it if you see repaint issues)")
    private val dshSourceLabel = JBLabel()

    override fun getDisplayName(): String = "DSH Assistant"

    override fun createComponent(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("DSH command:", dshCommandField)
            .addComponent(JBLabel("<b>SDK / model (dsh --profile sdk)</b>"))
            .addSeparator()
            .addComponent(syncWithDsh)
            .addComponent(dshSourceLabel)
            .addComponent(hardwareBrowser)
            .addLabeledComponent("Provider:", providerField)
            .addLabeledComponent("Model:", modelField)
            .addLabeledComponent("Models in the chat switcher (comma-separated):", modelsField)
            .addLabeledComponent("Reasoning effort (empty = auto):", reasoningEffortField)
            .addLabeledComponent("Max response tokens (0 = auto):", maxTokensSpinner)
            .addLabeledComponent("Agent permissions:", permissionCombo)
            .addComponent(JBLabel("<b>Chat and context</b>"))
            .addSeparator()
            .addComponent(autoConnect)
            .addComponent(openOnStartup)
            .addComponent(autoContext)
            .addLabeledComponent("Max file characters in context:", maxContextSpinner)
            .addComponent(JBLabel("<b>TAB completion (DSH inline suggestions)</b>"))
            .addSeparator()
            .addComponent(tabCompletion)
            .addComponent(tabExtendedHelp)
            .addLabeledComponent("Retry after Esc (sec):", tabRetrySpinner)
            .addComponent(
                JBLabel("<html><i>The suggestion appears as gray text; <b>Tab</b> accepts it.<br>" +
                    "<b>Esc</b> keeps you typing: after the configured delay DSH repeats the request<br>" +
                    "and returns a new variant based on the whole file.</i></html>"))
            .addComponent(JBLabel("<b>Changes and checkpoints</b>"))
            .addSeparator()
            .addComponent(trackOnOpen)
            .addLabeledComponent("Max file size for diff (bytes):", maxFileBytesSpinner)
            .addLabeledComponent("Checkpoints to keep:", maxCheckpointsSpinner)
            .addLabeledComponent("Excluded directories (comma-separated):", excludedDirsField)
            .addComponent(JBLabel("<b>Other</b>"))
            .addSeparator()
            .addComponent(syncAgentsMd)
            .addComponent(stopDshOnClose)
            .addVerticalGap(6)
            .addComponent(
                JBLabel("<html><i>Project settings (web DSH URL, token) are in Settings → Tools → DSH Assistant (project),<br>" +
                    "stored in .dsh/pycharm-assistant.json and synchronized through DSH.</i></html>"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
        form.border = JBUI.Borders.empty(10)
        reset()
        return form
    }

    override fun isModified(): Boolean {
        val s = app.appState
        return hardwareBrowser.isSelected != s.useHardwareBrowser ||
            syncWithDsh.isSelected != s.syncWithDsh ||
            dshCommandField.text.trim() != s.dshCommand ||
            providerField.text.trim() != s.provider ||
            modelField.text.trim() != s.model ||
            modelsField.text.trim() != s.models ||
            reasoningEffortField.text.trim() != s.reasoningEffort ||
            (maxTokensSpinner.value as Int) != s.maxTokens ||
            permissionCombo.selectedIndex != (if (s.permissionMode == "workspace-write") 1 else 0) ||
            autoConnect.isSelected != s.autoConnect ||
            openOnStartup.isSelected != s.openChatOnStartup ||
            autoContext.isSelected != s.autoContext ||
            (maxContextSpinner.value as Int) != s.maxContextChars ||
            tabCompletion.isSelected != s.tabCompletionEnabled ||
            tabExtendedHelp.isSelected != s.tabExtendedHelp ||
            (tabRetrySpinner.value as Int) * 1000 != s.tabRetryDelayMs ||
            trackOnOpen.isSelected != s.trackOnProjectOpen ||
            (maxFileBytesSpinner.value as Int) != s.maxFileBytes ||
            (maxCheckpointsSpinner.value as Int) != s.maxCheckpoints ||
            excludedDirsField.text.trim() != s.excludedDirs ||
            syncAgentsMd.isSelected != s.syncAgentsMd ||
            stopDshOnClose.isSelected != s.stopDshOnClose
    }

    override fun apply() {
        val s = app.appState
        s.useHardwareBrowser = hardwareBrowser.isSelected
        s.syncWithDsh = syncWithDsh.isSelected
        s.dshCommand = dshCommandField.text.trim().ifBlank { "dsh" }
        s.provider = providerField.text.trim().ifBlank { "deepseek-official" }
        s.model = modelField.text.trim().ifBlank { "deepseek-v4-pro" }
        s.models = modelsField.text.trim().ifBlank { "deepseek-v4-pro,deepseek-flash" }
        s.reasoningEffort = reasoningEffortField.text.trim()
        s.maxTokens = maxTokensSpinner.value as Int
        s.permissionMode = if (permissionCombo.selectedIndex == 1) "workspace-write" else "danger-full-access"
        s.autoConnect = autoConnect.isSelected
        s.openChatOnStartup = openOnStartup.isSelected
        s.autoContext = autoContext.isSelected
        s.maxContextChars = maxContextSpinner.value as Int
        s.tabCompletionEnabled = tabCompletion.isSelected
        s.tabExtendedHelp = tabExtendedHelp.isSelected
        s.tabRetryDelayMs = (tabRetrySpinner.value as Int) * 1000
        s.trackOnProjectOpen = trackOnOpen.isSelected
        s.maxFileBytes = maxFileBytesSpinner.value as Int
        s.maxCheckpoints = maxCheckpointsSpinner.value as Int
        s.excludedDirs = excludedDirsField.text.trim()
        val syncChanged = s.syncAgentsMd != syncAgentsMd.isSelected
        s.syncAgentsMd = syncAgentsMd.isSelected
        s.stopDshOnClose = stopDshOnClose.isSelected
        if (syncChanged) {
            AgentsMdSync.syncAllOpenProjects(s.syncAgentsMd)
        }
        // mirror the settings into .dsh/pycharm-assistant.json so DSH sees them right away
        for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            try {
                project.getService(DshProjectSettings::class.java)?.save()
            } catch (ignored: Exception) {
                // the project may be closing
            }
        }
    }

    private fun refreshDshSource() {
        val dsh = try {
            DshConfigReader.read(
                com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
                    ?: com.intellij.openapi.project.ProjectManager.getInstance().defaultProject,
                dshCommandField.text.trim().ifBlank { "dsh" })
        } catch (e: Exception) {
            null
        }
        dshSourceLabel.text = if (dsh == null) {
            "DSH not found"
        } else {
            "<html><body style='color:#8f949c'>Source: " +
                (dsh.settingsFile?.absolutePath ?: "not found") +
                " · provider <b>${dsh.provider ?: "?"}</b>, model <b>${dsh.model ?: "?"}</b>, effort <b>${dsh.reasoningEffort ?: "auto"}</b>, " +
                "models: ${dsh.models.size}" + (if (dsh.hasCredentials) ", API key found" else ", no API key") + "</body></html>"
        }
    }

    override fun reset() {
        val s = app.appState
        hardwareBrowser.isSelected = s.useHardwareBrowser
        syncWithDsh.isSelected = s.syncWithDsh
        dshCommandField.text = s.dshCommand
        providerField.text = s.provider
        modelField.text = s.model
        refreshDshSource()
        modelsField.text = s.models
        reasoningEffortField.text = s.reasoningEffort
        maxTokensSpinner.value = s.maxTokens
        permissionCombo.selectedIndex = if (s.permissionMode == "workspace-write") 1 else 0
        autoConnect.isSelected = s.autoConnect
        openOnStartup.isSelected = s.openChatOnStartup
        autoContext.isSelected = s.autoContext
        maxContextSpinner.value = s.maxContextChars
        tabCompletion.isSelected = s.tabCompletionEnabled
        tabExtendedHelp.isSelected = s.tabExtendedHelp
        tabRetrySpinner.value = (s.tabRetryDelayMs / 1000).coerceIn(1, 30)
        trackOnOpen.isSelected = s.trackOnProjectOpen
        maxFileBytesSpinner.value = s.maxFileBytes
        maxCheckpointsSpinner.value = s.maxCheckpoints
        excludedDirsField.text = s.excludedDirs
        syncAgentsMd.isSelected = s.syncAgentsMd
        stopDshOnClose.isSelected = s.stopDshOnClose
    }
}
