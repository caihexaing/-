package cn.quietguard.app;

/** A bounded startup window that is not extended by repeated content/activity changes. */
public final class SplashWindow {
    public static final long DURATION_MS = 15000;
    private String foreground = "";
    private long entered;
    private int attempts;
    private long lastAttempt = Long.MIN_VALUE;
    private boolean finished;
    public boolean observe(String pkg, boolean selected, boolean enabled, long now) {
        if (!enabled || !selected || pkg == null || pkg.isEmpty()) { reset(); return false; }
        if (!pkg.equals(foreground)) { reset(); foreground = pkg; entered = now; }
        return !finished && now >= entered && now - entered <= DURATION_MS;
    }
    public boolean canAttempt(long now) {
        return !foreground.isEmpty() && !finished && now >= entered && now - entered <= DURATION_MS
            && attempts < 2 && (attempts == 0 || now - lastAttempt >= 1000);
    }
    public void attempted(long now) { attempts++; lastAttempt = now; }
    public void finish() { finished = true; }
    public void reset() { foreground = ""; entered = 0; attempts = 0; lastAttempt = Long.MIN_VALUE; finished = false; }
}
