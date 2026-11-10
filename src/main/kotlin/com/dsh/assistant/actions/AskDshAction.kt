package com.dsh.assistant.actions

import com.dsh.assistant.toolwindow.DshToolWindowPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Cursor Ctrl+L: ask DSH about the selected code (or the open file).
 * Opens the panel, adds the context to the current chat and focuses the input field.
 */
class AskDshAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = obtainPanel(project) ?: return
        panel.askAboutSelection()
    }

    companion object {
        fun obtainPanel(project: com.intellij.openapi.project.Project): DshToolWindowPanel? {
            DshToolWindowPanel.getInstance(project)?.let { return it }
            val tw = ToolWindowManager.getInstance(project).getToolWindow("DSH Assistant")
            if (tw != null) {
                tw.show { }
                DshToolWindowPanel.getInstance(project)?.let { return it }
            }
            // the window may not have created its content yet — one retry
            ApplicationManager.getApplication().invokeLater {
                DshToolWindowPanel.getInstance(project)?.let { return@invokeLater }
                val tw2 = ToolWindowManager.getInstance(project).getToolWindow("DSH Assistant") ?: return@invokeLater
                tw2.show { }
            }
            return DshToolWindowPanel.getInstance(project)
        }
    }
}
