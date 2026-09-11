package com.example.ticketassistant.automation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

object AccessibilityServiceStatus {
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, TicketAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            val service = info.resolveInfo?.serviceInfo ?: return@any false
            ComponentName(service.packageName, service.name) == expected
        }
    }
}
