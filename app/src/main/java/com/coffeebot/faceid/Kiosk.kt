package com.coffeebot.faceid

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** Нужен только для того, чтобы приложение могло стать владельцем устройства (Device Owner). */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Режим киоска.
 * Device Owner: полная блокировка (нет «Домой», «Недавних», шторки), приложение — домашний экран,
 * стартует после перезагрузки. Без Device Owner — закрепление экрана Android (слабее).
 * Выйти можно только из админки: [exit].
 */
object Kiosk {
    private const val TAG = "Kiosk"

    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun homeAlias(ctx: Context) = ComponentName(ctx.packageName, "${ctx.packageName}.HomeAlias")

    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun isLocked(ctx: Context) =
        ctx.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    fun enter(activity: Activity) {
        try {
            if (isDeviceOwner(activity)) {
                val dpm = dpm(activity)
                val admin = admin(activity)
                dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                }
                setAsHome(activity, true)
            }
            if (!isLocked(activity)) activity.startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "lock task failed", e)
        }
    }

    /** Выход из киоска и закрытие приложения — только из админки. */
    fun exit(activity: Activity) {
        try {
            if (isDeviceOwner(activity)) setAsHome(activity, false)
            if (isLocked(activity)) activity.stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "unlock failed", e)
        }
        activity.finishAndRemoveTask()
    }

    /** Device Owner: сделать приложение домашним экраном (кнопка «Домой» и загрузка ведут в него). */
    private fun setAsHome(ctx: Context, enable: Boolean) {
        val pm = ctx.packageManager
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val alias = homeAlias(ctx)
        if (enable) {
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, alias)
        } else {
            dpm.clearPackagePersistentPreferredActivities(admin, ctx.packageName)
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}
