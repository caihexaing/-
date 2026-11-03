package cn.quietguard.app;

/** A bounded startup window that is not extended by repeated content/activity changes. */
public final class SplashWindow {
    public static final long DURATION_MS = 15000;
    private String foreground = "";
    private long entered;
    private int attempts;
    private long lastAttempt = Long.MIN_VALUE;
    private boolean finished;
    private boolean accepted;
    private int acceptedLeft, acceptedTop, acceptedRight, acceptedBottom;
    public boolean observe(String pkg, boolean selected, boolean enabled, long now) {
        if (!enabled || !selected || pkg == null || pkg.isEmpty()) { reset(); return false; }
        if (!pkg.equals(foreground)) { reset(); foreground = pkg; entered = now; }
        return !finished && now >= entered && now - entered <= DURATION_MS;
    }
    public boolean canAttempt(long now) {
        return !foreground.isEmpty() && !finished && now >= entered && now - entered <= DURATION_MS
            && attempts == 0;
    }
    public boolean attempted(long now) {
        if (!canAttempt(now)) return false;
        attempts = 1; lastAttempt = now; return true;
    }
    public boolean hasAttempted() { return attempts > 0; }
    public boolean exhausted(long now) { return hasAttempted() && now - lastAttempt >= 500; }
    public void accepted(int left, int top, int right, int bottom) {
        accepted = true;
        acceptedLeft = left; acceptedTop = top; acceptedRight = right; acceptedBottom = bottom;
    }
    public boolean hasAccepted() { return accepted; }
    public boolean matchesAcceptedTarget(int left, int top, int right, int bottom) {
        if (!accepted) return true;
        // Observation only: these bounds never authorize a second action or a screen gesture.
        return left < acceptedRight && right > acceptedLeft && top < acceptedBottom && bottom > acceptedTop
            && Math.abs(((long)left + right) - ((long)acceptedLeft + acceptedRight)) <= 48
            && Math.abs(((long)top + bottom) - ((long)acceptedTop + acceptedBottom)) <= 48;
    }
    public void finish() { finished = true; }
    public void reset() { foreground = ""; entered = 0; attempts = 0; lastAttempt = Long.MIN_VALUE; finished = false; accepted = false; }
}
