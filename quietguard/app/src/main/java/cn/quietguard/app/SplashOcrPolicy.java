package cn.quietguard.app;


/** Pure validation and two-frame stability checks for locally recognized skip labels. */
public final class SplashOcrPolicy {
    // Screenshot OCR cannot capture faster than the recognition round trip plus SplashOcr.MIN_CAPTURE_GAP_MS,
// so a sub-second confirmation window would never observe two frames of a real splash ad.
    private static final long MIN_FRAME_GAP_MS = 250, MAX_GAP_MS = 4000, MAX_SPAN_MS = 9000;
    private static final int POSITION_TOLERANCE_DP = 16;
    private String pkg = "", label = "";
    private int window, left, top, right, bottom, screenLeft, screenTop, screenRight, screenBottom;
    private long firstAt, lastAt;
    private boolean seen;

    /** Countdown digits on ad skip controls are 1-2 characters, e.g. "5", "08", "5s", "5秒". */
    public static boolean countdownText(String text) {
        if (text == null) return false;
        String s = text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\u00a0]", "");
        return s.matches("[0-9]{1,2}[s秒]?");
    }

    public static boolean valid(String text, boolean ad, boolean plain,
                                int left, int top, int right, int bottom,
                                int screenLeft, int screenTop, int screenRight, int screenBottom,
                                float density) {
        return valid(text, ad, plain, left, top, right, bottom, 0,
            screenLeft, screenTop, screenRight, screenBottom, false, false, density);
    }

    /**
     * Skip controls are frequently drawn as a "跳过" word with the countdown digit as a separate element
     * beside it, so the word alone can be well under 8dp wide. When a countdown digit sits immediately
     * next to the word, or the word itself carries a countdown, accept the smaller word width. The upper
     * bounds are never relaxed: those are what stop an ad banner from passing as a skip button.
     *
     * @param wordWidth  width of just the skip word inside the box, or 0 to use the whole box
     * @param joined     true when a neighbouring countdown digit was attached to this candidate
     */
    public static boolean valid(String text, boolean ad, boolean plain,
                                int left, int top, int right, int bottom, int wordWidth,
                                int screenLeft, int screenTop, int screenRight, int screenBottom,
                                boolean joined, boolean adjacentDigit, float density) {
        if (!(density > 0) || Float.isInfinite(density) || Float.isNaN(density)) return false;
        if (!SplashPolicy.candidate(text, ad, plain, left, top, right, bottom,
                screenLeft, screenTop, screenRight, screenBottom)) return false;
        long width = (long) right - left, height = (long) bottom - top;
        long screenWidth = (long) screenRight - screenLeft, screenHeight = (long) screenBottom - screenTop;
        if (width > 180 * density || height > 64 * density
                || width > screenWidth * .30 || height > screenHeight * .08) return false;
        boolean narrow = SplashPolicy.countdownLabel(text) || joined || adjacentDigit;
        long minWidth = Math.round((narrow ? 4 : 8) * density);
        long minHeight = Math.round(8 * density);
        long measured = wordWidth > 0 ? wordWidth : width;
        return measured >= minWidth && height >= minHeight;
    }

    /** True when {@code digit} sits on the same baseline and flush against {@code skip}, e.g. "跳过" + "5". */
    public static boolean beside(int skipLeft, int skipTop, int skipRight, int skipBottom,
                                 int digitLeft, int digitTop, int digitRight, int digitBottom,
                                 float density) {
        long tolerance = Math.round(8 * density);
        long overlap = Math.min(skipBottom, digitBottom) - Math.max(skipTop, digitTop);
        long shorter = Math.min((long) skipBottom - skipTop, (long) digitBottom - digitTop);
        if (shorter <= 0 || overlap * 10 < shorter * 6) return false;               // same line, >=60% overlap
        long gap = Math.max(digitLeft - skipRight, skipLeft - digitRight);           // >=0 when disjoint
        return gap >= -Math.round(2 * density) && gap <= tolerance;                  // touching, within 8dp
    }

    /** Returns true only when two separate frames confirm the same small skip label. */
    public boolean observe(String text, boolean ad, boolean plain,
                           int left, int top, int right, int bottom,
                           int screenLeft, int screenTop, int screenRight, int screenBottom,
                           String pkg, int window, float density, long now) {
        return observe(text, ad, plain, left, top, right, bottom, 0,
            screenLeft, screenTop, screenRight, screenBottom, false, false, pkg, window, density, now, -1);
    }

    /**
     * Two-frame confirmation that keeps the recorded rectangle. When {@code keepLeft >= 0} the caller passed a
     * merged box (skip word + adjacent digit); the tap should land on that whole merged box.
     */
    public boolean observe(String text, boolean ad, boolean plain,
                           int left, int top, int right, int bottom, int wordWidth,
                           int screenLeft, int screenTop, int screenRight, int screenBottom,
                           boolean joined, boolean adjacentDigit,
                           String pkg, int window, float density, long now, int keepLeft) {
        if (pkg == null || pkg.isEmpty() || !valid(text, ad, plain, left, top, right, bottom, wordWidth,
                screenLeft, screenTop, screenRight, screenBottom, joined, adjacentDigit, density)) {
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
