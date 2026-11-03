package cn.quietguard.app;

/** One worker owns this clock; window events cannot bypass scan or full-tree cooldowns. */
public final class SplashScanBudget {
    public static final long INTERVAL_MS = 500, TREE_INTERVAL_MS = 2500, TREE_SLICE_MS = 80;
    private long nextScan, nextTree;
    public long delay(long now) { return Math.max(0, nextScan - now); }
    public void completed(long now) { nextScan = now + INTERVAL_MS; }
    public boolean allowTree(long now) {
        if (now < nextTree) return false;
        nextTree = now + TREE_INTERVAL_MS; return true;
    }
    public void reset() { nextScan = 0; nextTree = 0; }
}
