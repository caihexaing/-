package cn.quietguard.app;

import android.app.DownloadManager;
import android.content.*;

/** Exported for the system download provider; never trusts an external completion claim. */
public final class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction()))
            UpdateVpnPause.downloadBroadcast(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1));
    }
}
