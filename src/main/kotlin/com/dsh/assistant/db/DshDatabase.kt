package com.dsh.assistant.db

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.sql.Connection

/**
 * Local SQLite database of the plugin: .dsh/assistant.db (inside the project).
 * Stores chats (tab metadata) and the DSH session cache — fast, reliable and
 * independent of IDE settings and the JSON file.
 */
@Service(Service.Level.PROJECT)
class DshDatabase(private val project: Project) : Disposable {

    companion object {
        fun getInstance(project: Project): DshDatabase = project.service<DshDatabase>()
        private val LOG = logger<DshDatabase>()
    }

    data class ChatRow(
        val id: String,
        val title: String?,
        val archived: Boolean,
        val state: String?,
        val openedAt: Long,
        val open: Boolean,
    )

    data class SessionRow(
        val id: String,
        val title: String?,
        val createdAt: Long?,
        val state: String?,
    )

    private val conn: Connection?
    val isAvailable: Boolean
        get() = conn != null

    init {
        var c: Connection? = null
        try {
            val f = File(project.basePath ?: "", ".dsh/assistant.db")
            f.parentFile?.mkdirs()
            // talk to the driver directly: DriverManager/ServiceLoader does not always see
            // the driver in the IntelliJ plugin classloader ("No suitable driver")
            val driver = org.sqlite.JDBC()
            c = driver.connect("jdbc:sqlite:${f.absolutePath}", java.util.Properties())
            c?.createStatement()?.use { st ->
                st.execute(
                    "CREATE TABLE IF NOT EXISTS chats (" +
                        "id TEXT PRIMARY KEY, title TEXT, archived INTEGER DEFAULT 0, " +
                        "state TEXT, opened_at INTEGER DEFAULT 0, open INTEGER DEFAULT 0)")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS sessions (" +
                        "id TEXT PRIMARY KEY, title TEXT, created_at INTEGER, state TEXT)")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS events (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, " +
                        "type TEXT, payload TEXT)")
                st.execute("CREATE INDEX IF NOT EXISTS idx_events_session ON events(session_id, id)")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS file_versions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT, " +
                        "before TEXT, after TEXT, added INTEGER, deleted INTEGER, " +
                        "created_at INTEGER, applied INTEGER DEFAULT 0)")
            }
            LOG.info("SQLite database opened: " + f.absolutePath)
        } catch (t: Throwable) {
            LOG.warn("Could not open the SQLite database (.dsh/assistant.db)", t)
            try {
                c?.close()
            } catch (ignored: Exception) {
                // ignore
            }
            c = null
        }
        conn = c
    }

    private inline fun <T> run(body: (Connection) -> T): T? {
        val c = conn ?: return null
        return try {
            body(c)
        } catch (e: Exception) {
            LOG.warn("SQLite operation failed", e)
            null
        }
    }

    // ---------- chats ----------

    fun upsertChat(id: String, title: String?, archived: Boolean = false, state: String? = null, open: Boolean = true) {
        run { c ->
            c.prepareStatement(
                "INSERT INTO chats(id, title, archived, state, opened_at, open) VALUES(?,?,?,?,?,?) " +
                    // the title is never overwritten with an empty value: a chat without a title
                    // (for example, on first open) must not lose the saved name
                    "ON CONFLICT(id) DO UPDATE SET title=COALESCE(excluded.title, chats.title), " +
                    "archived=excluded.archived, " +
                    "state=excluded.state, opened_at=excluded.opened_at, open=excluded.open").use { ps ->
                ps.setString(1, id)
                ps.setString(2, title)
                ps.setInt(3, if (archived) 1 else 0)
                ps.setString(4, state)
                ps.setLong(5, System.currentTimeMillis())
                ps.setInt(6, if (open) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    fun setChatState(id: String, state: String?) {
        run { c ->
            c.prepareStatement("UPDATE chats SET state=? WHERE id=?").use { ps ->
                ps.setString(1, state)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    fun setChatArchived(id: String, archived: Boolean) {
        run { c ->
            c.prepareStatement("UPDATE chats SET archived=? WHERE id=?").use { ps ->
                ps.setInt(1, if (archived) 1 else 0)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    fun deleteChat(id: String) {
        run { c ->
            c.prepareStatement("DELETE FROM chats WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    fun chats(): List<ChatRow> {
        return run { c ->
            val out = ArrayList<ChatRow>()
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, title, archived, state, opened_at, open FROM chats ORDER BY opened_at DESC").use { rs ->
                    while (rs.next()) {
                        out.add(
                            ChatRow(
                                id = rs.getString(1),
                                title = rs.getString(2),
                                archived = rs.getInt(3) != 0,
                                state = rs.getString(4),
                                openedAt = rs.getLong(5),
                                open = rs.getInt(6) != 0))
                    }
                }
            }
            out
        } ?: emptyList()
    }

    // ---------- sessions (cache for the list) ----------

    fun upsertSession(id: String, title: String?, createdAt: Long?, state: String?) {
        run { c ->
            c.prepareStatement(
                "INSERT INTO sessions(id, title, created_at, state) VALUES(?,?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET title=excluded.title, created_at=excluded.created_at, state=excluded.state"
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, title)
                ps.setObject(3, createdAt)
                ps.setString(4, state)
                ps.executeUpdate()
            }
        }
    }

    fun setSessionState(id: String, state: String?) {
        run { c ->
            c.prepareStatement("UPDATE sessions SET state=? WHERE id=?").use { ps ->
                ps.setString(1, state)
                ps.setString(2, id)
                ps.executeUpdate()
            }
        }
    }

    /** Updates only the session title without overwriting created_at/state (unlike [upsertSession]). */
    fun setSessionTitle(id: String, title: String?) {
        run { c ->
            c.prepareStatement(
                "INSERT INTO sessions(id, title, created_at, state) VALUES(?,?,NULL,NULL) " +
                    "ON CONFLICT(id) DO UPDATE SET title=excluded.title"
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, title)
                ps.executeUpdate()
            }
        }
    }

    /** Chat title saved by the plugin (the chats table), or null. */
    fun chatTitle(id: String): String? {
        return run { c ->
            c.prepareStatement("SELECT title FROM chats WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    /** Session title from the cache (the sessions table), or null. */
    fun sessionTitle(id: String): String? {
        return run { c ->
            c.prepareStatement("SELECT title FROM sessions WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    /** Payload of the last session/title event of this session, or null. */
    fun lastTitleEvent(id: String): String? {
        return run { c ->
            c.prepareStatement(
                "SELECT payload FROM events WHERE session_id=? AND type='session/title' " +
                    "ORDER BY id DESC LIMIT 1"
            ).use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
    }

    fun sessions(): List<SessionRow> {
        return run { c ->
            val out = ArrayList<SessionRow>()
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, title, created_at, state FROM sessions").use { rs ->
                    while (rs.next()) {
                        out.add(
                            SessionRow(
                                id = rs.getString(1),
                                title = rs.getString(2),
                                createdAt = rs.getObject(3) as? Long,
                                state = rs.getString(4)))
                    }
                }
            }
            out
        } ?: emptyList()
    }

    // ---------- session event journal (the DSH SDK does not write jsonl — we keep our own) ----------

    fun appendEvent(sessionId: String, type: String, payload: String) {
        run { c ->
            c.prepareStatement("INSERT INTO events(session_id, type, payload) VALUES(?,?,?)").use { ps ->
                ps.setString(1, sessionId)
                ps.setString(2, type)
                ps.setString(3, payload)
                ps.executeUpdate()
            }
        }
    }

    /** (type, payload) pairs in write order. */
    fun eventsOf(sessionId: String): List<Pair<String, String>> {
        return run { c ->
            val out = ArrayList<Pair<String, String>>()
            c.prepareStatement("SELECT type, payload FROM events WHERE session_id=? ORDER BY id").use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) out.add(rs.getString(1) to rs.getString(2))
                }
            }
            out
        } ?: emptyList()
    }

    fun eventCount(sessionId: String): Int {
        return run { c ->
            c.prepareStatement("SELECT COUNT(*) FROM events WHERE session_id=?").use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        } ?: 0
    }

    fun deleteEvents(sessionId: String) {
        run { c ->
            c.prepareStatement("DELETE FROM events WHERE session_id=?").use { ps ->
                ps.setString(1, sessionId)
                ps.executeUpdate()
            }
        }
    }

    /** Removes the session from the list cache (on permanent chat deletion). */
    fun deleteSession(id: String) {
        run { c ->
            c.prepareStatement("DELETE FROM sessions WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
        }
    }

    data class FileVersion(
        val id: Long,
        val before: String,
        val after: String,
        val added: Int,
        val deleted: Int,
        val createdAt: Long,
        val applied: Boolean,
    )

    /** Records the next version of a file change (git-like history). */
    fun recordFileVersion(path: String, before: String, after: String, added: Int, deleted: Int) {
        run { c ->
            c.prepareStatement(
                "INSERT INTO file_versions(path, before, after, added, deleted, created_at, applied) " +
                    "VALUES(?,?,?,?,?,?,0)").use { ps ->
                ps.setString(1, path)
                ps.setString(2, before)
                ps.setString(3, after)
                ps.setInt(4, added)
                ps.setInt(5, deleted)
                ps.setLong(6, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    fun fileVersions(path: String): List<FileVersion> {
        return run { c ->
            val out = ArrayList<FileVersion>()
            c.prepareStatement(
                "SELECT id, before, after, added, deleted, created_at, applied " +
                    "FROM file_versions WHERE path=? ORDER BY id").use { ps ->
                ps.setString(1, path)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(
                            FileVersion(
                                rs.getLong(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4), rs.getInt(5), rs.getLong(6), rs.getInt(7) != 0))
                    }
                }
            }
            out
        } ?: emptyList()
    }

    fun fileVersion(path: String, id: Long): FileVersion? =
        fileVersions(path).firstOrNull { it.id == id }

    fun deleteFileVersions(path: String) {
        run { c ->
            c.prepareStatement("DELETE FROM file_versions WHERE path=?").use { ps ->
                ps.setString(1, path)
                ps.executeUpdate()
            }
        }
    }

    override fun dispose() {
        try {
            conn?.close()
        } catch (ignored: Exception) {
            // ignore
        }
    }
}
