package com.mtzallqmy.aiagent.tools

import android.content.Context
import com.mtzallqmy.aiagent.model.RiskLevel
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Stable rule identity. Request IDs are deliberately excluded. */
data class ApprovalRuleKey(
    val toolId: String,
    val action: String,
    val target: String,
    val risk: RiskLevel,
    val agentScope: String,
)

enum class ApprovalRuleEffect { ALLOW, DENY }

data class ApprovalRule(val key: ApprovalRuleKey, val effect: ApprovalRuleEffect)

interface ApprovalRuleStore {
    fun load(): Set<ApprovalRule>
    fun replace(rules: Set<ApprovalRule>)
}

class InMemoryApprovalRuleStore(initialRules: Set<ApprovalRule> = emptySet()) : ApprovalRuleStore {
    private var rules = initialRules.toSet()

    @Synchronized
    override fun load(): Set<ApprovalRule> = rules.toSet()

    @Synchronized
    override fun replace(rules: Set<ApprovalRule>) {
        this.rules = rules.toSet()
    }
}

/** Durable Android store containing only stable rule fields. */
class SharedPreferencesApprovalRuleStore(context: Context) : ApprovalRuleStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun load(): Set<ApprovalRule> =
        preferences.getStringSet(RULES_KEY, emptySet()).orEmpty().map(::decode).toSet()

    override fun replace(rules: Set<ApprovalRule>) {
        check(preferences.edit().putStringSet(RULES_KEY, rules.map(::encode).toSet()).commit()) {
            "Failed to persist approval rules"
        }
    }

    private fun encode(rule: ApprovalRule): String = listOf(
        rule.effect.name,
        rule.key.risk.name,
        encodePart(rule.key.toolId),
        encodePart(rule.key.action),
        encodePart(rule.key.target),
        encodePart(rule.key.agentScope),
    ).joinToString(SEPARATOR)

    private fun decode(value: String): ApprovalRule = runCatching {
        val parts = value.split(SEPARATOR)
        require(parts.size == 6)
        ApprovalRule(
            key = ApprovalRuleKey(
                toolId = decodePart(parts[2]),
                action = decodePart(parts[3]),
                target = decodePart(parts[4]),
                risk = RiskLevel.valueOf(parts[1]),
                agentScope = decodePart(parts[5]),
            ),
            effect = ApprovalRuleEffect.valueOf(parts[0]),
        )
    }.getOrElse { throw IllegalStateException("Corrupt persisted approval rule", it) }

    private fun encodePart(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodePart(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    private companion object {
        const val PREFERENCES_NAME = "aegis_approval_rules"
        const val RULES_KEY = "rules_v1"
        const val SEPARATOR = "|"
    }
}
