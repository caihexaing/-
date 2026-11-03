package cn.quietguard.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** User-initiated direct transfer, owned by a foreground service rather than an Activity. */
public final class UpdateDownloadService extends Service {
    static final String CANCEL = "cn.quietguard.CANCEL_UPDATE";
    private static final int NOTIFICATION = 18;
    private static volatile UpdateDownloadService owner;
    static volatile Snapshot snapshot;
    static final class Snapshot {
        final long id, received, total;
        final String message;
        final boolean complete, failed;
        Snapshot(long id, long received, long total, String message, boolean complete, boolean failed) {
            this.id = id; this.received = received; this.total = total; this.message = message;
            this.complete = complete; this.failed = failed;
        }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "quietguard-update"); t.setDaemon(true); return t;
    });
    private final UpdateHttpDownloader downloader = new UpdateHttpDownloader();
    private long task = -1, lastNotification;
    private volatile boolean stopped;
    private String version;
    private Future<?> job;
    private final Runnable deadline = () -> fail("下载超过 65 秒仍未完成，已停止等待。请确认校园网已登录，或切换手机热点/移动网络后重试");

    static File source(Context context, long id) { return new File(new File(context.getFilesDir(), "update-downloads"), id + ".download"); }
    static void start(Context context, long id) {
        snapshot = new Snapshot(id, 0, -1, "正在启动直连下载…", false, false);
        context.startForegroundService(new Intent(context, UpdateDownloadService.class).putExtra("id", id));
    }
    static void cancel(Context context, long id) {
        UpdateDownloadService service = owner;
        if (service != null && service.task == id) service.halt();
        context.stopService(new Intent(context, UpdateDownloadService.class));
        if (snapshot != null && snapshot.id == id) snapshot = null;
        source(context, id).delete();
    }
    @Override public void onCreate() {
        super.onCreate(); owner = this;
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("updates", "应用更新下载", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) { fail("下载已取消，可返回净屏重新下载"); return START_NOT_STICKY; }
        if (job != null) return START_NOT_STICKY;
        task = intent == null ? -1 : intent.getLongExtra("id", -1);
        try {
            android.content.SharedPreferences saved = getSharedPreferences("update_download", MODE_PRIVATE);
            if (task < 0 || saved.getLong("id", -2) != task) throw new IOException("下载任务已失效");
            version = saved.getString("version", "");
            UpdateChecker.Release release = new UpdateChecker.Release(version, saved.getLong("code", 0), "",
                saved.getString("url", ""), saved.getString("sha256", ""));
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification("正在直连下载服务器…", 0, -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION, notification("正在直连下载服务器…", 0, -1));
            handler.postDelayed(deadline, 65000);
            job = worker.submit(() -> download(release));
        } catch (Exception e) { fail("无法启动下载：" + readable(e)); }
        return START_NOT_STICKY;
    }
    @android.annotation.SuppressLint("ApplySharedPref") // Completion must survive process death; this runs on the IO worker.
    private void download(UpdateChecker.Release release) {
        File output = source(this, task), partial = new File(output.getParentFile(), task + ".part");
        try {
            File directory = output.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建下载目录，请检查剩余空间");
            downloader.download(UpdateChecker.REPO, release.version, release.apkUrl, release.sha256, partial, new UpdateHttpDownloader.Listener() {
                private int source;
                @Override public void connecting(int index, String host) {
                    source = index;
                    publish(index == 0 ? "正在连接主下载线路（最长等待 8 秒）…" : "主线路未完成，正在尝试同仓库备用线路…", 0, -1);
                }
                @Override public void progress(long received, long total) {
                    publish((source == 0 ? "主线路" : "备用线路") + "正在下载更新…", received, total);
                }
            });
            synchronized (this) {
                if (stopped) return;
                if (!partial.renameTo(output)) throw new IOException("无法保存下载文件，请检查剩余空间");
                if (!getSharedPreferences("update_download", MODE_PRIVATE).edit().putBoolean("direct_complete", true).commit())
                    throw new IOException("无法保存下载完成状态，请检查手机剩余空间");
                snapshot = new Snapshot(task, output.length(), output.length(), "下载完成，正在准备校验…", true, false);
            }
            handler.post(this::finishService);
        } catch (Exception e) { handler.post(() -> { if (!stopped) fail("下载失败：" + readable(e)); }); }
        finally { partial.delete(); }
    }
    private void publish(String text, long received, long total) {
        synchronized (this) {
            if (stopped) return;
            snapshot = new Snapshot(task, received, total, text, false, false);
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotification >= 700) {
            lastNotification = now;
            handler.post(() -> {
                Snapshot s = snapshot;
                if (!stopped && s != null && s.id == task) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(s.message, s.received, s.total));
            });
        }
    }
    private Notification notification(String text, long received, long total) {
        PendingIntent open = PendingIntent.getActivity(this, 18, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 19, new Intent(this, UpdateDownloadService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "updates").setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("净屏更新" + (version == null ? "" : " v" + version)).setContentText(text).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setProgress(100, UpdateIntegrity.progress(received, total, false), total <= 0)
            .addAction(new Notification.Action.Builder(null, "取消下载", cancel).build()).build();
    }
    private void fail(String reason) {
        if (stopped) return;
        Snapshot s = snapshot;
        snapshot = new Snapshot(task, s != null && s.id == task ? s.received : 0, s != null && s.id == task ? s.total : -1, reason, false, true);
        finishService();
    }
    private synchronized void halt() {
        stopped = true; handler.removeCallbacksAndMessages(null); downloader.cancel();
        if (job != null) job.cancel(true);
    }
    private void finishService() { halt(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    // Android 15 may stop dataSync services when their runtime budget is exhausted.
    @Override public void onTimeout(int startId, int type) { fail("系统停止了后台下载，请返回净屏重试"); }
    @Override public void onDestroy() {
        if (!stopped) {
            Snapshot s = snapshot;
            if (s != null && s.id == task && !s.complete && !s.failed)
                snapshot = new Snapshot(task, s.received, s.total, "下载被系统中断，请点击重新下载", false, true);
        }
        halt(); worker.shutdownNow(); if (owner == this) owner = null; super.onDestroy();
    }
    private static String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
