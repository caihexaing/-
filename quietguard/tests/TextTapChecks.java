package cn.quietguard.app;

public final class TextTapChecks {
    private static int checks;
    private static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static SplashButton.Info info(String text, int l, int t, int r, int b) {
        SplashButton.Info i = new SplashButton.Info(); i.text = text; i.pkg = "target"; i.window = 1;
        i.visible = true; i.enabled = true; i.left = l; i.top = t; i.right = r; i.bottom = b; return i;
    }
    private static SplashButton.Info label() { return info("跳过广告", 800, 100, 880, 140); }
    private static SplashButton.Info countdown() { return info("4s|跳过", 800, 100, 880, 140); }
    private static SplashButton.Info screen() { return info(null, 0, 0, 1000, 2000); }
    private static boolean valid(SplashButton.Info i) { return SplashTextTap.valid(i, screen(), "target", 1, true, false, 2f); }
    private static boolean observe(SplashTextTap tap, SplashButton.Info i, long identity, boolean on, long now) {
        return tap.observe(i, screen(), "target", 1, identity, on, true, false, 2f, now);
    }
    public static void main(String[] args) {
        SplashButton.Info i = label();
        check(valid(i), "small labelled leaf is eligible");
        check(SplashTextTap.evidence(i.text, i.description, false), "explicit ad label carries its own evidence");
        check(SplashTextTap.evidence(countdown().text, null, false), "countdown carries its own evidence");
        check(!SplashTextTap.evidence("跳过", null, false), "bare skip label has no ad evidence");
        check(SplashTextTap.evidence("跳过", null, true), "plain compatibility opt-in allows a bare skip label");
        check(!SplashTextTap.evidence("登录后继续", null, true), "non skip text never gets evidence");
        check(!valid(info("跳过广告", 800, 100, 810, 110)), "label smaller than 8dp at density 2 is refused");
        check(!valid(info("跳过广告", 780, 100, 1200, 140)), "label wider than the window limit is refused");
        check(!valid(info("跳过广告", 950, 100, 1050, 140)), "label over the right edge is refused");

        SplashTextTap tap = new SplashTextTap();
        check(!observe(tap, i, 1, false, 100), "disabled mode never authorizes");
        check(!observe(tap, i, 1, true, 100), "first scan never authorizes a tap");
        check(!observe(tap, i, 1, true, 449), "too soon is not an independent confirmation");
        check(observe(tap, i, 1, true, 450), "second scan after 350ms authorizes");
        check(observe(tap, i, 1, true, 600), "500ms production interval is compatible");
        check(tap.matches(i, screen(), "target", 1, 1), "confirmed position is remembered");
        tap.reset();
        check(!tap.matches(i, screen(), "target", 1, 1), "reset discards the pending location");
        check(!observe(tap, i, 1, true, 100), "reset needs a fresh first scan");

        check(!observe(tap, countdown(), 1, true, 2000), "new label at the same place resets");
        check(observe(tap, countdown(), 1, true, 2460), "countdown text that stays still authorizes");
        tap.reset(); observe(tap, i, 1, true, 100);
        SplashButton.Info moved = label(); moved.left++; moved.right++;
        check(!observe(tap, moved, 1, true, 600), "one pixel of movement resets");
        check(observe(tap, moved, 1, true, 1100), "the new position can stabilize");
        tap.reset(); observe(tap, i, 1, true, 100);
        check(!observe(tap, i, 1, true, 1401), "a stale observation gap resets");
        check(observe(tap, i, 1, true, 1901), "a new observation after the gap may stabilize");
        tap.reset(); observe(tap, i, 1, true, 100);
        check(!observe(tap, i, 1, true, 99), "clock regression resets");
        check(!observe(tap, i, 1, false, 600), "turning the mode off cancels the pending observation");
        check(!observe(tap, i, 1, true, 1100), "re-enabling requires a fresh scan");
        tap.reset(); observe(tap, i, 1, true, 100);
        SplashButton.Info resized = screen(); resized.right = 999;
        check(!tap.observe(i, resized, "target", 1, 1, true, true, false, 2f, 600), "window resize resets");
        check(!tap.matches(i, screen(), "target", 1, 1), "the old screen no longer matches");

        // A slow-moving screen must not be able to keep authorizing forever.
        tap.reset(); observe(tap, i, 1, true, 100);
        observe(tap, i, 1, true, 1000);
        check(!tap.observe(i, screen(), "target", 1, 1, true, true, false, 2f, 5600), "stability window is bounded");

        // A tap the foreground ignored counts against the app; two in a row retire coordinate tapping.
        check(SplashTextTap.penalties(0, true) == 0, "effective tap clears the strikes");
        check(SplashTextTap.penalties(0, false) == 1, "ineffective tap adds a strike");
        check(SplashTextTap.penalties(1, false) == 2, "second ineffective tap reaches the limit");
        check(SplashTextTap.penalties(5, false) == SplashTextTap.MAX_PENALTIES, "strikes are capped");
        check(!SplashTextTap.disabled(1), "one strike still allows a retry");
        check(SplashTextTap.disabled(2), "two strikes retire the mode for that app");
        check(SplashTextTap.penalties(1, true) == 0 && !SplashTextTap.disabled(0), "success restores the mode");
        System.out.println("TextTapChecks passed: " + checks);
    }
}
