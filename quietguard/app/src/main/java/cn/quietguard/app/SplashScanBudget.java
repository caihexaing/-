package cn.quietguard.app;

/** One worker owns this clock; repeated events cannot extend the short entry boost. */
public final class SplashScanBudget {
    public static final long INTERVAL_MS = 500, TREE_INTERVAL_MS = 2500, TREE_SLICE_MS = 80;
    public static final long FAST_INTERVAL_MS = 200, FAST_WINDOW_MS = 3000;
    private long lastCompleted, nextTree, fastUntil;
    private boolean completed;
    private String foreground = "";
    public void foreground(String pkg, boolean selected, long now) {
        String next = selected && pkg != null ? pkg : "";
        if (next.equals(foreground)) return;
        foreground = next;
        fastUntil = next.isEmpty() ? 0 : now + FAST_WINDOW_MS;
    }
    public long delay(long now) {
        if (!completed) return 0;
        long interval = !foreground.isEmpty() && now < fastUntil ? FAST_INTERVAL_MS : INTERVAL_MS;
        return Math.max(0, lastCompleted + interval - now);
    }
    public void completed(long now) { lastCompleted = now; completed = true; }
    public boolean allowTree(long now) {
        if (now < nextTree) return false;
        nextTree = now + TREE_INTERVAL_MS; return true;
    }
    public void reset() { completed = false; nextTree = 0; fastUntil = 0; foreground = ""; }
}
