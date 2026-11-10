package com.dsh.assistant.sdk

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.airlift.compress.zstd.ZstdInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet

/**
 * DSH session history: sessions live in `$DSH_HOME/sessions/<encoded cwd>/<id>/session.*.jsonl.zstd`.
 * The format is zstd-compressed JSONL (session header + user/message, assistant/message, session/title…).
 */
class DshSessionStore(private val project: Project) {

    companion object {
        /**
         * Technical sessions of TAB completion. They are created for every request,
         * must not appear in the chat list and are deleted by age.
         */
        const val COMPLETION_SESSION_PREFIX = "dsh-tab-"
    }

    data class SessionInfo(
        val id: String,
        val title: String?,
        val createdAt: Long?,
        val file: File,
    )

    data class HistMessage(val role: String, val text: String)

    private val log = Logger.getInstance(DshSessionStore::class.java)
    private val mapper = ObjectMapper()

    fun dshHomeDir(): File {
        val env = System.getenv("DSH_HOME")
        if (!env.isNullOrBlank()) return File(env)
        return File(System.getProperty("user.home") ?: ".", ".dsh")
    }

    fun projectSessionsDir(): File {
        val base = project.basePath ?: return File(dshHomeDir(), "sessions")
        val root = File(dshHomeDir(), "sessions")
        // canonical path (symlinks/case must not lose sessions)
        val canonical = try {
            File(base).canonicalPath
        } catch (e: Exception) {
            base
        }
        val dir = root.resolve(encodeCwd(canonical))
        if (dir.isDirectory) return dir
        // tolerant lookup: a different case encoding or a path without canonicalization
        val wantedEnc = encodeCwd(base).lowercase()
        val wantedCanonical = canonical.lowercase()
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                val n = f.name.lowercase()
                if (n == wantedEnc || n == wantedCanonical || n == encodeCwd(canonical).lowercase()) {
                    return f
                }
            }
        }
        return dir
    }

    /** /home/user/project → --home-user-project-- */
    private fun encodeCwd(path: String): String {
        var p = path.replace('\\', '/')
        while (p.endsWith("/") && p.length > 1) p = p.dropLast(1)
        return "--" + p.replace('/', '-') + "--"
    }

    fun hasSessionLog(sessionId: String): Boolean = sessionLogFile(sessionId) != null

    private fun sessionLogFile(sessionId: String): File? {
        val dir = projectSessionsDir().resolve(sessionId)
        if (!dir.isDirectory) return null
        val files = dir.listFiles { f ->
            f.name.startsWith("session.") && f.name.endsWith(".jsonl.zstd")
        } ?: return null
        return files.firstOrNull()
    }

    /**
     * Title of one session from the journal: we take the LAST session/title event.
     * DSH first writes a draft title (fallback), then the model-generated one — the
     * tab needs the second one. Call in the background.
     */
    fun sessionTitle(sessionId: String): String? {
        val file = sessionLogFile(sessionId) ?: return null
        var title: String? = null
        try {
            openLines(file)?.forEachLine { line ->
                val node = try {
                    mapper.readTree(line)
                } catch (e: Exception) {
                    return@forEachLine
                }
                if (node.get("type")?.asText() == "session/title") {
                    val t = node.get("data")?.get("title")?.asText()
                    if (!t.isNullOrBlank()) title = t
                }
            }
        } catch (e: Exception) {
            log.warn("sessionTitle failed for $sessionId", e)
        }
        return title
    }

    /** Reads titles of all project sessions (id, title, createdAt). Call in the background. */
    fun listSessions(): List<SessionInfo> {
        val result = ArrayList<SessionInfo>()
        val root = projectSessionsDir()
        if (root.isDirectory) {
            val dirs = root.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in dirs) {
                if (dir.name.startsWith(COMPLETION_SESSION_PREFIX)) continue
                val file = sessionLogFile(dir.name) ?: continue
                val info = readHeader(file, dir.name)
                if (info != null) result.add(info)
            }
        }
        if (result.isEmpty()) {
            // fallback: look up sessions by project name across all directories (~/.dsh/sessions)
            val projectName = File(project.basePath ?: return emptyList()).name
            val allRoot = File(dshHomeDir(), "sessions")
            allRoot.listFiles { f -> f.isDirectory }?.forEach { dir ->
                val decoded = decodeCwd(dir.name)
                if (decoded != null && decoded.substringAfterLast('/') == projectName) {
                    dir.listFiles { f -> f.isDirectory }?.forEach { sessionDir ->
                        if (sessionDir.name.startsWith(COMPLETION_SESSION_PREFIX)) return@forEach
                        val file = sessionLogFileIn(dir, sessionDir.name) ?: return@forEach
                        val info = readHeader(file, sessionDir.name)
                        if (info != null) result.add(info)
                    }
                }
            }
        }
        return result.distinctBy { it.id }.sortedByDescending { it.createdAt ?: 0L }
    }

    private fun decodeCwd(name: String): String? =
        if (name.startsWith("--") && name.endsWith("--")) name.substring(2, name.length - 2).replace('-', '/') else null

    private fun sessionLogFileIn(dir: File, sessionId: String): File? {
        val sdir = dir.resolve(sessionId)
        if (!sdir.isDirectory) return null
        val files = sdir.listFiles { f ->
            f.name.startsWith("session.") && f.name.endsWith(".jsonl.zstd")
        } ?: return null
        return files.firstOrNull()
    }

    private fun readHeader(file: File, sessionId: String): SessionInfo? {
        var title: String? = null
        var createdAt: Long? = null
        try {
            openLines(file)?.forEachLine { line ->
                if (title != null && createdAt != null) return@forEachLine
                val node = try {
                    mapper.readTree(line)
                } catch (e: Exception) {
                    return@forEachLine
                }
                when (node.get("type")?.asText()) {
                    "session" -> createdAt = node.get("createdAt")?.asLong()
                    "session/title" -> title = node.get("data")?.get("title")?.asText()
                }
            }
        } catch (e: Exception) {
            log.warn("readHeader failed for $sessionId", e)
        }
        if (createdAt == null) {
            // the title may be missing after a hard shutdown — fall back to the file mtime
            createdAt = file.lastModified()
        }
        return SessionInfo(sessionId, title, createdAt, file)
    }

    /** Reads the message history (user/assistant, text only). Call in the background. */
    fun readHistory(sessionId: String, maxMessages: Int = 400): List<HistMessage> {
        // 1) the plugin's SQLite journal (newer dsh versions do not write events to jsonl)
        val db = com.dsh.assistant.db.DshDatabase.getInstance(project)
        if (db.eventCount(sessionId) > 0) {
            val result = ArrayList<HistMessage>()
            for ((type, payload) in db.eventsOf(sessionId)) {
                if (result.size >= maxMessages) break
                val node = try {
                    mapper.readTree(payload)
                } catch (e: Exception) {
                    continue
                }
                when (type) {
                    "user/message" -> {
                        val data = node.get("data") ?: continue
                        val kind = data.get("source")?.get("kind")?.asText()
                        if (kind != "user") continue
                        val text = textOf(data.get("content"))
                        if (text.isNotBlank()) result.add(HistMessage("user", text))
                    }
                    "assistant/message" -> {
                        val content = node.get("data")?.get("message")?.get("content") ?: continue
                        val text = textOf(content)
                        if (text.isNotBlank()) result.add(HistMessage("assistant", text))
                    }
                }
            }
            if (result.isNotEmpty()) return result
        }
        // 2) the jsonl journal (old dsh sessions)
        val file = sessionLogFile(sessionId) ?: return emptyList()
        val result = ArrayList<HistMessage>()
        try {
            var lines = 0
            openLines(file)?.forEachLine { line ->
                if (result.size >= maxMessages || lines++ > 100_000) return@forEachLine
                val node = try {
                    mapper.readTree(line)
                } catch (e: Exception) {
                    return@forEachLine
                }
                when (node.get("type")?.asText()) {
                    "user/message" -> {
                        val data = node.get("data") ?: return@forEachLine
                        val kind = data.get("source")?.get("kind")?.asText()
                        if (kind != "user") return@forEachLine
                        val text = textOf(data.get("content"))
                        if (text.isNotBlank()) result.add(HistMessage("user", text))
                    }
                    "assistant/message" -> {
                        val content = node.get("data")?.get("message")?.get("content") ?: return@forEachLine
                        val text = textOf(content)
                        if (text.isNotBlank()) result.add(HistMessage("assistant", text))
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("readHistory failed for $sessionId", e)
        }
        return result
    }

    private fun metricsFromRows(rows: List<Pair<String, String>>): Triple<List<com.dsh.assistant.chat.ChatMetrics.TurnRecord>, Long?, Long> {
        val records = ArrayList<com.dsh.assistant.chat.ChatMetrics.TurnRecord>()
        var curStart = 0L
        var input = 0L; var output = 0L; var reasoning = 0L; var cacheRead = 0L; var cacheWrite = 0L
        var hasUsage = false
        var lastCompaction: Long? = null
        var compacted = 0L
        for ((type, payload) in rows) {
            val node = try {
                mapper.readTree(payload)
            } catch (e: Exception) {
                continue
            }
            val time = node.get("time")?.asLong() ?: 0L
            when (type) {
                "turn/start" -> {
                    curStart = time
                    input = 0; output = 0; reasoning = 0; cacheRead = 0; cacheWrite = 0
                    hasUsage = false
                }
                "assistant/message" -> {
                    val usage = node.get("data")?.get("usage") ?: continue
                    input += usage.get("inputTokens")?.asLong() ?: 0
                    output += usage.get("outputTokens")?.asLong() ?: 0
                    reasoning += usage.get("reasoningTokens")?.asLong() ?: 0
                    cacheRead += usage.get("cacheReadTokens")?.asLong() ?: 0
                    cacheWrite += usage.get("cacheWriteTokens")?.asLong() ?: 0
                    hasUsage = true
                }
                "turn/end" -> {
                    if (hasUsage) {
                        records.add(
                            com.dsh.assistant.chat.ChatMetrics.TurnRecord(
                                time = curStart,
                                input = input,
                                output = output,
                                reasoning = reasoning,
                                cacheRead = cacheRead,
                                cacheWrite = cacheWrite,
                                durationMs = (time - curStart).coerceAtLeast(0)))
                    }
                    hasUsage = false
                }
                "compaction/summary" -> {
                    lastCompaction = time
                    compacted += node.get("data")?.get("shadowedTokenCount")?.asLong() ?: 0
                }
            }
        }
        return Triple(records, lastCompaction, compacted)
    }

    /** History along the session chain (in order): SQLite events, jsonl as a fallback. */
    fun readChainHistory(ids: List<String>, maxMessages: Int = 400): List<HistMessage> {
        val result = ArrayList<HistMessage>()
        for (id in ids) {
            if (result.size >= maxMessages) break
            result.addAll(readHistory(id, maxMessages - result.size))
        }
        return result
    }

    /** Metrics along the session chain: consecutive turn records + the last context reset. */
    fun readChainMetrics(ids: List<String>): Triple<List<com.dsh.assistant.chat.ChatMetrics.TurnRecord>, Long?, Long> {
        val records = ArrayList<com.dsh.assistant.chat.ChatMetrics.TurnRecord>()
        var lastCompaction: Long? = null
        var compacted = 0L
        for (id in ids) {
            val (r, lc, cp) = readMetrics(id)
            records.addAll(r)
            if (lc != null) lastCompaction = lc
            compacted += cp
        }
        return Triple(records, lastCompaction, compacted)
    }

    private fun textOf(content: JsonNode?): String {
        if (content == null || !content.isArray) return ""
        val sb = StringBuilder()
        for (b in content) {
            if (b.get("type")?.asText() == "text") {
                sb.append(b.get("text")?.asText("") ?: "")
            }
        }
        return sb.toString().trim()
    }

    /** Reads session metrics from the journal: records per turn + context resets. Call in the background. */
    fun readMetrics(sessionId: String): Triple<List<com.dsh.assistant.chat.ChatMetrics.TurnRecord>, Long?, Long> {
        val db = com.dsh.assistant.db.DshDatabase.getInstance(project)
        if (db.eventCount(sessionId) > 0) {
            val fromDb = metricsFromRows(db.eventsOf(sessionId))
            if (fromDb.first.isNotEmpty() || fromDb.second != null || fromDb.third > 0) return fromDb
        }
        val file = sessionLogFile(sessionId) ?: return Triple(emptyList(), null, 0L)
        val records = ArrayList<com.dsh.assistant.chat.ChatMetrics.TurnRecord>()
        var curStart = 0L
        var input = 0L
        var output = 0L
        var reasoning = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var hasUsage = false
        var lastCompaction: Long? = null
        var compacted = 0L
        try {
            openLines(file)?.forEachLine { line ->
                val node = try {
                    mapper.readTree(line)
                } catch (e: Exception) {
                    return@forEachLine
                }
                val time = node.get("time")?.asLong() ?: 0L
                when (node.get("type")?.asText()) {
                    "turn/start" -> {
                        curStart = time
                        input = 0; output = 0; reasoning = 0; cacheRead = 0; cacheWrite = 0
                        hasUsage = false
                    }
                    "assistant/message" -> {
                        val usage = node.get("data")?.get("usage") ?: return@forEachLine
                        input += usage.get("inputTokens")?.asLong() ?: 0
                        output += usage.get("outputTokens")?.asLong() ?: 0
                        reasoning += usage.get("reasoningTokens")?.asLong() ?: 0
                        cacheRead += usage.get("cacheReadTokens")?.asLong() ?: 0
                        cacheWrite += usage.get("cacheWriteTokens")?.asLong() ?: 0
                        hasUsage = true
                    }
                    "turn/end" -> {
                        if (hasUsage) {
                            records.add(
                                com.dsh.assistant.chat.ChatMetrics.TurnRecord(
                                    time = curStart,
                                    input = input,
                                    output = output,
                                    reasoning = reasoning,
                                    cacheRead = cacheRead,
                                    cacheWrite = cacheWrite,
                                    durationMs = (time - curStart).coerceAtLeast(0)))
                        }
                        curStart = 0
                        hasUsage = false
                    }
                    "compaction/summary" -> {
                        lastCompaction = time
                        compacted += node.get("data")?.get("shadowedTokenCount")?.asLong() ?: 0
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("readMetrics failed for $sessionId", e)
        }
        return Triple(records, lastCompaction, compacted)
    }

    /** Journal session status: "error" — last turn failed, "answered" — has a reply, "new" — empty. */
    fun readSessionState(sessionId: String): String {
        val db = com.dsh.assistant.db.DshDatabase.getInstance(project)
        if (db.eventCount(sessionId) > 0) {
            var anyAssistant = false
            var lastTurnError = false
            var lastTurnSeen = false
            for ((type, payload) in db.eventsOf(sessionId)) {
                val node = try {
                    mapper.readTree(payload)
                } catch (e: Exception) {
                    continue
                }
                when (type) {
                    "assistant/message" -> {
                        val content = node.get("data")?.get("message")?.get("content")
                        if (content != null && content.isArray) {
                            for (b in content) {
                                if (b.get("type")?.asText() == "text" && (b.get("text")?.asText() ?: "").isNotBlank()) {
                                    anyAssistant = true
                                }
                            }
                        }
                    }
                    "turn/end" -> {
                        lastTurnSeen = true
                        lastTurnError = node.get("data")?.get("reason")?.get("kind")?.asText() == "error"
                    }
                }
            }
            return when {
                lastTurnSeen && lastTurnError -> "error"
                anyAssistant -> "answered"
                else -> "new"
            }
        }
        val file = sessionLogFile(sessionId) ?: return "new"
        var anyAssistant = false
        var lastTurnError = false
        var lastTurnSeen = false
        try {
            openLines(file)?.forEachLine { line ->
                val node = try {
                    mapper.readTree(line)
                } catch (e: Exception) {
                    return@forEachLine
                }
                when (node.get("type")?.asText()) {
                    "assistant/message" -> {
                        val content = node.get("data")?.get("message")?.get("content")
                        if (content != null && content.isArray) {
                            for (b in content) {
                                if (b.get("type")?.asText() == "text" && (b.get("text")?.asText() ?: "").isNotBlank()) {
                                    anyAssistant = true
                                }
                            }
                        }
                    }
                    "turn/end" -> {
                        lastTurnSeen = true
                        lastTurnError = node.get("data")?.get("reason")?.get("kind")?.asText() == "error"
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("readSessionState failed for $sessionId", e)
        }
        return when {
            lastTurnSeen && lastTurnError -> "error"
            anyAssistant -> "answered"
            else -> "new"
        }
    }

    /**
     * Deletes the session journal. We look for the directory not only in the "main"
     * project directory: the session may have lived in a neighboring one (a different
     * path encoding) that [projectSessionsDir] finds only when reading — otherwise
     * deletion from history did not work and the chat came back to the list.
     */
    fun deleteSession(sessionId: String): Boolean {
        val dirs = LinkedHashSet<File>()
        val direct = projectSessionsDir().resolve(sessionId)
        if (direct.isDirectory) dirs.add(direct)
        val allRoot = File(dshHomeDir(), "sessions")
        allRoot.listFiles()?.forEach { projectDir ->
            if (!projectDir.isDirectory) return@forEach
            sessionLogFileIn(projectDir, sessionId)?.parentFile?.let { dirs.add(it) }
        }
        var deleted = false
        for (dir in dirs) {
            try {
                if (dir.deleteRecursively()) deleted = true
            } catch (ignored: Exception) {
                // the journal may be locked — do not fail deletion of the other sessions
            }
        }
        return deleted
    }

    /**
     * Deletes old TAB completion journals: a separate technical session is created
     * for every suggestion, and there is no point in keeping them. Call in the background.
     */
    fun pruneCompletionSessions(maxAgeMs: Long = 24L * 60 * 60 * 1000): Int {
        val root = projectSessionsDir()
        if (!root.isDirectory) return 0
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        for (dir in root.listFiles() ?: return 0) {
            if (!dir.isDirectory || !dir.name.startsWith(COMPLETION_SESSION_PREFIX)) continue
            try {
                if (dir.lastModified() < cutoff && dir.deleteRecursively()) removed++
            } catch (ignored: Exception) {
                // the journal may be locked — try again next time
            }
        }
        return removed
    }

    private fun openLines(file: File): BufferedReader? {
        return try {
            BufferedReader(InputStreamReader(ZstdInputStream(file.inputStream()), StandardCharsets.UTF_8))
        } catch (e: Exception) {
            log.warn("cannot open session log $file", e)
            null
        }
    }
}
