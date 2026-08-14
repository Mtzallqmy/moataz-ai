package com.mtzallqmy.aiagent.feature.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.*
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Real device tools: apps.list, apps.open, apps.info, notifications. */
class DeviceToolSet(private val context: Context) {
    val tools: List<RegisteredTool> = listOf(
        RegisteredTool.typed(AppsListTool(context), AppsListInput.serializer()),
        RegisteredTool.typed(AppsOpenTool(context), AppPackageInput.serializer()),
        RegisteredTool.typed(AppsInfoTool(context), AppPackageInput.serializer()),
    )
}

@Serializable
data class AppsListInput(val query: String? = null)

@Serializable
data class AppPackageInput(val packageName: String)

private class AppsListTool(private val context: Context) : AgentTool<AppsListInput, JsonArray> {
    override val descriptor = ToolDescriptor(
        id = "apps.list", displayName = "List Apps", description = "List installed application packages",
        inputSchema = """{"type":"object","properties":{"query":{"type":"string"}}}""",
        outputSchema = """{"type":"array"}""",
        riskLevel = RiskLevel.READ, requiredCapabilities = setOf(CapabilityId("device")), timeoutMs = 15_000L,
    )
    override suspend fun availability(context: ToolContext) = ToolAvailability.Available
    override suspend fun execute(input: AppsListInput, ctx: ToolContext): JsonArray = withContext(Dispatchers.IO) {
        val query = input.query?.lowercase()
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { query == null || (it.packageName.lowercase().contains(query) || (it.loadLabel(pm).toString().lowercase().contains(query))) }
            .take(200)
        JsonArray(apps.map {
            buildJsonObject {
                put("packageName", JsonPrimitive(it.packageName))
                put("label", JsonPrimitive(it.loadLabel(pm).toString()))
            }
        })
    }
}

private class AppsOpenTool(private val context: Context) : AgentTool<AppPackageInput, JsonObject> {
    override val descriptor = ToolDescriptor(
        id = "apps.open", displayName = "Open App", description = "Launch an installed application by package name",
        inputSchema = """{"type":"object","required":["packageName"],"properties":{"packageName":{"type":"string"}}}""",
        outputSchema = """{"type":"object"}""",
        riskLevel = RiskLevel.MODIFY, requiredCapabilities = setOf(CapabilityId("device")), timeoutMs = 10_000L,
    )
    override suspend fun availability(context: ToolContext) = ToolAvailability.Available
    override suspend fun execute(input: AppPackageInput, ctx: ToolContext): JsonObject = withContext(Dispatchers.Main) {
        val intent = context.packageManager.getLaunchIntentForPackage(input.packageName)
        if (intent == null) {
            buildJsonObject { put("opened", JsonPrimitive(false)); put("reason", JsonPrimitive("No launcher intent")) }
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            buildJsonObject { put("opened", JsonPrimitive(true)); put("packageName", JsonPrimitive(input.packageName)) }
        }
    }
}

private class AppsInfoTool(private val context: Context) : AgentTool<AppPackageInput, JsonObject> {
    override val descriptor = ToolDescriptor(
        id = "apps.info", displayName = "App Info", description = "Get application metadata by package name",
        inputSchema = """{"type":"object","required":["packageName"],"properties":{"packageName":{"type":"string"}}}""",
        outputSchema = """{"type":"object"}""",
        riskLevel = RiskLevel.READ, requiredCapabilities = setOf(CapabilityId("device")), timeoutMs = 10_000L,
    )
    override suspend fun availability(context: ToolContext) = ToolAvailability.Available
    override suspend fun execute(input: AppPackageInput, ctx: ToolContext): JsonObject = withContext(Dispatchers.IO) {
        try {
            val info = context.packageManager.getApplicationInfo(input.packageName, 0)
            val pm = context.packageManager
            buildJsonObject {
                put("packageName", JsonPrimitive(input.packageName))
                put("label", JsonPrimitive(info.loadLabel(pm).toString()))
                put("enabled", JsonPrimitive(info.enabled))
                put("uid", JsonPrimitive(info.uid))
                put("sourceDir", JsonPrimitive(info.sourceDir ?: ""))
            }
        } catch (e: PackageManager.NameNotFoundException) {
            buildJsonObject { put("error", JsonPrimitive("Package not found")) }
        }
    }
}
