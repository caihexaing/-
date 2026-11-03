package cn.quietguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    static SharedPreferences get(Context c) { return c.getSharedPreferences("quiet_guard", Context.MODE_PRIVATE); }
    static Set<String> selected(Context c) { return new HashSet<>(get(c).getStringSet("apps", new HashSet<>())); }
    static Set<String> networkSelected(Context c) {
        return ProtectionMode.networkApps(selected(c), get(c).getStringSet("skip_only_apps", new HashSet<>()));
    }
    static boolean skipOnly(Context c, String pkg) {
        return get(c).getStringSet("skip_only_apps", new HashSet<>()).contains(pkg);
    }
    static void skipOnly(Context c, String pkg, boolean on) {
        Set<String> apps = new HashSet<>(get(c).getStringSet("skip_only_apps", new HashSet<>()));
        if (on) apps.add(pkg); else apps.remove(pkg);
        get(c).edit().putStringSet("skip_only_apps", apps).apply();
    }
    static boolean enabled(Context c) { return get(c).getBoolean("protection_enabled", get(c).getBoolean("consent", false)); }
    static void enabled(Context c, boolean on) { get(c).edit().putBoolean("protection_enabled", on).apply(); }
    static boolean plainSkip(Context c, String pkg) {
        return get(c).getStringSet("skip_plain_apps", new HashSet<>()).contains(pkg);
    }
    static void plainSkip(Context c, String pkg, boolean on) {
        Set<String> apps = new HashSet<>(get(c).getStringSet("skip_plain_apps", new HashSet<>()));
        if (on) apps.add(pkg); else apps.remove(pkg);
        get(c).edit().putStringSet("skip_plain_apps", apps).apply();
    }
    static void select(Context c, String name, boolean on) {
        Set<String> apps = selected(c); if (on) apps.add(name); else apps.remove(name);
        get(c).edit().putStringSet("apps", apps).apply();
    }
}
