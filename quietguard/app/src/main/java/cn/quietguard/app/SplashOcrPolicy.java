package cn.quietguard.app;


/** Pure validation and two-frame stability checks for locally recognized skip labels. */
public final class SplashOcrPolicy {
    private static final long MIN_FRAME_GAP_MS = 250, MAX_GAP_MS = 1400, MAX_SPAN_MS = 4000;
    private static final int POSITION_TOLERANCE_DP = 16;
    private String pkg = "", label = "";
    private int window, left, top, right, bottom, screenLeft, screenTop, screenRight, screenBottom;
    private long firstAt, lastAt;
    private boolean seen;

    public static boolean valid(String text, boolean ad, boolean plain,
                                int left, int top, int right, int bottom,
                                int screenLeft, int screenTop, int screenRight, int screenBottom,
                                float density) {
        if (!(density > 0) || Float.isInfinite(density) || Float.isNaN(density)) return false;
        if (!SplashPolicy.candidate(text, ad, plain, left, top, right, bottom,
                screenLeft, screenTop, screenRight, screenBottom)) return false;
        long width = (long) right - left, height = (long) bottom - top;
        long screenWidth = (long) screenRight - screenLeft, screenHeight = (long) screenBottom - screenTop;
        return width >= 8 * density && height >= 8 * density
            && width <= 180 * density && height <= 64 * density
            && width <= screenWidth * .30 && height <= screenHeight * .08;
    }

    /** Returns true only when two separate frames confirm the same small skip label. */
    public boolean observe(String text, boolean ad, boolean plain,
                           int left, int top, int right, int bottom,
                           int screenLeft, int screenTop, int screenRight, int screenBottom,
                           String pkg, int window, float density, long now) {
        if (pkg == null || pkg.isEmpty() || !valid(text, ad, plain, left, top, right, bottom,
                screenLeft, screenTop, screenRight, screenBottom, density)) {
            reset(); return false;
        }
        boolean sameSession = seen && this.pkg.equals(pkg) && this.window == window
            && this.screenLeft == screenLeft && this.screenTop == screenTop
            && this.screenRight == screenRight && this.screenBottom == screenBottom;
        boolean sameTarget = sameSession && SplashPolicy.skipLabel(text)
            && near(left, right, this.left, this.right, density)
            && near(top, bottom, this.top, this.bottom, density);
        boolean stale = !sameTarget || now < lastAt || now - lastAt > MAX_GAP_MS || now - firstAt > MAX_SPAN_MS;
        if (stale) {
            remember(text, left, top, right, bottom, screenLeft, screenTop, screenRight, screenBottom, pkg, window, now, true);
            return false;
        }
        if (now - lastAt < MIN_FRAME_GAP_MS) return false;
        remember(text, left, top, right, bottom, screenLeft, screenTop, screenRight, screenBottom, pkg, window, now, false);
        return true;
    }

    private static boolean near(int aLeft, int aRight, int bLeft, int bRight, float density) {
        long aw = (long) aRight - aLeft, bw = (long) bRight - bLeft;
        long ac = (long) aLeft + aRight, bc = (long) bLeft + bRight;
        long tolerance = Math.max(Math.round(POSITION_TOLERANCE_DP * density), Math.max(aw, bw) / 4);
        return Math.abs(ac - bc) <= 2 * tolerance && Math.abs(aw - bw) <= 2 * tolerance;
    }

    private void remember(String text, int left, int top, int right, int bottom,
                          int screenLeft, int screenTop, int screenRight, int screenBottom,
                          String pkg, int window, long now, boolean restart) {
        if (restart || !seen || !this.pkg.equals(pkg) || this.window != window
                || this.screenLeft != screenLeft || this.screenTop != screenTop
                || this.screenRight != screenRight || this.screenBottom != screenBottom
                || !near(left, right, this.left, this.right, 1)
                || !near(top, bottom, this.top, this.bottom, 1)) firstAt = now;
        this.pkg = pkg; this.window = window; this.label = "skip";
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.screenLeft = screenLeft; this.screenTop = screenTop;
        this.screenRight = screenRight; this.screenBottom = screenBottom;
        lastAt = now; seen = true;
    }

    public boolean matches(String pkg, int window, int left, int top, int right, int bottom,
                           int screenLeft, int screenTop, int screenRight, int screenBottom) {
        return seen && "skip".equals(label) && this.pkg.equals(pkg) && this.window == window
            && this.left == left && this.top == top && this.right == right && this.bottom == bottom
            && this.screenLeft == screenLeft && this.screenTop == screenTop
            && this.screenRight == screenRight && this.screenBottom == screenBottom;
    }

    private void reset() { seen = false; pkg = ""; label = ""; }
    public void clear() { reset(); }
}
