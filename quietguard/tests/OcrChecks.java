package cn.quietguard.app;

public final class OcrChecks {
    private static int checks;
    private static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static boolean valid(String text, boolean ad, boolean plain, int l, int t, int r, int b) {
        return SplashOcrPolicy.valid(text, ad, plain, l, t, r, b, 0, 0, 1260, 2820, 3f);
    }
    public static void main(String[] args) {
        check(valid("跳过", true, false, 76, 1950, 146, 1990), "small skip with ad context");
        check(valid("跳过", false, true, 76, 1950, 146, 1990), "per-app plain skip opt-in");
        check(!valid("跳过", false, false, 76, 1950, 146, 1990), "bare skip without ad context refused");
        check(!valid("立即进入", true, true, 990, 170, 1190, 230), "ad landing action refused");
        check(!valid("跳转至详情页", true, true, 400, 2500, 850, 2570), "details navigation text refused");
        check(!valid("跳过", true, false, -10, 1950, 60, 1990), "target outside screen refused");
        check(!valid("跳过", true, false, 80, 1950, 800, 1990), "wide text line refused");
        check(!valid("跳过", true, false, 80, 1000, 146, 1230), "tall text line refused");
        check(!valid("跳过", true, false, 80, 1950, 146, 1990, Float.NaN), "invalid density refused");

        SplashOcrPolicy policy = new SplashOcrPolicy();
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",7,3f,1000), "one frame never taps");
        check(policy.observe("跳过", true, false, 80,1952,150,1992, 0,0,1260,2820, "target",7,3f,1600), "stable second frame confirms");
        check(policy.matches("target",7,80,1952,150,1992,0,0,1260,2820), "confirmed frame geometry is recorded");
        check(policy.observe("3秒|跳过", true, false, 80,1952,150,1992, 0,0,1260,2820, "target",7,3f,2000), "countdown changing across frames preserves the same skip target");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "other",7,3f,1800), "package change resets confirmation");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",8,3f,2000), "window change starts a new confirmation");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",8,3f,2100), "frames too close do not confirm");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",8,3f,3600), "stale frame starts over");
        check(!policy.observe("跳转至详情页", true, true, 76,1950,146,1990, 0,0,1260,2820, "target",8,3f,4000), "non-skip OCR clears pending confirmation");
        policy.clear();
        check(!policy.matches("target",8,76,1950,146,1990,0,0,1260,2820), "clear drops OCR coordinates");
        System.out.println("OcrChecks passed: " + checks);
    }

    private static boolean valid(String text, boolean ad, boolean plain,
                                 int l, int t, int r, int b, float density) {
        return SplashOcrPolicy.valid(text, ad, plain, l, t, r, b, 0, 0, 1260, 2820, density);
    }
}
