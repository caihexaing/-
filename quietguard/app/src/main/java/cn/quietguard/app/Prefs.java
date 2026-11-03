package cn.quietguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

final class Prefs {
    static SharedPreferences get(Context c) { return c.getSharedPreferences("quiet_guard", Context.MODE_PRIVATE); }
    static Set<String> selected(Context c) { return new HashSet<>(get(c).getStringSet("apps", new HashSet<>())); }
    static void select(Context c, String name, boolean on) {
        Set<String> apps = selected(c); if (on) apps.add(name); else apps.remove(name);
        get(c).edit().putStringSet("apps", apps).apply();
    }
}
