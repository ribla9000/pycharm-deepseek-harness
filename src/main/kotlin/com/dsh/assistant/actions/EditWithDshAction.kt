package com.dsh.assistant.actions

import com.dsh.assistant.toolwindow.DshToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

/**
 * Cursor Ctrl+K: edit the selected code (or the current file)
 * using an instruction in natural language. The DSH agent makes the changes,
 * and they show up in the "Changes" panel for accept/reject.
 */
class EditWithDshAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = AskDshAction.obtainPanel(project) ?: return
        val instruction = Messages.showInputDialog(
            project,
            "What should be done with the selected code (or the current file)?\n" +
                "For example: \"extract it into a separate function and add a docstring\".",
            "DSH: Edit Code",
            Messages.getQuestionIcon())
        if (instruction.isNullOrBlank()) return
        panel.editSelection(instruction)
    }
}
