package cn.quietguard.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Optional persistent, explicit ADB authorization. No root, shell process or background enabler. */
final class AccessibilitySetup {
    static boolean authorized(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean enable(Context context) {
        if (!authorized(context) || !Prefs.get(context).getBoolean("skip_consent",false)) return false;
        ComponentName service = new ComponentName(context,SplashSkipService.class);
        String full=service.flattenToString(), shortName=service.flattenToShortString();
        String before=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String next=AccessibilitySelection.add(before,full,shortName);
        if (!next.equals(before) && !Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,next)) return false;
        if (!Settings.Secure.putInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1)) return false;
        return Settings.Secure.getInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0)==1
            && AccessibilitySelection.contains(Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),full,shortName);
    }
}
