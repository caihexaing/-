package cn.quietguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    static void migrateModes(Context c) {
        if (get(c).getBoolean("mode_migrated_v2", false)) return;
        Set<String> configured = new HashSet<>(get(c).getStringSet("mode_configured_apps", new HashSet<>()));
        configured.addAll(selected(c));
        configured.addAll(get(c).getStringSet("skip_only_apps", new HashSet<>()));
        configured.addAll(get(c).getStringSet("skip_plain_apps", new HashSet<>()));
        configured.addAll(get(c).getStringSet("skip_text_tap_apps", new HashSet<>()));
        get(c).edit().putStringSet("mode_configured_apps", configured).putBoolean("mode_migrated_v2", true).apply();
    }
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
        Set<String> configured = new HashSet<>(get(c).getStringSet("mode_configured_apps", new HashSet<>())); configured.add(pkg);
        get(c).edit().putStringSet("skip_only_apps", apps).putStringSet("mode_configured_apps", configured).apply();
    }
    static ProtectionControl control(Context c) {
        return new ProtectionControl(get(c).getBoolean("protection_enabled", false), get(c).getLong("control_revision", 0));
    }
    static boolean enabled(Context c) { return control(c).enabled; }
    static void enabled(Context c, boolean on) {
        ProtectionControl state = control(c); state.request(on);
        get(c).edit().putBoolean("protection_enabled", state.enabled).putLong("control_revision", state.revision).apply();
    }
    static Set<String> exemptions(Context c) { return new HashSet<>(get(c).getStringSet("exempt_apps", new HashSet<>())); }
    static boolean conservative(Context c) { return get(c).getBoolean("conservative_new_apps", true); }
    static void exempt(Context c, String pkg, boolean on) {
        Set<String> exempt = exemptions(c), selected = selected(c);
        if (on) { exempt.add(pkg); selected.remove(pkg); } else exempt.remove(pkg);
        get(c).edit().putStringSet("exempt_apps", exempt).putStringSet("apps", selected).apply();
    }
    static boolean plainSkip(Context c, String pkg) {
        return get(c).getStringSet("skip_plain_apps", new HashSet<>()).contains(pkg);
    }
    static void plainSkip(Context c, String pkg, boolean on) {
        Set<String> apps = new HashSet<>(get(c).getStringSet("skip_plain_apps", new HashSet<>()));
        if (on) apps.add(pkg); else apps.remove(pkg);
        get(c).edit().putStringSet("skip_plain_apps", apps).apply();
    }
    static boolean textTap(Context c,String pkg) {
        return get(c).getStringSet("skip_text_tap_apps",new HashSet<>()).contains(pkg);
    }
    static void textTap(Context c,String pkg,boolean on) {
        Set<String> apps=new HashSet<>(get(c).getStringSet("skip_text_tap_apps",new HashSet<>()));
        if (on) apps.add(pkg); else apps.remove(pkg);
        get(c).edit().putStringSet("skip_text_tap_apps",apps).apply();
    }
    static void select(Context c, String name, boolean on) {
        Set<String> apps = selected(c);
        if (on && exemptions(c).contains(name)) return;
        SharedPreferences.Editor editor = get(c).edit();
        if (on) {
            if (apps.add(name) && conservative(c) && !get(c).getStringSet("mode_configured_apps", new HashSet<>()).contains(name)) {
                Set<String> skip = new HashSet<>(get(c).getStringSet("skip_only_apps", new HashSet<>()));
                skip.add(name); editor.putStringSet("skip_only_apps", skip);
            }
        } else apps.remove(name);
        editor.putStringSet("apps", apps).apply();
    }
}
