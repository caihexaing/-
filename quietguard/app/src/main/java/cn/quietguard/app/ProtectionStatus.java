package cn.quietguard.app;

import java.util.HashSet;
import java.util.Set;

/** Configured scope is distinct from a live tunnel's scope. */
final class ProtectionStatus {
    final boolean requested, skipReady, updating;
    final int selectedCount, networkCount, skipOnlyCount, networkActiveCount;
    ProtectionStatus(boolean requested, Set<String> selected, Set<String> network,
            Set<String> applied, boolean networkRunning, boolean updating, boolean skipReady) {
        this.requested = requested; this.updating = updating;
        this.skipReady = requested && !selected.isEmpty() && skipReady;
        selectedCount = selected.size();
        Set<String> configured = new HashSet<>(network); configured.retainAll(selected);
        networkCount = configured.size(); skipOnlyCount = selectedCount - networkCount;
        Set<String> active = new HashSet<>(applied); active.retainAll(configured);
        networkActiveCount = requested && networkRunning ? active.size() : 0;
    }
    String title() {
        if (!requested) return "防护已暂停";
        if (selectedCount == 0) return "尚未选择应用";
        if (networkActiveCount > 0 || skipReady) return updating ? "开屏跳过可用 · 更新期间网络直连" : "防护中";
        if (updating) return "更新期间网络直连";
        return "防护待就绪";
    }
    String action() { return requested ? "暂停防护" : "恢复防护"; }
    String scope() { return "已选 " + selectedCount + " · 仅跳过 " + skipOnlyCount + " · 网络+跳过 " + networkCount; }
}
