package com.dsh.assistant.settings

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Adds (or removes) a managed block describing the plugin settings in the project AGENTS.md.
 * DSH reads AGENTS.md as project instructions, so the agent learns about the settings file
 * and can change them right in the chat — that is what "settings synchronized through DSH" means.
 */
object AgentsMdSync {

    private const val START = "<!-- DSH-ASSISTANT:START -->"
    private const val END = "<!-- DSH-ASSISTANT:END -->"
    private val BLOCK_REGEX = Regex("(?s)$START.*?$END[\\r\\n]*")

    fun blockText(): String = listOf(
        START,
        "## DSH Assistant (PyCharm plugin)",
        "- Plugin settings live in `.dsh/pycharm-assistant.json` (JSON, applied on the fly).",
        "- To change settings from the chat, edit that file (fields: dshUrl, tokenUrl, autoConnect).",
        "- TAB completion: tabCompletionEnabled, tabExtendedHelp (up to 12,000 tokens), tabRetryDelayMs (retry after Esc).",
        "- The plugin watches project files and shows changes in the \"Changes\" panel:",
        "  accept or reject them one by one or all at once.",
        END
    ).joinToString("\n")

    fun sync(project: Project, enabled: Boolean) {
        val root = project.basePath ?: return
        val f = File(root, "AGENTS.md")
        val text = if (f.isFile) f.readText() else ""
        val hasBlock = BLOCK_REGEX.containsMatchIn(text)

        val newText: String = if (!enabled) {
            if (!hasBlock) return
            text.replace(BLOCK_REGEX, "")
        } else {
            if (hasBlock) {
                text.replace(BLOCK_REGEX, blockText() + "\n")
            } else {
                val base = if (text.isBlank()) "" else text.trimEnd() + "\n"
                base + "\n" + blockText() + "\n"
            }
        }

        // write in the background; VFS is refreshed asynchronously (a synchronous refresh needs
        // a write action and produces TransactionGuard errors when the project is opening)
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            try {
                f.writeText(newText)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    try {
                        com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh { }
                    } catch (ignored: Throwable) {
                        // ignore
                    }
                }
            } catch (ignored: Exception) {
                // no permissions / the file is locked — skip it
            }
        }
    }

    fun syncAllOpenProjects(enabled: Boolean) {
        for (p in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            sync(p, enabled)
        }
    }
}
