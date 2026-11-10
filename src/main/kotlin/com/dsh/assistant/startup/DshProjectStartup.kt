package com.dsh.assistant.startup

import com.dsh.assistant.changes.ChangeTracker
import com.dsh.assistant.settings.AgentsMdSync
import com.dsh.assistant.settings.DshAppSettings
import com.dsh.assistant.settings.DshProjectSettings
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Initializes the plugin services as soon as the project opens:
 * the baseline file snapshot must be taken BEFORE the DSH agent starts editing files.
 */
class DshProjectStartup : StartupActivity, DumbAware {

    override fun runActivity(project: Project) {
        try {
            // Creates .dsh/pycharm-assistant.json and starts watching it
            project.getService(DshProjectSettings::class.java)
            // Takes the baseline and subscribes to VFS events
            project.getService(ChangeTracker::class.java)
            // File watcher: polls the project every 300 ms so the agent's edits
            // land in "Changes"/"History" without VFS delays
            com.dsh.assistant.changes.ChangesWatchdog.getInstance(project).start()
            // TAB completion: cleans up old technical sessions, the process starts lazily
            project.getService(com.dsh.assistant.completion.DshInlineCompletionService::class.java)
            // Synchronizes the settings block in AGENTS.md (if enabled)
            AgentsMdSync.sync(project, DshAppSettings.getInstance().appState.syncAgentsMd)
        } catch (ignored: Exception) {
            // startup must not break opening the project
        }
    }
}
