package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.model.ApprovalDecision
import com.mtzallqmy.aiagent.model.ApprovalOption
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.ApprovalRequest
import com.mtzallqmy.aiagent.model.RiskLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** Single human-approval coordinator with stable, persisted matching rules. */
class ApprovalEngine(
    private val ruleStore: ApprovalRuleStore = InMemoryApprovalRuleStore(),
    private val policyProvider: (RiskLevel) -> ApprovalPolicy = { ApprovalPolicy.ASK_EVERY_TIME },
) {
    private data class PendingApproval(
        val request: ApprovalRequest,
        val ruleKey: ApprovalRuleKey,
        val deferred: CompletableDeferred<ApprovalDecision>,
    )

    private val lock = Any()
    private val perRiskAllowed = mutableSetOf<RiskLevel>()
    private val perRunAllowed = mutableMapOf<String, MutableSet<ApprovalRuleKey>>()
    private val persistentRules = ruleStore.load().toMutableSet()
    private val pending = mutableMapOf<String, PendingApproval>()
    private val requestHistory = object : LinkedHashMap<String, ApprovalRequest>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ApprovalRequest>?): Boolean =
            size > MAX_REQUEST_HISTORY
    }
    private val _requests = Channel<ApprovalRequest>(Channel.UNLIMITED)

    val requests get() = _requests
    val pendingCount: Int get() = synchronized(lock) { pending.size }

    fun decide(request: ApprovalRequest): ApprovalDecision {
        val ruleKey = ruleKey(request)
        val option = synchronized(lock) {
            remember(request)
            val policy = policyProvider(request.riskLevel)
            when {
                persistentRules.contains(ApprovalRule(ruleKey, ApprovalRuleEffect.DENY)) -> ApprovalOption.DENY
                persistentRules.contains(ApprovalRule(ruleKey, ApprovalRuleEffect.ALLOW)) -> ApprovalOption.ALWAYS_ALLOW
                request.runId.isNotBlank() && perRunAllowed[request.runId]?.contains(ruleKey) == true ->
                    ApprovalOption.ALLOW_FOR_TASK
                perRiskAllowed.contains(request.riskLevel) -> ApprovalOption.ALLOW_ONCE
                policy == ApprovalPolicy.ALLOW -> ApprovalOption.ALLOW_ONCE
                policy == ApprovalPolicy.DENY -> ApprovalOption.DENY
                else -> ApprovalOption.ASK
            }
        }
        return ApprovalDecision(request.id, option)
    }

    suspend fun requestApproval(request: ApprovalRequest): ApprovalDecision {
        var isNewRequest = false
        val approval = synchronized(lock) {
            remember(request)
            pending[request.id] ?: PendingApproval(
                request = request,
                ruleKey = ruleKey(request),
                deferred = CompletableDeferred(),
            ).also {
                pending[request.id] = it
                isNewRequest = true
            }
        }
        return try {
            if (isNewRequest) _requests.send(request)
            approval.deferred.await()
        } finally {
            synchronized(lock) {
                if (pending[request.id] === approval) pending.remove(request.id)
            }
        }
    }

    /** Resolves the original request by ID; rule identity is never reconstructed from the ID. */
    fun respond(requestId: String, option: ApprovalOption) {
        val approval = synchronized(lock) { pending[requestId] } ?: return
        if (option == ApprovalOption.ASK) return

        synchronized(lock) {
            when (option) {
                ApprovalOption.ALLOW_ONCE -> Unit
                ApprovalOption.ALLOW_FOR_TASK -> {
                    if (approval.request.runId.isNotBlank()) {
                        perRunAllowed.getOrPut(approval.request.runId) { mutableSetOf() }.add(approval.ruleKey)
                    }
                }
                ApprovalOption.ALWAYS_ALLOW -> persist(approval.ruleKey, ApprovalRuleEffect.ALLOW)
                ApprovalOption.DENY -> persist(approval.ruleKey, ApprovalRuleEffect.DENY)
                ApprovalOption.ASK -> Unit
            }
        }
        approval.deferred.complete(ApprovalDecision(requestId, option))
    }

    fun requestFor(requestId: String): ApprovalRequest? = synchronized(lock) { requestHistory[requestId] }

    fun ruleKeyForRequest(requestId: String): ApprovalRuleKey? =
        synchronized(lock) { requestHistory[requestId]?.let(::ruleKey) }

    fun persistentRules(): Set<ApprovalRule> = synchronized(lock) { persistentRules.toSet() }

    fun revoke(ruleKey: ApprovalRuleKey) {
        synchronized(lock) {
            val updated = persistentRules.filterNot { it.key == ruleKey }.toSet()
            if (updated.size != persistentRules.size) {
                ruleStore.replace(updated)
                persistentRules.clear()
                persistentRules.addAll(updated)
            }
        }
    }

    fun clearRun(runId: String) {
        synchronized(lock) { perRunAllowed.remove(runId) }
    }

    fun allowRiskLevel(level: RiskLevel) {
        synchronized(lock) { perRiskAllowed.add(level) }
    }

    private fun persist(key: ApprovalRuleKey, effect: ApprovalRuleEffect) {
        val updated = persistentRules.filterNot { it.key == key }.toMutableSet().apply {
            add(ApprovalRule(key, effect))
        }
        ruleStore.replace(updated)
        persistentRules.clear()
        persistentRules.addAll(updated)
    }

    private fun remember(request: ApprovalRequest) {
        requestHistory[request.id] = request
    }

    private fun ruleKey(request: ApprovalRequest) = ApprovalRuleKey(
        toolId = request.toolId,
        action = request.action,
        target = request.target,
        risk = request.riskLevel,
        agentScope = request.agentScope,
    )

    private companion object {
        const val MAX_REQUEST_HISTORY = 1_000
    }
}
