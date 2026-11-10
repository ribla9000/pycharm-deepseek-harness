package com.dsh.assistant.chat

import com.dsh.assistant.db.DshDatabase
import com.dsh.assistant.sdk.DshSessionStore
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Single entry point for restoring chat titles.
 *
 * DSH generates the session title and sends it in a `session/title` event; the
 * plugin remembers it. Previously the tab title lived only in [ChatState] memory, so
 * after an IDE restart (and when opening a chat from history) the tab showed
 * "New session". Every source a title can be recovered from is collected here:
 * user-set name → chats table → session cache → `session/title` events across
 * the whole chain of chat sessions.
 */
object ChatTitles {

    /** Placeholder for a chat that does not have a real title yet. */
    const val DEFAULT: String = "New session"

    /** Placeholders that different plugin versions used for an unnamed chat. */
    private val PLACEHOLDERS = setOf(DEFAULT, "Новая сессия")

    private val mapper = ObjectMapper()

    /** The title, or null for an empty string or the "New session" placeholder. */
    fun real(title: String?): String? {
        val v = title?.trim().orEmpty()
        return if (v.isEmpty() || v in PLACEHOLDERS) null else v
    }

    /**
     * Chat title from SQLite: the chats table, then the session cache and the
     * `session/title` event journal along the session chain (after id rotations the
     * title may have settled in a child session). Returns null when there is no real title.
     */
    fun resolve(db: DshDatabase, chatId: String, chain: List<String>): String? {
        val ids = (listOf(chatId) + chain).distinct()
        // 1) the tab title that the plugin has already shown to the user
        real(db.chatTitle(chatId))?.let { return it }
        // 2) the session/title event journal: the chain goes from old to new,
        //    so the last value found is the current one
        var best: String? = null
        for (id in ids) {
            real(titleOfEvent(db.lastTitleEvent(id)))?.let { best = it }
        }
        if (best != null) return best
        // 3) the session cache (the title is written along with events, but may be incomplete)
        for (id in ids) {
            real(db.sessionTitle(id))?.let { best = it }
        }
        return best
    }

    /** Session title from the DSH jsonl journal (external sessions that are not in SQLite). */
    fun resolve(store: DshSessionStore, sessionId: String): String? =
        real(store.sessionTitle(sessionId))

    private fun titleOfEvent(payload: String?): String? {
        if (payload.isNullOrBlank()) return null
        return try {
            mapper.readTree(payload).get("data")?.get("title")?.asText()
        } catch (e: Exception) {
            null
        }
    }
}
