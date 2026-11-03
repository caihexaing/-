package cn.quietguard.app;

import java.util.HashSet;
import java.util.Set;

/** App selection controls skip eligibility; only its network subset goes into VPN. */
public final class ProtectionMode {
    private ProtectionMode() { }
    public static Set<String> networkApps(Set<String> selected, Set<String> skipOnly) {
        Set<String> result = new HashSet<>(selected); result.removeAll(skipOnly); return result;
    }
}
