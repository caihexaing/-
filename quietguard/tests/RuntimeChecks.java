package cn.quietguard.app;

import java.util.HashSet;
import java.util.Set;

public final class RuntimeChecks {
    static int checks;
    static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    public static void main(String[] args) {
        SplashScanBudget b = new SplashScanBudget();
        check(b.delay(0) == 0, "first scan is immediate");
        b.completed(100);
        check(b.delay(100) == 500, "cooldown is measured from completion");
        check(b.delay(599) == 1, "early event cannot scan");
        check(b.delay(600) == 0, "boundary scan allowed");
        int scans = 0;
        for (long now = 0; now < 15000; now++) {
            if (b.delay(now) == 0) { scans++; b.completed(now); }
        }
        check(scans <= 30, "event storm does not cause hundreds of scans in entry window");
        b.reset(); check(b.allowTree(100), "initial full fallback available");
        check(!b.allowTree(101), "duplicate event cannot force full traversal");
        check(!b.allowTree(2599), "full tree cooldown boundary");
        check(b.allowTree(2600), "full tree allowed after cooldown");
        b.completed(5000);
        check(b.delay(5300) == 200, "later event preserves pending cooldown");
        b.reset(); check(b.delay(5300) == 0, "new session can run immediately");
        check(b.allowTree(5300), "reset clears fallback cooldown");
        check(SplashScanBudget.TREE_SLICE_MS < SplashScanBudget.INTERVAL_MS, "fallback budget is smaller than scan interval");

        Set<String> selected = new HashSet<>(Set.of("residence", "calendar"));
        Set<String> skipOnly = new HashSet<>();
        check(ProtectionMode.networkApps(selected, skipOnly).equals(selected), "old installations keep both modes");
        skipOnly.add("residence");
        check(ProtectionMode.networkApps(selected, skipOnly).equals(Set.of("calendar")), "skip-only app is excluded from VPN");
        check(selected.contains("residence"), "skip-only app remains selected for accessibility");
        check(selected.size() == 2 && skipOnly.size() == 1, "routing does not mutate preferences");
        skipOnly.add("calendar");
        check(ProtectionMode.networkApps(selected, skipOnly).isEmpty(), "all skip-only needs no VPN");
        skipOnly.add("uninstalled");
        check(ProtectionMode.networkApps(Set.of(), skipOnly).isEmpty(), "stale preference cannot re-enable unselected app");
        selected.remove("residence"); skipOnly.remove("calendar");
        check(ProtectionMode.networkApps(selected, skipOnly).equals(Set.of("calendar")), "switching back restores only selected app");
        Set<String> result = ProtectionMode.networkApps(selected, skipOnly); result.clear();
        check(selected.equals(Set.of("calendar")), "mutable routing result does not alias selection");

        SplashWindow w = new SplashWindow(); w.observe("target", true, true, 0);
        check(!w.exhausted(5000), "no attempts is not exhausted");
        check(w.attempted(0), "one action can be reserved");
        check(!w.exhausted(499), "wait for one click observation");
        check(!w.attempted(1000), "no second action can be reserved");
        check(w.exhausted(500), "single action stops scanning after observation");
        w.reset(); check(!w.exhausted(5000), "new session clears exhaustion");
        UpdateVpnState update = new UpdateVpnState();
        update.networkRequested(); check(!update.restore, "request outside update does not invent restore intent");
        update.beginDownload(false); update.networkRequested();
        check(!update.canRestore(true,true,true), "mode change cannot restart VPN during download");
        update.endDownload(-2);
        check(update.canRestore(true,true,true), "explicit mode change can restore after download");
        update.beginDownload(true); update.networkRequested(); update.userDisabled(); update.endDownload(-2);
        check(!update.canRestore(true,true,true), "later user pause overrides mode change restore");
        System.out.println("PASS: " + checks + " scan cooldown, event storm, per-app network mode and retry checks.");
    }
}
