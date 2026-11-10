package com.dsh.assistant.actions

import com.dsh.assistant.completion.DshInlineCompletionService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Manual invocation of the TAB suggestion (for example, when auto-suggestions are
 * disabled or the user wants to repeat the request right away without waiting for the Esc pause).
 * No shortcut is assigned by default — it can be set in Settings → Keymap.
 */
class TabCompletionAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        project.getService(DshInlineCompletionService::class.java)?.trigger(editor)
    }
}
