package cn.quietguard.app;

import java.util.HashSet;
import java.util.Set;

/** One atomic bulk selection; exemptions and explicit per-app modes always win. */
final class SelectionPlan {
    final Set<String> selected, skipOnly;
    final int added;
    SelectionPlan(Set<String> before, Set<String> skip, Set<String> configured, Set<String> exempt,
            Set<String> candidates, boolean conservative) {
        selected = new HashSet<>(before); selected.removeAll(exempt);
        skipOnly = new HashSet<>(skip);
        int count = 0;
        for (String pkg : candidates) {
            if (exempt.contains(pkg) || !selected.add(pkg)) continue;
            count++;
            if (conservative && !configured.contains(pkg)) skipOnly.add(pkg);
        }
        added = count;
    }
}
