package cn.quietguard.app;

/**
 * Requires two independent scans of the same small, explicitly labelled node before a coordinate tap.
 * Restored in 0.4.25 after 0.4.20 removed it and lost every self-drawn skip button.
 *
 * <p>Differences from the 0.4.7 original:
 * <ul>
 *   <li>a bare "跳过"/"skip" label never authorizes a tap; it must carry a countdown or be an explicit ad label,
 *       unless the user turned on plain skip compatibility for that app;</li>
 *   <li>the observation window is bounded by {@link #MAX_SPAN_MS} so a slow-moving screen cannot keep authorizing;</li>
 *   <li>callback slippage weakens the app instead of crashing (see {@link #penalties(int)}).</li>
 * </ul>
 * No coordinates persist.
 */
public final class SplashTextTap {
    public static final long MIN_STABLE_MS = 350, MAX_GAP_MS = 1300, MAX_SPAN_MS = 4000;
    /** Foreground must move away within this delay after a tap, otherwise the tap did nothing useful. */
    public static final long VERIFY_MS = 1500;
    /** Consecutive non-effective taps before coordinate tapping is disabled for that app. */
    public static final int MAX_PENALTIES = 2;
    private long seenAt, firstAt, identity;
    private String pkg = "";
    private int window, left, top, right, bottom, screenLeft, screenTop, screenRight, screenBottom;
    private boolean seen;

    /** Static geometry and semantics for the labelled leaf that would receive the tap. */
    public static boolean valid(SplashButton.Info i, SplashButton.Info screen, String pkg, int window,
                                boolean ad, boolean plain, float density) {
        if (i == null || screen == null || !i.visible || !i.enabled || i.editable || i.scrollable
                || !pkg.equals(i.pkg) || i.window != window || !(density > 0) || Float.isInfinite(density)) return false;
        String label = SplashPolicy.actionLabel(i.text, i.description);
        if (label == null || !SplashPolicy.candidate(label, ad, plain, i.left, i.top, i.right, i.bottom,
                screen.left, screen.top, screen.right, screen.bottom)) return false;
        long w = (long) i.right - i.left, h = (long) i.bottom - i.top;
        long sw = (long) screen.right - screen.left, sh = (long) screen.bottom - screen.top;
        return w >= 8 * density && h >= 8 * density && w <= 180 * density && h <= 64 * density
            && w <= sw * .30 && h <= sh * .08;
    }

    /**
     * A tap is authorized only when the label itself carries ad evidence. A bare skip label can point at a
     * tutorial or login prompt, so it needs the per-app plain compatibility opt-in.
     */
    public static boolean evidence(String text, String description, boolean plain) {
        String label = SplashPolicy.actionLabel(text, description);
        if (label == null) return false;
        return plain || SplashPolicy.countdownLabel(label) || SplashPolicy.explicitAdSkip(label);
    }

    /**
     * Records one scan. Returns true only on the second scan that confirms the same node at the same place
     * inside the stability window. Calls that cannot confirm reset the pending observation.
     */
    public boolean observe(SplashButton.Info i, SplashButton.Info screen, String pkg, int window, long identity,
                           boolean enabled, boolean ad, boolean plain, float density, long now) {
        if (!enabled || !valid(i, screen, pkg, window, ad, plain, density)) { reset(); return false; }
        boolean same = matches(i, screen, pkg, window, identity);
        boolean tooSoon = now - seenAt < MIN_STABLE_MS;
        boolean tooLate = now - seenAt > MAX_GAP_MS || now - firstAt > MAX_SPAN_MS;
        if (!same || now < seenAt || tooLate) {
            seen = true; seenAt = now; firstAt = now; this.identity = identity; this.pkg = pkg; this.window = window;
            left = i.left; top = i.top; right = i.right; bottom = i.bottom;
            screenLeft = screen.left; screenTop = screen.top; screenRight = screen.right; screenBottom = screen.bottom;
            return false;
        }
        if (tooSoon) return false;
        return true;
    }

    public boolean matches(SplashButton.Info i, SplashButton.Info screen, String pkg, int window, long identity) {
        return seen && this.identity == identity && this.pkg.equals(pkg) && this.window == window
            && left == i.left && top == i.top && right == i.right && bottom == i.bottom
            && screenLeft == screen.left && screenTop == screen.top && screenRight == screen.right
            && screenBottom == screen.bottom;
    }

    /**
     * Counts a tap that the foreground did not react to. Two consecutive misses mean the coordinates are
     * pointing somewhere useless or harmful, so coordinate tapping stops for that app.
     */
    public static int penalties(int previous, boolean effective) {
        return effective ? 0 : Math.min(previous + 1, MAX_PENALTIES);
    }

    public static boolean disabled(int penalties) { return penalties >= MAX_PENALTIES; }

    public void reset() { seen = false; pkg = ""; }
}
