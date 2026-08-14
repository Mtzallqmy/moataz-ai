package com.mtzallqmy.aiagent.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Real permission detection against Android runtime state — never capability(true)
 * without detection. Used by Capability Registry for availability states.
 */
class PermissionInspector(private val context: Context) {

    fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    val storageAccessGranted: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            true
        } else {
            @Suppress("DEPRECATION")
            isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    val notificationsGranted: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    fun isAccessibilityEnabled(): Boolean =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    fun isNotificationListenerEnabled(): Boolean =
        Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.contains(context.packageName) == true

    fun checkAppOp(appOpsFieldName: String): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val uid = context.applicationInfo.uid
        val pkg = context.packageName
        val code = try {
            AppOpsManager::class.java.getField(appOpsFieldName).getInt(null)
        } catch (e: NoSuchFieldException) {
            return false
        }
        // unsafeCheckOpNoThrow(int, int, String) — use reflection to resolve the
        // int overload explicitly (Kotlin resolves the String overload by default).
        return runCatching {
            val op: Int = code
            val method = AppOpsManager::class.java.getMethod(
                "unsafeCheckOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            method.invoke(appOps, op, uid, pkg) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
