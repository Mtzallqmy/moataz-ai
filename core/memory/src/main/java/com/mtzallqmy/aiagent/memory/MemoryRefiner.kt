package com.mtzallqmy.aiagent.memory

import com.mtzallqmy.aiagent.database.MemoryEntity

/**
 * Concepts studied from Kai 9000 (Apache-2.0, clean-room reimplementation):
 *  - Memories carry a hitCount; frequently useful memories (>= promotionThreshold)
 *    can be promoted into the agent's permanent system prompt.
 *  - Memories older than retentionMs and never recalled are eligible for pruning.
 *
 * Works over the existing MemoryEntity schema: hitCount is read from a
 * "hits" key inside the metadata JSON, and promoted memories are surfaced
 * separately so they can be appended to the system prompt.
 */
class MemoryRefiner(
    private val promotionThreshold: Int = 5,
    private val retentionMs: Long = 90L * 24 * 3600 * 1000, // 90 days
) {
    /** Hit count is stored as a small JSON object inside metadata. */
    fun hitCount(entity: MemoryEntity): Int =
        runCatching {
            val m = entity.metadata ?: return@runCatching 0
            val start = m.indexOf("\"hits\"")
            if (start < 0) return@runCatching 0
            val colon = m.indexOf(':', start)
            if (colon < 0) return@runCatching 0
            val end = m.indexOf('}', colon)
            if (end < 0) return@runCatching 0
            m.substring(colon + 1, end).trim().toIntOrNull() ?: 0
        }.getOrDefault(0)

    fun setHitCount(entity: MemoryEntity, hits: Int): MemoryEntity {
        val existing = entity.metadata ?: ""
        // metadata is kept as a small flat JSON fragment; replace or append "hits".
        val withHits = if (existing.contains("\"hits\"")) {
            existing.replace(Regex(""""hits"\s*:\s*-?\d+"""), "\"hits\":$hits")
        } else {
            val inner = existing.trim().trimStart('{').trimEnd('}').trim().trimStart(',')
            "{$inner," + """"hits":$hits""" + "}"
        }
        return entity.copy(metadata = withHits)
    }

    /** Memories that crossed the usefulness threshold and should be promoted. */
    fun promotable(entries: List<MemoryEntity>): List<MemoryEntity> =
        entries.filter { hitCount(it) >= promotionThreshold }

    /** Memories eligible for safe pruning (stale + never recalled + unpinned + not expiring sooner). */
    fun pruneable(
        entries: List<MemoryEntity>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<MemoryEntity> = entries.filter { entry ->
        (nowMs - entry.createdAt) > retentionMs &&
            hitCount(entry) == 0 &&
            !entry.pinned &&
            (entry.expiresAt == null || entry.expiresAt ?: Long.MAX_VALUE > nowMs)
    }

    /** Build a compact prompt section from promoted memories. */
    fun buildSystemPromptSection(entries: List<MemoryEntity>): String {
        val promoted = promotable(entries)
        if (promoted.isEmpty()) return ""
        val lines = promoted.sortedByDescending { hitCount(it) }.mapIndexed { i, m ->
            "${i + 1}. ${m.key}: ${m.value}  (recalled ${hitCount(m)}x)"
        }
        return buildString {
            appendLine("# Permanent User Memories (auto-promoted)")
            lines.forEach { appendLine(it) }
        }
    }
}
