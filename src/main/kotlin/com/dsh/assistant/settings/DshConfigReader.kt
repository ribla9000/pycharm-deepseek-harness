package com.dsh.assistant.settings

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Reads the ORIGINAL DSH settings from the machine so the plugin does not "live its own life":
 *  - provider, model and reasoning effort come from `$DSH_HOME/settings.yaml(.imported)`;
 *  - the provider model catalog comes from the installed `dsh-llm-deepseek` package
 *    (it holds the official model list with human-readable names).
 */
object DshConfigReader {

    data class ModelEntry(val id: String, val name: String)

    data class DshSettings(
        val provider: String?,
        val model: String?,
        val reasoningEffort: String?,
        val models: List<ModelEntry>,
        val settingsFile: File?,
        val hasCredentials: Boolean,
    )

    fun dshHome(): File {
        val env = System.getenv("DSH_HOME")
        if (!env.isNullOrBlank()) return File(env)
        return File(System.getProperty("user.home") ?: ".", ".dsh")
    }

    fun settingsFile(): File? {
        val home = dshHome()
        val candidates = listOf("settings.yaml", "settings.yaml.imported", "settings.yml")
        return candidates.map { File(home, it) }.firstOrNull { it.isFile }
    }

    /** Reads the DSH settings (best-effort: simple key-based YAML parsing). */
    fun read(project: Project, dshCommand: String): DshSettings {
        val file = settingsFile()
        var provider: String? = null
        var model: String? = null
        var effort: String? = null
        val models = LinkedHashMap<String, String>()

        if (file != null) {
            try {
                val lines = file.readLines()
                var inAgentModel = false
                for (raw in lines) {
                    val line = raw.trimEnd()
                    val trimmed = line.trim()
                    if (trimmed.startsWith("agent-default-model:")) {
                        inAgentModel = true
                        continue
                    }
                    if (inAgentModel) {
                        // the block ends at a line with zero indentation (a new root key)
                        if (line.isNotEmpty() && !line.startsWith(" ") && !line.startsWith("-")) {
                            inAgentModel = false
                        } else {
                            when {
                                trimmed.startsWith("provider:") -> provider = value(trimmed)
                                trimmed.startsWith("model:") -> model = value(trimmed)
                                trimmed.startsWith("reasoningEffort:") -> effort = value(trimmed)
                            }
                            continue
                        }
                    }
                    // custom models of third-party providers (llm-pi-ai)
                    if (trimmed.startsWith("- id:")) {
                        val id = value(trimmed)
                        if (id.isNotBlank()) models[id] = id
                    }
                }
            } catch (ignored: Exception) {
                // ignore
            }
        }

        // official model catalog from the installed DSH
        for (m in officialCatalog()) {
            models[m.id] = m.name
        }

        val creds = File(dshHome(), ".credentials.yaml").isFile
        return DshSettings(
            provider = provider,
            model = model,
            reasoningEffort = effort,
            models = models.map { ModelEntry(it.key, it.value) },
            settingsFile = file,
            hasCredentials = creds,
        )
    }

    private fun value(trimmed: String): String =
        trimmed.substringAfter(':').trim().trim('"', '\'')

    /** Looks for dsh-llm-deepseek in the installed DSH and reads DEFAULT_MODELS. */
    private fun officialCatalog(): List<ModelEntry> {
        val roots = ArrayList<File>()
        val home = dshHome()
        roots.add(File(home, "profiles"))
        val npmNpx = File(System.getProperty("user.home") ?: ".", ".npm/_npx")
        if (npmNpx.isDirectory) roots.add(npmNpx)

        val pkgDirs = ArrayList<File>()
        for (root in roots) {
            walk(root, 4, pkgDirs)
            if (pkgDirs.isNotEmpty()) break
        }
        for (dir in pkgDirs) {
            val lib = File(dir, "lib")
            val js = lib.listFiles { f -> f.name.endsWith(".js") } ?: continue
            for (f in js) {
                try {
                    val text = f.readText()
                    if (!text.contains("DEFAULT_MODELS")) continue
                    val found = parseModels(text)
                    if (found.isNotEmpty()) return found
                } catch (ignored: Exception) {
                    // ignore
                }
            }
        }
        return emptyList()
    }

    private fun walk(dir: File, depth: Int, out: MutableList<File>) {
        if (depth < 0 || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) {
                if (c.name == "dsh-llm-deepseek") {
                    out.add(c)
                } else if (out.isEmpty()) {
                    walk(c, depth - 1, out)
                }
            }
        }
    }

    private fun parseModels(text: String): List<ModelEntry> {
        val result = ArrayList<ModelEntry>()
        val re = Regex("id:\\s*\"([^\"]+)\"(?:[\\s\\S]{0,400}?name:\\s*\"([^\"]+)\")?")
        val start = text.indexOf("DEFAULT_MODELS")
        if (start < 0) return result
        val end = text.indexOf("//#endregion", start).let { if (it < 0) minOf(text.length, start + 4000) else it }
        for (m in re.findAll(text.substring(start, end))) {
            val id = m.groupValues[1]
            val name = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: id
            result.add(ModelEntry(id, name))
        }
        return result
    }
}
