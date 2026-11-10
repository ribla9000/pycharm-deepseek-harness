package com.dsh.assistant.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Creates the "DSH Assistant" panel on the right side of the IDE. */
class DshToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DshToolWindowPanel.attach(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        // On project/panel close, release the JCEF browsers, timers and the DSH process.
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}
