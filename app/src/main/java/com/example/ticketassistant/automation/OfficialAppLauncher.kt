package com.example.ticketassistant.automation

import android.content.Context

class OfficialAppLauncher(private val context: Context) {
    fun isInstalled(): Boolean = launchIntent() != null

    fun launch(): Result<Unit> = runCatching {
        val intent = launchIntent()
            ?: error("未找到官方 12306 的启动入口")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun launchIntent() = context.packageManager
        .getLaunchIntentForPackage(TicketAccessibilityService.OFFICIAL_PACKAGE)
}
