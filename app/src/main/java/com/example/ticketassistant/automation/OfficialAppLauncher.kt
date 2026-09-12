package com.example.ticketassistant.automation

import android.content.Context

class OfficialAppLauncher(private val context: Context) {
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getApplicationInfo(TicketAccessibilityService.OFFICIAL_PACKAGE, 0).enabled
    }.getOrDefault(false)

    fun launch(): Result<Unit> = runCatching {
        val intent = launchIntent()
            ?: error("已检测到官方 12306，但系统没有可启动入口；请先手动打开一次官方 App")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun launchIntent() = context.packageManager
        .getLaunchIntentForPackage(TicketAccessibilityService.OFFICIAL_PACKAGE)
}
