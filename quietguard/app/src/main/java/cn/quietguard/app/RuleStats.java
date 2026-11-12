package cn.quietguard.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Reads the tiny key=value asset written next to the rule list at build time. */
final class RuleStats {
    final int blocked, exceptions;
    private RuleStats(int blocked, int exceptions) { this.blocked = blocked; this.exceptions = exceptions; }
    static RuleStats parse(InputStream in) {
        if (in == null) return new RuleStats(-1, 0);
        Properties values = new Properties();
        try (InputStream source = in) { values.load(source); }
        catch (IOException | IllegalArgumentException e) { return new RuleStats(-1, 0); }
        return new RuleStats(number(values, "blocked"), number(values, "exceptions"));
    }
    private static int number(Properties values, String key) {
        try { return Integer.parseInt(values.getProperty(key, "-1")); } catch (NumberFormatException e) { return -1; }
    }
    boolean known() { return blocked > 0; }
}
