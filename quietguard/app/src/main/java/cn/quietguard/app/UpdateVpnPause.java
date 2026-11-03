package cn.quietguard.app;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.VpnService;
import android.os.*;

/** Pauses only our tunnel; keeps the existing foreground service available to restore it. */
final class UpdateVpnPause {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static long activeCheck, monitored = -1, lastBytes, lastProgress;
    private static final Runnable monitor = new Runnable() {
        @Override public void run() {
            Context context = app;
            if (context != null) inspectDownload(context, true);
        }
    };
    @android.annotation.SuppressLint("StaticFieldLeak") // Stores only getApplicationContext(), never an Activity or service.
    private static Context app;
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("update_vpn_pause", Context.MODE_PRIVATE); }
    private static UpdateVpnState read(Context c) {
        SharedPreferences p = prefs(c); UpdateVpnState s = new UpdateVpnState();
        s.paused = p.getBoolean("paused", false); s.restore = p.getBoolean("restore", false);
        s.checking = p.getBoolean("checking", false); s.installing = p.getBoolean("installing", false);
        s.checkToken = p.getLong("checkToken", 0); s.downloadId = p.getLong("downloadId", -1); s.installCode = p.getLong("installCode", 0);
        return s;
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Must persist before disconnecting a tunnel or launching an installer.
    private static void save(Context c, UpdateVpnState s) {
        if (!prefs(c).edit().putBoolean("paused", s.paused).putBoolean("restore", s.restore).putBoolean("checking", s.checking)
                .putBoolean("installing", s.installing).putLong("checkToken", s.checkToken).putLong("downloadId", s.downloadId)
                .putLong("installCode", s.installCode).commit()) throw new IllegalStateException("无法保存更新前的 VPN 状态，已停止更新");
    }
    static boolean isPaused(Context c) { return read(c).paused; }
    static boolean needsRestore(Context c) { UpdateVpnState s = read(c); return s.restore && !s.paused; }
    static long beginCheck(Context c) {
        if (activeCheck != 0 || read(c).downloadId != -1) throw new IllegalStateException("已有更新任务，请稍后再试");
        UpdateVpnState s = read(c); if (!Prefs.enabled(c)) s.userDisabled();
        long token = Math.max(1, System.nanoTime()); s.beginCheck(token, FilterVpnService.running); save(c, s);
        activeCheck = token;
        try { FilterVpnService.pauseForUpdate(); }
        catch (RuntimeException e) { endCheck(c, token); throw e; }
        Context application = c.getApplicationContext();
        handler.postDelayed(() -> { if (read(application).checkToken == token) endCheck(application, token); }, 30000);
        return token;
    }
    static void endCheck(Context c, long token) {
        UpdateVpnState s = read(c); s.endCheck(token); save(c, s);
        if (activeCheck == token) activeCheck = 0;
        restore(c);
    }
    static void beginDownload(Context c) {
        UpdateVpnState s = read(c); if (!Prefs.enabled(c)) s.userDisabled();
        s.beginDownload(FilterVpnService.running); save(c, s);
        try { FilterVpnService.pauseForUpdate(); }
        catch (RuntimeException e) { abortPreparing(c); throw e; }
        prefs(c).edit().remove("problem").apply();
    }
    static void attachDownload(Context c, long id) {
        UpdateVpnState s = read(c); s.attachDownload(id); save(c, s);
        app = c.getApplicationContext(); monitored = id; lastBytes = 0; lastProgress = SystemClock.elapsedRealtime();
        handler.removeCallbacks(monitor); handler.postDelayed(monitor, 1000);
    }
    static void finishDownload(Context c, long id) {
        UpdateVpnState s = read(c); s.endDownload(id); save(c, s);
        if (monitored == id) { handler.removeCallbacks(monitor); monitored = -1; }
        restore(c);
    }
    static void abortPreparing(Context c) { finishDownload(c, -2); }
    static String problem(Context c) { return prefs(c).getString("problem", "下载任务已被系统移除，请重新下载"); }
    static void downloadBroadcast(Context c, long id) { if (id == read(c).downloadId) inspectDownload(c, false); }
    private static void inspectDownload(Context c, boolean schedule) {
        UpdateVpnState state = read(c); long id = state.downloadId;
        if (id < 0) return;
        DownloadManager manager = c.getSystemService(DownloadManager.class);
        try {
            if (manager == null) { finishDownload(c, id); return; }
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) { finishDownload(c, id); return; }
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) { finishDownload(c, id); return; }
                long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long now = SystemClock.elapsedRealtime();
                if (monitored != id || bytes > lastBytes) { monitored = id; lastBytes = bytes; lastProgress = now; }
                if (now - lastProgress >= 60000) {
                    prefs(c).edit().putString("problem", "下载连续 60 秒没有进展，已取消并结束 VPN 暂停。可重新下载").apply();
                    manager.remove(id); finishDownload(c, id); return;
                }
            }
        } catch (RuntimeException e) {
            // A provider error must not leave protection paused indefinitely.
            prefs(c).edit().putString("problem", "无法读取下载状态，已结束 VPN 暂停，请重新下载").apply();
            try { if (manager != null) manager.remove(id); } catch (RuntimeException ignored) { }
            finishDownload(c, id); return;
        }
        if (schedule) { handler.removeCallbacks(monitor); handler.postDelayed(monitor, 1000); }
    }
    static void recover(Context c) {
        app = c.getApplicationContext(); UpdateVpnState s = read(c);
        if (s.checking && s.checkToken != activeCheck) s.endCheck(s.checkToken);
        if (s.downloadId == -2) s.endDownload(-2);
        // Returning to the app means installation was cancelled or completed; either way restore intent persists.
        if (s.installing) s.installFinished();
        save(c, s);
        if (s.downloadId >= 0) inspectDownload(c, true);
        restore(c);
    }
    static void beforeInstall(Context c, long targetCode) {
        UpdateVpnState s = read(c); s.installStarted(FilterVpnService.running, targetCode); save(c, s);
        FilterVpnService.pauseForUpdate();
    }
    static void packageReplaced(Context c) {
        UpdateVpnState s = read(c);
        if (s.installing && BuildConfig.VERSION_CODE >= s.installCode) {
            s.installFinished(); if (s.checking) s.endCheck(s.checkToken);
            if (s.downloadId != -1) s.endDownload(s.downloadId);
            save(c, s); restore(c);
        }
    }
    static void userDisabled(Context c) {
        UpdateVpnState s = read(c); s.userDisabled(); save(c, s);
    }
    static void vpnStarted(Context c) {
        UpdateVpnState s = read(c); if (!s.paused) { s.restored(); save(c, s); }
    }
    private static void restore(Context c) {
        UpdateVpnState s = read(c);
        if (s.paused) return;
        if (!Prefs.enabled(c) || Prefs.selected(c).isEmpty()) { s.userDisabled(); save(c, s); }
        if (!s.restore) { FilterVpnService.stopUpdatePause(); return; }
        if (FilterVpnService.running) { vpnStarted(c); return; }
        if (!s.canRestore(Prefs.enabled(c), !Prefs.selected(c).isEmpty(), VpnService.prepare(c) == null)) {
            FilterVpnService.stopUpdatePause();
            FilterVpnService.status = "更新已结束，VPN 需要重新授权，请点击恢复 VPN"; return;
        }
        if (FilterVpnService.resumeAfterUpdate()) return;
        try { c.startForegroundService(new Intent(c, FilterVpnService.class)); }
        catch (RuntimeException e) { FilterVpnService.status = "更新已结束，请返回净屏恢复 VPN"; }
    }
}
