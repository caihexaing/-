package cn.quietguard.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Activity-bound view of a system-owned download; metadata survives activity/process recreation. */
final class InAppUpdater {
    private final Activity activity;
    private final Context context;
    private final DownloadManager manager;
    private final SharedPreferences saved;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::refresh;
    private AlertDialog dialog;
    private ProgressBar progress;
    private TextView detail, transfer;
    private UpdateChecker.Release release;
    private long id = -1, received, total = -1, sampleTime, sampleBytes, speed;
    private String filename, message = "准备下载…";
    private File ready;
    private boolean resumed, closed, failed, verifying, installRequested, downloaded;
    private volatile long generation;
    private Future<?> verification;

    InAppUpdater(Activity activity) {
        this.activity = activity; context = activity.getApplicationContext();
        manager = context.getSystemService(DownloadManager.class);
        saved = context.getSharedPreferences("update_download", Context.MODE_PRIVATE);
        id = saved.getLong("id", -1);
        if (id >= 0) try {
            release = new UpdateChecker.Release(saved.getString("version", ""), saved.getLong("code", 0), "",
                saved.getString("url", ""), saved.getString("sha256", ""));
            filename = saved.getString("file", "");
            if (UpdateVersion.fromTag("quietguard-v" + release.version) == null || !UpdateIntegrity.validHash(release.sha256)
                    || !UpdateIntegrity.validApkUrl(UpdateChecker.REPO, release.version, release.apkUrl)
                    || !filename.matches("QuietGuard-[0-9.]+-[a-f0-9-]+\\.apk")) throw new IllegalArgumentException();
            if (release.versionCode <= BuildConfig.VERSION_CODE) clear();
        } catch (RuntimeException e) { clear(); }
    }
    boolean showExisting() {
        if (id < 0 || release == null) return false;
        showDialog(); refresh(); return true;
    }
    boolean hasPending() { return id >= 0 && release != null; }
    void start(UpdateChecker.Release target) {
        if (showExisting()) return;
        begin(target);
    }
    private void begin(UpdateChecker.Release target) {
        release = target; failed = false; verifying = false; downloaded = false; ready = null; received = 0; total = -1;
        sampleTime = 0; sampleBytes = 0; speed = 0; message = "正在连接下载服务器…";
        try {
            if (manager == null) throw new IOException("系统下载服务不可用");
            File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("下载存储空间不可用");
            filename = "QuietGuard-" + target.version + "-" + UUID.randomUUID() + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(target.apkUrl))
                .setTitle("净屏 v" + target.version + " 更新")
                .setDescription("返回净屏查看下载进度并安装")
                .setMimeType(UpdateApkProvider.MIME).setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename);
            request.addRequestHeader("User-Agent", "QuietGuard/" + BuildConfig.VERSION_NAME);
            id = manager.enqueue(request);
            if (!saved.edit().putLong("id", id).putString("version", target.version).putLong("code", target.versionCode)
                .putString("url", target.apkUrl).putString("sha256", target.sha256).putString("file", filename).commit()) {
                manager.remove(id); throw new IOException("无法保存下载任务，请重试");
            }
        } catch (Exception e) { failed = true; message = "无法开始下载：" + readable(e); }
        showDialog(); if (!failed) refresh();
    }
    void resume() {
        resumed = true;
        if (saved.getBoolean("waiting_permission", false)) {
            saved.edit().putBoolean("waiting_permission", false).apply();
            installRequested = activity.getPackageManager().canRequestPackageInstalls();
            if (!installRequested) message = "安装权限尚未开启，可点击“安装更新”重试";
        }
        if (id >= 0 && release != null) {
            showDialog(); refresh();
            if (installRequested && ready != null) install();
        }
    }
    void pause() { resumed = false; handler.removeCallbacks(poll); }
    void close() {
        closed = true; generation++; pause();
        if (verification != null) verification.cancel(true);
        worker.shutdownNow(); if (dialog != null) dialog.dismiss();
    }
    private void showDialog() {
        if (closed || !resumed || activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) { render(); return; }
        LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24); panel.setPadding(pad, dp(12), pad, dp(12));
        detail = new TextView(activity); detail.setTextSize(15); detail.setTextColor(0xff192c32); panel.addView(detail);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(18)); lp.topMargin = dp(16); lp.bottomMargin = dp(10); panel.addView(progress, lp);
        transfer = new TextView(activity); transfer.setTextSize(13); transfer.setTextColor(0xff687a80); panel.addView(transfer);
        dialog = new AlertDialog.Builder(activity).setTitle("净屏 v" + release.version + " 更新").setView(panel)
            .setCancelable(false).setPositiveButton("安装更新", null).setNeutralButton("收起", (d,w) -> { })
            .setNegativeButton("取消下载", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { clear(); dialog.dismiss(); });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (failed) { UpdateChecker.Release target = release; clear(); dialog.dismiss(); begin(target); }
                else if (ready != null) { installRequested = true; install(); }
            });
            render();
        });
        dialog.show();
    }
    private void render() {
        if (dialog == null || !dialog.isShowing()) return;
        boolean complete = downloaded;
        detail.setText(message);
        progress.setIndeterminate(!failed && !complete && total <= 0);
        progress.setProgress(UpdateIntegrity.progress(received, total, complete));
        String bytes = formatBytes(received) + (total > 0 ? " / " + formatBytes(total) : " / 大小待确认");
        String percentage = complete ? "100%" : total > 0 ? UpdateIntegrity.progress(received,total,false) + "%" : "";
        transfer.setText(activity.getString(R.string.update_transfer, percentage, bytes,
            !complete && !failed && speed > 0 ? "\n" + formatBytes(speed) + "/s" : ""));
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positive.setText(failed ? "重新下载" : "安装更新"); positive.setVisibility(failed || ready != null ? View.VISIBLE : View.GONE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(ready != null ? "删除安装包" : "取消下载");
    }
    private void refresh() {
        handler.removeCallbacks(poll);
        if (closed || !resumed || id < 0 || failed) { render(); return; }
        if (ready != null && ready.isFile()) { render(); return; }
        ready = null;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("下载任务已被系统移除，请重新下载");
            received = Math.max(0, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
            total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (received > UpdateIntegrity.MAX_BYTES || total > UpdateIntegrity.MAX_BYTES) { manager.remove(id); throw new IOException("安装包超过允许的大小"); }
            long now = SystemClock.elapsedRealtime();
            if (sampleTime > 0 && now > sampleTime) speed = Math.max(0,(received - sampleBytes) * 1000 / (now - sampleTime));
            sampleTime = now; sampleBytes = received;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                downloaded = true;
                if (!verifying) verify(); render(); return;
            }
            if (status == DownloadManager.STATUS_FAILED) throw new IOException(failureReason(reason));
            if (status == DownloadManager.STATUS_PAUSED) {
                message = reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK ? "等待网络连接，恢复后继续下载"
                    : reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI ? "等待 Wi-Fi 连接" : "系统暂停下载，正在等待重试";
            } else message = status == DownloadManager.STATUS_PENDING ? "下载已排队，等待系统开始…" : "正在应用内下载更新…";
            render(); handler.postDelayed(poll, 500);
        } catch (Exception e) { failed = true; message = "下载失败：" + readable(e); render(); }
    }
    private void verify() {
        verifying = true; message = "下载完成，正在校验文件与签名…"; render();
        final long token = generation, task = id;
        final UpdateChecker.Release target = release;
        final String sourceName = filename;
        verification = worker.submit(() -> {
            File temp = null, output = cacheApk(target.version, task); String error = null;
            try {
                File directory = output.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建安装缓存");
                temp = File.createTempFile("verify-", ".apk", directory);
                File sourceDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (sourceDirectory == null) throw new IOException("下载文件存储不可用");
                try (InputStream input = new FileInputStream(new File(sourceDirectory, sourceName)); OutputStream out = new FileOutputStream(temp)) {
                    UpdateIntegrity.copyAndCheck(input, out, target.sha256, UpdateIntegrity.MAX_BYTES);
                }
                validatePackage(temp, target);
                if (Thread.currentThread().isInterrupted() || generation != token) throw new InterruptedIOException("校验已取消");
                if (output.exists() && !output.delete()) throw new IOException("无法替换安装缓存");
                if (!temp.renameTo(output)) throw new IOException("无法保存已校验的安装包");
                temp = null;
            } catch (Exception e) { error = readable(e); }
            finally { if (temp != null) temp.delete(); }
            final String problem = error;
            handler.post(() -> {
                // A recreated activity may already be verifying the same task. Do not delete its shared output.
                if (closed) return;
                if (token != generation || task != id) { output.delete(); return; }
                verifying = false;
                if (problem != null) { failed = true; message = "校验失败：" + problem; }
                else { ready = output; received = output.length(); total = received; message = "下载和校验完成，点击“安装更新”继续"; }
                render(); if (ready != null && installRequested && resumed) install();
            });
        });
    }
    @SuppressWarnings("deprecation")
    private void validatePackage(File file, UpdateChecker.Release target) throws Exception {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo incoming = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo current = pm.getPackageInfo(context.getPackageName(), flags);
        if (incoming == null || !context.getPackageName().equals(incoming.packageName)) throw new IOException("安装包不属于净屏");
        long code = Build.VERSION.SDK_INT >= 28 ? incoming.getLongVersionCode() : incoming.versionCode;
        if (code != target.versionCode || code <= BuildConfig.VERSION_CODE || !target.version.equals(incoming.versionName)) throw new IOException("安装包版本与发布信息不一致");
        Signature[] a = Build.VERSION.SDK_INT >= 28 && incoming.signingInfo != null ? incoming.signingInfo.getApkContentsSigners() : incoming.signatures;
        Signature[] b = Build.VERSION.SDK_INT >= 28 && current.signingInfo != null ? current.signingInfo.getApkContentsSigners() : current.signatures;
        if (a == null || b == null || a.length == 0 || a.length != b.length || !new HashSet<>(Arrays.asList(a)).equals(new HashSet<>(Arrays.asList(b))))
            throw new IOException("安装包签名与当前净屏不一致，已停止安装");
    }
    private void install() {
        if (closed || !resumed) return;
        if (ready == null || !ready.isFile()) { ready = null; refresh(); return; }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            message = "请在系统设置中允许净屏安装应用，然后返回继续"; render();
            saved.edit().putBoolean("waiting_permission", true).apply();
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()))); }
            catch (ActivityNotFoundException | SecurityException e) { installRequested = false; saved.edit().putBoolean("waiting_permission", false).apply(); message = "请在系统应用设置中允许净屏安装应用，再点击安装更新"; render(); }
            return;
        }
        installRequested = false;
        Uri uri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates").appendPath("apk").appendPath(ready.getName()).build();
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, UpdateApkProvider.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("净屏更新", uri));
        try { activity.startActivity(intent); message = "请在系统安装界面确认；取消后可回到这里重试"; }
        catch (ActivityNotFoundException | SecurityException e) { message = "无法打开系统安装程序：" + readable(e); }
        render();
    }
    private void clear() {
        generation++; handler.removeCallbacks(poll);
        if (verification != null) verification.cancel(true);
        if (id >= 0 && manager != null) try { manager.remove(id); } catch (RuntimeException ignored) { }
        if (release != null && id >= 0 && UpdateVersion.fromTag("quietguard-v" + release.version) != null) cacheApk(release.version,id).delete();
        saved.edit().clear().apply(); id = -1; ready = null; verifying = false; failed = false; downloaded = false; installRequested = false;
    }
    private File cacheApk(String version, long task) { return new File(new File(context.getCacheDir(), "updates"), "QuietGuard-" + version + "-" + task + ".apk"); }
    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    private static String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA,"%.1f KB",bytes / 1024.0);
        return String.format(Locale.CHINA,"%.1f MB",bytes / (1024.0 * 1024));
    }
    private static String failureReason(int code) {
        if (code == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "手机剩余空间不足";
        if (code == DownloadManager.ERROR_DEVICE_NOT_FOUND) return "下载存储不可用";
        if (code == DownloadManager.ERROR_CANNOT_RESUME) return "网络中断且无法续传，请重新下载";
        if (code >= 400 && code <= 599) return "服务器返回 HTTP " + code;
        return "系统下载错误（" + code + "），可重新下载";
    }
}
