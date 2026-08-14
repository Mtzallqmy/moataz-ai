package com.mtzallqmy.aiagent.feature.sandbox

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import com.mtzallqmy.aiagent.common.AgentException

/**
 * App-sandbox manager: real Android AppOps inspection; never grants blanket
 * permissions. Reports actual app-op states; dangerous ops flagged for approval.
 */
class SandboxManager(private val context: Context) {

    data class AppOpState(val op: String, val mode: String)

    fun checkAppOp(opField: String): AppOpState {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return AppOpState(opField, "unsupported")
        val uid = context.applicationInfo.uid
        val pkg = context.packageName
        val mode = try {
            appOps.unsafeCheckOpNoThrow(opField, uid, pkg)
        } catch (e: SecurityException) {
            return AppOpState(opField, "unknown_op")
        }
        val modeName = when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            else -> "mode_$mode"
        }
        return AppOpState(opField, modeName)
    }

    /** Sensitive ops that always require explicit approval before use. */
    val sensitiveOps = setOf(
        "OP_READ_CONTACTS", "OP_READ_SMS", "OP_READ_CALL_LOG", "OP_RECORD_AUDIO",
        "OP_CAMERA", "OP_READ_PHONE_STATE", "OP_LOCATION",
    )
}
