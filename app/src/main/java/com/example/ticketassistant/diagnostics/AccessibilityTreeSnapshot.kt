package com.example.ticketassistant.diagnostics

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** A bounded, already-redacted accessibility snapshot for one-shot diagnostics. */
data class SanitizedAccessibilitySnapshot(
    val capturedAt: Long,
    val source: String,
    val pageState: String,
    val automationStage: String,
    val rootPackage: String,
    val nodeCount: Int,
    val truncated: Boolean,
    val treeJson: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("capturedAt", capturedAt)
        put("source", source)
        put("pageState", pageState)
        put("automationStage", automationStage)
        put("rootPackage", rootPackage)
        put("nodeCount", nodeCount)
        put("truncated", truncated)
        put("tree", JSONObject(treeJson))
    }
}

/** Keeps only a few recent snapshots so raw accessibility data is not persisted. */
object AccessibilitySnapshotBuffer {
    private const val MAX_SNAPSHOTS = 5
    private val snapshots = ArrayDeque<SanitizedAccessibilitySnapshot>()

    @Synchronized
    fun add(snapshot: SanitizedAccessibilitySnapshot) {
        while (snapshots.size >= MAX_SNAPSHOTS) snapshots.removeFirst()
        snapshots.addLast(snapshot)
    }

    @Synchronized
    fun list(): List<SanitizedAccessibilitySnapshot> = snapshots.toList()

    @Synchronized
    fun clear() {
        snapshots.clear()
    }
}

object AccessibilityTreeSnapshotter {
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 18
    private const val MAX_TEXT_LENGTH = 80
    private const val MAX_RESOURCE_ID_LENGTH = 120
    private const val MAX_TREE_CHARS = 120_000

    fun capture(
        root: AccessibilityNodeInfo,
        source: String,
        pageState: String,
        automationStage: String,
        passengerName: String
    ): SanitizedAccessibilitySnapshot? {
        val rootPackage = root.packageName?.toString() ?: return null
        if (rootPackage != "com.MobileTicket") return null
        val budget = Budget()
        val tree = snapshotNode(root, passengerName, 0, budget)
        tree.put("truncated", budget.truncated)
        var truncated = budget.truncated
        var treeJson = tree.toString()
        if (treeJson.length > MAX_TREE_CHARS) {
            // Keep the export valid even if a vendor exposes unusually large node data.
            truncated = true
            tree.remove("children")
            tree.put("truncated", true)
            treeJson = tree.toString()
        }
        if (treeJson.length > MAX_TREE_CHARS) {
            // The bounded fallback also protects against an unexpected vendor
            // value that exceeds the per-field limits above.
            treeJson = JSONObject()
                .put("truncated", true)
                .put("nodeCount", budget.nodes)
                .toString()
        }
        return SanitizedAccessibilitySnapshot(
            capturedAt = System.currentTimeMillis(),
            source = source,
            pageState = pageState,
            automationStage = automationStage,
            rootPackage = rootPackage,
            nodeCount = budget.nodes,
            truncated = truncated,
            treeJson = treeJson
        )
    }

    private class Budget {
        var nodes = 0
        var remainingChars = MAX_TREE_CHARS
        var truncated = false
    }

    private fun snapshotNode(
        node: AccessibilityNodeInfo,
        passengerName: String,
        depth: Int,
        budget: Budget
    ): JSONObject {
        if (depth > MAX_DEPTH || budget.nodes >= MAX_NODES || budget.remainingChars < 160) {
            budget.truncated = true
            return JSONObject().put("truncated", true)
        }
        budget.nodes++
        val text = sanitizeAccessibilityTextForDiagnostics(node.text?.toString(), passengerName, node.isPassword)
        val description = sanitizeAccessibilityTextForDiagnostics(node.contentDescription?.toString(), passengerName, node.isPassword)
        val hint = sanitizeAccessibilityTextForDiagnostics(node.hintText?.toString(), passengerName, node.isPassword)
        val resourceId = sanitizeResourceId(node.viewIdResourceName)
        budget.remainingChars -= 220 + text.length + description.length + hint.length + resourceId.length

        val result = JSONObject().apply {
            node.className?.toString()?.take(MAX_TEXT_LENGTH)?.let { put("className", it) }
            text.takeIf(String::isNotBlank)?.let { put("text", it) }
            description.takeIf(String::isNotBlank)?.let { put("contentDescription", it) }
            hint.takeIf(String::isNotBlank)?.let { put("hintText", it) }
            resourceId.takeIf(String::isNotBlank)?.let { put("resourceId", it) }
            put("visible", node.isVisibleToUser)
            put("enabled", node.isEnabled)
            put("clickable", node.isClickable)
            put("focusable", node.isFocusable)
            put("editable", node.isEditable)
            put("selected", node.isSelected)
            put("checked", node.isChecked)
            put("childCount", node.childCount)
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
        }
        val children = JSONArray()
        for (index in 0 until node.childCount) {
            if (budget.nodes >= MAX_NODES || budget.remainingChars < 160) {
                budget.truncated = true
                break
            }
            val child = node.getChild(index) ?: continue
            children.put(snapshotNode(child, passengerName, depth + 1, budget))
        }
        result.put("children", children)
        return result
    }

    private fun sanitizeResourceId(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.substringAfterLast('/').take(MAX_RESOURCE_ID_LENGTH)
    }

}

/** Redacts personal, authentication, order and payment values before export. */
internal fun sanitizeAccessibilityTextForDiagnostics(
    value: String?,
    passengerName: String,
    password: Boolean
): String {
    if (value.isNullOrBlank()) return ""
    if (password) return "<REDACTED>"
    var sanitized = value.trim().replace(Regex("\\s+"), " ").take(80)
    val knownPassenger = passengerName.trim().replace(Regex("\\s+"), " ")
    if (knownPassenger.isNotBlank()) sanitized = sanitized.replace(knownPassenger, "<PASSENGER>", ignoreCase = false)
    sanitized = sanitized.replace(Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"), "<PHONE>")
    sanitized = sanitized.replace(Regex("(?<!\\d)\\d{17}[\\dXx](?!\\d)"), "<ID_NUMBER>")
    sanitized = sanitized.replace(
        Regex("(密码|验证码|短信码|支付密码)\\s*[:：]?\\s*[^\\s,，;；]+"),
        "$1:<REDACTED>"
    )
    sanitized = sanitized.replace(
        Regex("(账号|用户名|身份证号|证件号|订单号|订单编号|支付单号|流水号)\\s*[:：]?\\s*[^\\s,，;；]+"),
        "$1:<REDACTED>"
    )
    sanitized = sanitized.replace(
        Regex("(姓名|联系人|乘车人)\\s*[:：]?\\s*[^\\s,，;；]+"),
        "$1:<PASSENGER>"
    )
    sanitized = sanitized.replace(
        Regex("(?:金额|应付|实付)\\s*[:：]?\\s*(?:¥|￥)?[0-9]+(?:\\.[0-9]{1,2})?(?:元)?"),
        "金额:<AMOUNT>"
    )
    return sanitized.take(80)
}
