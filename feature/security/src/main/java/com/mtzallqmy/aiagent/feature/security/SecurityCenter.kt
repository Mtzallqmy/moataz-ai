package com.mtzallqmy.aiagent.feature.security

import android.content.Context
import com.mtzallqmy.aiagent.permissions.PermissionInspector
import com.mtzallqmy.aiagent.feature.sandbox.SandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Real security-center status: no hardcoded OK badges. */
class SecurityCenter(private val context: Context) {
    private val inspector = PermissionInspector(context)
    private val sandbox = SandboxManager(context)

    suspend fun collect(): SecurityReport = withContext(Dispatchers.IO) {
        SecurityReport(
            accessibilityEnabled = inspector.isAccessibilityEnabled(),
            notificationListenerEnabled = inspector.isNotificationListenerEnabled(),
            notificationsPermission = inspector.notificationsGranted,
            storageAccess = inspector.storageAccessGranted,
            sandboxContacts = sandbox.checkAppOp("OP_READ_CONTACTS").mode,
            sms = sandbox.checkAppOp("OP_READ_SMS").mode,
            callLog = sandbox.checkAppOp("OP_READ_CALL_LOG").mode,
        )
    }
}

data class SecurityReport(
    val accessibilityEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
    val notificationsPermission: Boolean,
    val storageAccess: Boolean,
    val sandboxContacts: String,
    val sms: String,
    val callLog: String,
)
