package cn.quietguard.app;

/** Release tags are scoped to this app because the repository also contains other projects. */
public final class UpdateVersion {
    public static boolean newer(String remote, long remoteCode, String current, long currentCode) {
        return remoteCode > currentCode && compare(remote, current) >= 0;
    }
    public static int compare(String left, String right) {
        String[] a = parse(left), b = parse(right);
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (result != 0) return result;
        }
        return 0;
    }
    private static String[] parse(String v) {
        if (!v.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) throw new IllegalArgumentException("Invalid stable version");
        return v.split("\\.");
    }
    public static String fromTag(String tag) {
        if (!tag.startsWith("quietguard-v")) return null;
        String v = tag.substring("quietguard-v".length());
        try { parse(v); return v; } catch (IllegalArgumentException e) { return null; }
    }
}
