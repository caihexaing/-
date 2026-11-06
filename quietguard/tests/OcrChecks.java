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

        // The Taobao/AnyThink splash draws "跳过" (81px wide, 27dp) and the countdown "2" (28px, 9dp) as two
        // separate elements on the same line. The merged target must still land on the whole 跳过+数字 box.
        check(valid("跳过", true, false, 1032, 175, 1113, 220), "27dp word accepted with ad context");
        check(SplashOcrPolicy.valid("跳过", true, false, 1032, 175, 1113, 220, 81, 0, 0, 1260, 2820, true, true, 3f),
            "word merged with a flush countdown digit is accepted");
        // A bare countdown digit is not a skip label on its own; it only qualifies as part of a merged box.
        check(!SplashOcrPolicy.valid("2", true, false, 1116, 175, 1144, 220, 28, 0, 0, 1260, 2820, false, false, 3f),
            "a lone countdown digit is never a skip target");
        // A box narrower than 4dp is always refused; a 4.3dp box clears the relaxed floor only with a digit.
        check(!SplashOcrPolicy.valid("跳过", true, false, 1032, 175, 1045, 220, 13, 0, 0, 1260, 2820, false, false, 3f),
            "a 4.3dp word fragment without a digit is refused");
        check(SplashOcrPolicy.valid("跳过", true, false, 1032, 175, 1045, 220, 13, 0, 0, 1260, 2820, false, true, 3f),
            "a narrow word is accepted only when a countdown digit sits beside it");
        check(!SplashOcrPolicy.valid("跳过", true, false, 1032, 175, 1043, 220, 11, 0, 0, 1260, 2820, false, true, 3f),
            "a 3.6dp box stays refused even with a digit");
        // Merged target keeps the upper bounds: a full-width banner cannot pretend to be a skip button.
        check(!SplashOcrPolicy.valid("跳过", true, false, 40, 300, 1180, 900, 81, 0, 0, 1260, 2820, true, true, 3f),
            "merged banner still refused by the upper bounds");
        // Merging must not lower the bar for a huge box either: 300dp tall countdown row is refused.
        check(!SplashOcrPolicy.valid("5", true, false, 900, 100, 1000, 1300, 100, 0, 0, 1260, 2820, true, true, 3f),
            "a tall merged box is still refused");
        // The digit must be on the same line and flush: a distant number is not the countdown.
        check(SplashOcrPolicy.beside(1032,175,1113,220, 1116,175,1144,220, 3f), "flush digit on the same line is beside");
        check(!SplashOcrPolicy.beside(1032,175,1113,220, 1116,900,1144,945, 3f), "digit on another line is not beside");
        check(!SplashOcrPolicy.beside(1032,175,1113,220, 1180,175,1208,220, 3f), "digit far to the right is not beside");
        check(!SplashOcrPolicy.beside(1032,175,1113,220, 700,175,728,220, 3f), "digit far to the left is not beside");
        check(SplashOcrPolicy.countdownText("2") && SplashOcrPolicy.countdownText("5s") && SplashOcrPolicy.countdownText("08")
            && !SplashOcrPolicy.countdownText("跳过") && !SplashOcrPolicy.countdownText("2026"), "countdown text recognizes only 1-2 digits");

        SplashOcrPolicy policy = new SplashOcrPolicy();
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",7,3f,1000), "one frame never taps");
        check(policy.observe("跳过", true, false, 80,1952,150,1992, 0,0,1260,2820, "target",7,3f,1600), "stable second frame confirms");
        check(policy.matches("target",7,80,1952,150,1992,0,0,1260,2820), "confirmed frame geometry is recorded");
        check(policy.observe("3秒|跳过", true, false, 80,1952,150,1992, 0,0,1260,2820, "target",7,3f,2000), "countdown changing across frames preserves the same skip target");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "other",7,3f,1800), "package change resets confirmation");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",8,3f,2000), "window change starts a new confirmation");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",8,3f,2100), "frames too close do not confirm");
        check(policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",8,3f,3600), "real screenshot cadence confirms inside the widened window");
        policy.clear();
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",9,3f,10000), "one frame never taps");
        check(policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",9,3f,12500), "frames 2.5s apart still confirm");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",10,3f,20000), "one frame never taps on a new window");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",10,3f,24500), "frames beyond the confirmation window start over");
        check(!policy.observe("跳过", true, false, 76,1950,146,1990, 0,0,1260,2820, "target",10,3f,31000), "same gap twice still needs two fresh frames");
        check(!policy.observe("跳转至详情页", true, true, 76,1950,146,1990, 0,0,1260,2820, "target",10,3f,32000), "non-skip OCR clears pending confirmation");
        policy.clear();
        check(!policy.matches("target",8,76,1950,146,1990,0,0,1260,2820), "clear drops OCR coordinates");
        System.out.println("OcrChecks passed: " + checks);
    }

    private static boolean valid(String text, boolean ad, boolean plain,
                                 int l, int t, int r, int b, float density) {
        return SplashOcrPolicy.valid(text, ad, plain, l, t, r, b, 0, 0, 1260, 2820, density);
    }
}
