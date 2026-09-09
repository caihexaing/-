package com.example.ticketassistant.automation

import android.content.Context

class OfficialAppLauncher(private val context: Context) {
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getApplicationInfo(TicketAccessibilityService.OFFICIAL_PACKAGE, 0)
    }.isSuccess

    fun launch(): Result<Unit> = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(TicketAccessibilityService.OFFICIAL_PACKAGE)
            ?: error("未找到官方 12306 的启动入口")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
