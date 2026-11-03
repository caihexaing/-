package cn.quietguard.app;

import android.content.*;

/** Restores the recorded VPN intent after replacing this app, without launching a UI. */
public final class UpdatePackageReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) UpdateVpnPause.packageReplaced(context);
    }
}
