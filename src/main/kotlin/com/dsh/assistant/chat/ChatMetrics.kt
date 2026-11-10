package com.dsh.assistant.chat

import com.fasterxml.jackson.databind.JsonNode

/**
 * Session metrics (like in Cursor / Claude Code): tokens, cache, speed, context compactions.
 * Accumulated from SDK events and restored from the session journal.
 */
class ChatMetrics {

    class TurnRecord(
        val time: Long,
        val input: Long,
        val output: Long,
        val reasoning: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val durationMs: Long,
    )

    class Summary(
        val input: Long,
        val output: Long,
        val reasoning: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val turns: Int,
        val durationMs: Long,
        val lastCompactionTime: Long?,
        val compactedTokens: Long,
        val lastInput: Long,
    ) {
        val totalPrompt: Long get() = input + cacheRead
        val total: Long get() = totalPrompt + output
        val cacheHitPercent: Int
            get() = if (totalPrompt == 0L) 0 else (cacheRead * 100 / totalPrompt).toInt()
        val speedTokensPerSec: Double
            get() = if (durationMs <= 0L) 0.0 else output.toDouble() * 1000.0 / durationMs.toDouble()
    }

    private val turns = ArrayList<TurnRecord>()

    @Volatile
    private var curTurnStart = 0L

    @Volatile
    private var curInput = 0L

    @Volatile
    private var curOutput = 0L

    @Volatile
    private var curReasoning = 0L

    @Volatile
    private var curCacheRead = 0L

    @Volatile
    private var curCacheWrite = 0L

    @Volatile
    private var hasCurUsage = false

    @Volatile
    var lastCompactionTime: Long? = null
        private set

    @Volatile
    var compactedTokens: Long = 0
        private set

    /** Restoration from the journal history. */
    fun seed(records: List<TurnRecord>, compactionTime: Long?, compacted: Long) {
        turns.clear()
        turns.addAll(records)
        lastCompactionTime = compactionTime
        compactedTokens = compacted
        curTurnStart = 0
        hasCurUsage = false
    }

    /** Live SDK event. */
    fun onEvent(type: String, event: JsonNode) {
        when (type) {
            "turn/start" -> {
                curTurnStart = event.get("time")?.asLong() ?: System.currentTimeMillis()
                curInput = 0; curOutput = 0; curReasoning = 0; curCacheRead = 0; curCacheWrite = 0
                hasCurUsage = false
            }
            "assistant/message" -> {
                val usage = event.get("data")?.get("usage") ?: return
                val t = event.get("time")?.asLong() ?: System.currentTimeMillis()
                curInput += usage.get("inputTokens")?.asLong() ?: 0
                curOutput += usage.get("outputTokens")?.asLong() ?: 0
                curReasoning += usage.get("reasoningTokens")?.asLong() ?: 0
                curCacheRead += usage.get("cacheReadTokens")?.asLong() ?: 0
                curCacheWrite += usage.get("cacheWriteTokens")?.asLong() ?: 0
                hasCurUsage = true
                if (curTurnStart == 0L) curTurnStart = t
            }
            "turn/end" -> {
                val t = event.get("time")?.asLong() ?: System.currentTimeMillis()
                if (hasCurUsage || curTurnStart != 0L) {
                    turns.add(
                        TurnRecord(
                            time = if (curTurnStart != 0L) curTurnStart else t,
                            input = curInput,
                            output = curOutput,
                            reasoning = curReasoning,
                            cacheRead = curCacheRead,
                            cacheWrite = curCacheWrite,
                            durationMs = (t - curTurnStart).coerceAtLeast(0)))
                }
                curTurnStart = 0
                hasCurUsage = false
            }
            "compaction/summary" -> {
                val t = event.get("time")?.asLong() ?: System.currentTimeMillis()
                lastCompactionTime = t
                compactedTokens += event.get("data")?.get("shadowedTokenCount")?.asLong() ?: 0
            }
            else -> Unit
        }
    }

    fun summary(): Summary {
        var input = 0L
        var output = 0L
        var reasoning = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var duration = 0L
        for (r in turns) {
            input += r.input
            output += r.output
            reasoning += r.reasoning
            cacheRead += r.cacheRead
            cacheWrite += r.cacheWrite
            duration += r.durationMs
        }
        return Summary(
            input = input,
            output = output,
            reasoning = reasoning,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            turns = turns.size,
            durationMs = duration,
            lastCompactionTime = lastCompactionTime,
            compactedTokens = compactedTokens,
            lastInput = turns.lastOrNull()?.let { it.input + it.cacheRead } ?: 0L)
    }

    fun records(): List<TurnRecord> = turns.toList()

    companion object {
        fun fmtTokens(n: Long): String = when {
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
            else -> n.toString()
        }

        fun fmtDuration(ms: Long): String = when {
            ms >= 3_600_000 -> String.format("%.1fh", ms / 3_600_000.0)
            ms >= 60_000 -> String.format("%.1fm", ms / 60_000.0)
            ms >= 1_000 -> String.format("%.1fs", ms / 1_000.0)
            else -> "${ms}ms"
        }
    }
}
