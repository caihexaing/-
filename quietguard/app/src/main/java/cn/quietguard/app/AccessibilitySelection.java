package cn.quietguard.app;

/** Only add our component; preserve all other enabled services and their serialized spelling. */
public final class AccessibilitySelection {
    private AccessibilitySelection() { }
    public static boolean contains(String enabled, String full, String shortName) {
        if (enabled == null) return false;
        for (String entry : enabled.split(":")) if (entry.equals(full) || entry.equals(shortName)) return true;
        return false;
    }
    public static String add(String enabled, String full, String shortName) {
        if (contains(enabled,full,shortName)) return enabled;
        if (enabled == null || enabled.isEmpty()) return full;
        return enabled + (enabled.endsWith(":") ? "" : ":") + full;
    }
}
