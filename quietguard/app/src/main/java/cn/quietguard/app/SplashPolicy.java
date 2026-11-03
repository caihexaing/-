package cn.quietguard.app;

import java.util.Locale;
import java.util.regex.Pattern;

/** Position-independent matching of small, labelled skip controls with ad/countdown evidence. */
public final class SplashPolicy {
    private static final Pattern SKIP = Pattern.compile(
        "^(?:[0-9]{1,2}(?:s|秒)?[|/·:：-]?)?(?:跳过(?:广告)?|关闭广告|skip(?:ad)?)(?:[|/·:：-]?[0-9]{1,2}(?:s|秒)?)?$");
    private SplashPolicy() { }
    private static String compact(String s) {
        return s == null || s.length() > 64 ? "" : s.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\u00a0()（）]", "").replace('｜', '|');
    }
    public static boolean skipLabel(String text) { return SKIP.matcher(compact(text)).matches(); }
    public static boolean countdownLabel(String text) { return skipLabel(text) && compact(text).matches(".*[0-9].*"); }
    public static boolean explicitAdSkip(String text) {
        String s = compact(text);
        return s.equals("跳过广告") || s.equals("关闭广告") || s.equals("skipad");
    }
    public static boolean adMarker(String text) {
        if (text == null || text.length() > 32) return false;
        String s = compact(text);
        return s.equals("广告") || s.equals("ad") || s.equals("advertisement") || s.equals("广告详情") || s.equals("广告反馈");
    }
    public static boolean candidate(String text, boolean adMarker, int left, int top, int right, int bottom,
                                    int windowLeft, int windowTop, int windowRight, int windowBottom) {
        return candidate(text, adMarker, false, left, top, right, bottom,
            windowLeft, windowTop, windowRight, windowBottom);
    }
    public static boolean candidate(String text, boolean adMarker, boolean allowPlainSkip,
                                    int left, int top, int right, int bottom,
                                    int windowLeft, int windowTop, int windowRight, int windowBottom) {
        if (!skipLabel(text) || (!adMarker && !countdownLabel(text) && !explicitAdSkip(text) && !allowPlainSkip)) return false;
        long width = (long)windowRight - windowLeft, height = (long)windowBottom - windowTop;
        long w = (long)right - left, h = (long)bottom - top;
        if (width <= 0 || height <= 0 || w <= 0 || h <= 0) return false;
        if (left < windowLeft || top < windowTop || right > windowRight || bottom > windowBottom) return false;
        return w <= width * .46 && h <= height * .12;
    }
}
