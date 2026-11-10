package com.dsh.assistant.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Project plugin settings. They edit the .dsh/pycharm-assistant.json file —
 * the same file DSH reads and changes (settings synchronization through DSH).
 */
class DshProjectConfigurable(private val project: Project) : Configurable {

    private val settings = project.getService(DshProjectSettings::class.java)

    private val dshUrlField = JBTextField()
    private val tokenUrlField = JBTextField()
    private val autoConnect = JBCheckBox("Connect automatically when the window opens")
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "DSH Assistant"

    override fun createComponent(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("DSH URL:", dshUrlField)
            .addLabeledComponent("URL from `dsh web` (with ?token=...):", tokenUrlField)
            .addComponent(autoConnect)
            .addVerticalGap(10)
            .addComponent(
                JBLabel(
                    "<html>Settings file: <b>.dsh/pycharm-assistant.json</b> in the project root.<br>" +
                        "Changes are applied on the fly. The DSH agent can read and change<br>" +
                        "the settings right in the chat — that is the synchronization through DSH.<br>" +
                        "The file can be committed to git so the settings travel with the project.</html>"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
        form.border = JBUI.Borders.empty(10)
        panel = form
        reset()
        return form
    }

    override fun isModified(): Boolean {
        val s = settings.settings
        return dshUrlField.text.trim() != s.dshUrl ||
            tokenUrlField.text.trim() != s.tokenUrl ||
            autoConnect.isSelected != s.autoConnect
    }

    override fun apply() {
        val s = settings.settings
        s.dshUrl = dshUrlField.text.trim().ifBlank { "http://127.0.0.1:3080/" }
        s.tokenUrl = tokenUrlField.text.trim()
        s.autoConnect = autoConnect.isSelected
        settings.save()
    }

    override fun reset() {
        val s = settings.settings
        dshUrlField.text = s.dshUrl
        tokenUrlField.text = s.tokenUrl
        autoConnect.isSelected = s.autoConnect
    }
}
