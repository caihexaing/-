package cn.quietguard.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.*;
import android.system.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class FilterVpnService extends VpnService {
    public static volatile boolean running;
    public static volatile Set<String> filteredApps = Collections.emptySet();
    public static volatile String status = "过滤未运行";
    public static final AtomicLong queries = new AtomicLong(), blocked = new AtomicLong(), failed = new AtomicLong(), sniBlocked = new AtomicLong(), quicBlocked = new AtomicLong();
    public static final String STOP = "cn.quietguard.STOP";
    private static FilterVpnService instance;
    private boolean pausedForUpdate;
    private Session session;
    private SniTunnelSession sniSession;
    @Override public void onCreate() {
        super.onCreate(); instance = this;
        NotificationChannel channel = new NotificationChannel("filter", "广告过滤服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示过滤运行状态，并提供停止按钮");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FilterVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "filter").setSmallIcon(cn.quietguard.app.R.drawable.ic_shield)
            .setContentTitle("净屏 · 本地广告过滤").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "暂停全部防护", stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { Prefs.enabled(this, false); UpdateVpnPause.userDisabled(this); stopFilter("防护已暂停"); stopSelf(); return START_NOT_STICKY; }
        if (!Prefs.enabled(this)) { stopSelf(); return START_NOT_STICKY; }
        if (UpdateVpnPause.isPaused(this)) { enterUpdatePause(); return START_NOT_STICKY; }
        startFilter(); return START_NOT_STICKY;
    }
    static void pauseForUpdate() {
        if (instance != null && (instance.session != null || instance.sniSession != null)) instance.enterUpdatePause();
    }
    // A VPN can remain system-bound after stopService(), so close the tunnel explicitly.
    static void stopFiltering(Context context, String reason) {
        if (instance != null) {
            instance.pausedForUpdate = false;
            instance.stopFilter(reason);
            instance.stopSelf();
        }
        context.stopService(new Intent(context, FilterVpnService.class));
        status = reason;
    }
    private void enterUpdatePause() {
        closeSession(); pausedForUpdate = true; status = "更新期间，净屏 VPN 已暂时断开";
        Notification n = notification(status);
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        else startForeground(7, n);
    }
    static boolean resumeAfterUpdate() {
        if (instance == null || !instance.pausedForUpdate || !Prefs.enabled(instance)) return false;
        instance.startFilter(); return true;
    }
    static void stopUpdatePause() {
        if (instance != null && instance.pausedForUpdate) {
            instance.pausedForUpdate = false; instance.stopFilter("过滤已停止"); instance.stopSelf();
        }
    }
    private void startFilter() {
        if (!Prefs.enabled(this)) { stopFilter("防护已暂停"); stopSelf(); return; }
        Set<String> selected = Prefs.networkSelected(this);
        if (selected.isEmpty()) {
            pausedForUpdate = false; stopFilter("仅开屏跳过，未启用网络过滤"); stopSelf(); return;
        }
        if (UpdateVpnPause.isPaused(this)) { enterUpdatePause(); return; }
        pausedForUpdate = false;
        try {
            Notification n = notification("正在配置过滤…");
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else startForeground(7, n);
            closeSession();
            if (VpnService.prepare(this) != null) throw new IllegalStateException("VPN 授权已失效，请重新启动");
            RuleSet rules = new RuleSet(getAssets().open("ad-domains.txt"), Prefs.get(this).getString("exceptions", ""));
            boolean sniEnabled = Prefs.sniFiltering(this);
            Builder builder = new Builder().setSession(sniEnabled ? "净屏 · SNI 增强过滤" : "净屏 · 按应用广告过滤").setMtu(sniEnabled ? 1500 : 32767)
                .addAddress("10.77.0.1", 32).addDnsServer("10.77.0.2")
                .allowFamily(OsConstants.AF_INET).allowFamily(OsConstants.AF_INET6)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            if (sniEnabled) builder.addAddress("fd77:77::1", 128).addRoute("0.0.0.0", 0).addRoute("::", 0);
            else builder.addRoute("10.77.0.2", 32);
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            Set<String> applied = new HashSet<>();
            for (String pkg : selected) {
                if (pkg.equals(getPackageName())) continue;
                try { builder.addAllowedApplication(pkg); applied.add(pkg); } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            }
            if (applied.isEmpty()) throw new IllegalStateException("选中的应用已卸载，请刷新列表");
            ParcelFileDescriptor tunnel = builder.establish();
            if (tunnel == null) throw new IOException("系统未建立 VPN，请检查授权");
            String resolver = Prefs.get(this).getString("resolver", "223.5.5.5");
            if (!Arrays.asList("223.5.5.5", "119.29.29.29", "1.1.1.1").contains(resolver)) resolver = "223.5.5.5";
            queries.set(0); blocked.set(0); failed.set(0); sniBlocked.set(0); quicBlocked.set(0);
            if (sniEnabled) {
                final Set<String> activeApps = Collections.unmodifiableSet(new HashSet<>(applied));
                status = "SNI 增强隧道启动中…"; getSystemService(NotificationManager.class).notify(7, notification(status));
                final SniTunnelSession[] createdRef = new SniTunnelSession[1];
                SniTunnelSession created = new SniTunnelSession(this, tunnel, rules, resolver, new SniTunnelSession.Listener() {
                    @Override public void onReady() {
                        SniTunnelSession current = createdRef[0];
                        if (sniSession != current || !Prefs.enabled(FilterVpnService.this)) { current.close(); return; }
                        filteredApps = activeApps; running = true; status = "SNI 增强过滤已连接 " + activeApps.size() + " 个应用";
                        UpdateVpnPause.vpnStarted(FilterVpnService.this);
                        getSystemService(NotificationManager.class).notify(7, notification(status));
                    }
                    @Override public void onFailure(String reason) {
                        if (sniSession == createdRef[0]) { stopFilter("SNI 增强启动失败：" + reason); stopSelf(); }
                    }
                });
                createdRef[0] = created;
                sniSession = created; created.start();
            } else {
                try { session = new Session(tunnel, rules, resolver); } catch (Exception e) { tunnel.close(); throw e; }
                filteredApps = Collections.unmodifiableSet(applied);
                running = true; status = "网络过滤已连接 " + applied.size() + " 个应用";
                UpdateVpnPause.vpnStarted(this);
                getSystemService(NotificationManager.class).notify(7, notification(status));
                session.start();
            }
        } catch (Exception e) { stopFilter("启动失败：" + readable(e)); stopSelf(); }
    }
    private static String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void closeSession() {
        running = false; filteredApps = Collections.emptySet();
        if (sniSession != null) { sniSession.close(); sniSession = null; }
        if (session != null) { session.close(); session = null; }
    }
    private void stopFilter(String text) { closeSession(); status = text; stopForeground(STOP_FOREGROUND_REMOVE); }
    @Override public void onRevoke() { stopFilter("VPN 授权已撤销或被其他 VPN 替换"); stopSelf(); super.onRevoke(); }
    @Override public void onDestroy() { closeSession(); if (instance == this) instance = null; if (status.startsWith("网络过滤已连接") || status.startsWith("SNI 增强过滤已连接")) status = "过滤已停止"; super.onDestroy(); }

    private final class Session implements AutoCloseable {
        final ParcelFileDescriptor tunnel;
        final FileInputStream input;
        final FileOutputStream output;
        final RuleSet rules;
        final InetAddress resolver;
        final Set<DatagramSocket> sockets = ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        volatile boolean alive = true;
        Session(ParcelFileDescriptor fd, RuleSet rules, String resolver) throws Exception {
            this.tunnel = fd; this.rules = rules; this.resolver = InetAddress.getByName(resolver);
            input = new FileInputStream(fd.getFileDescriptor()); output = new FileOutputStream(fd.getFileDescriptor());
        }
        void start() { Thread reader = new Thread(this::readLoop, "quietguard-tun"); reader.setDaemon(true); reader.start(); }
        void readLoop() {
            byte[] buffer = new byte[32767]; StructPollfd poll = new StructPollfd();
            poll.fd = tunnel.getFileDescriptor(); poll.events = (short)OsConstants.POLLIN;
            try {
                while (alive) {
                    if (Os.poll(new StructPollfd[]{poll}, 250) <= 0 || (poll.revents & OsConstants.POLLIN) == 0) continue;
                    int n = input.read(buffer); if (n <= 0) continue;
                    DnsPacket packet = DnsPacket.parse(buffer, n); if (packet == null) continue;
                    String domain = DnsPacket.domain(packet.dns); if (domain == null) continue;
                    queries.incrementAndGet();
                    if (rules.blocks(domain)) { blocked.incrementAndGet(); send(packet, DnsPacket.error(packet.dns, 3)); }
                    else try { pool.execute(() -> forward(packet)); }
                    catch (RejectedExecutionException e) { failed.incrementAndGet(); send(packet, DnsPacket.error(packet.dns, 2)); }
                }
            } catch (Exception e) {
                if (alive) new Handler(Looper.getMainLooper()).post(() -> {
                    if (session == this) { stopFilter("隧道已停止，请重新启动过滤"); stopSelf(); }
                });
            }
        }
        void forward(DnsPacket packet) {
            if (!alive) return;
            try (DatagramSocket socket = new DatagramSocket()) {
                sockets.add(socket);
                try {
                    if (!alive) return;
                    if (!protect(socket)) throw new IOException("无法保护 DNS 套接字");
                    socket.setSoTimeout(3500); socket.connect(resolver, 53);
                    socket.send(new DatagramPacket(packet.dns, packet.dns.length));
                    byte[] answer = new byte[32000]; DatagramPacket reply = new DatagramPacket(answer, answer.length); socket.receive(reply);
                    byte[] dns = Arrays.copyOf(answer, reply.getLength());
                    if (!DnsPacket.validReply(packet.dns, dns)) throw new IOException("无效 DNS 响应");
                    send(packet, dns);
                } finally { sockets.remove(socket); }
            } catch (Exception e) { if (alive) { failed.incrementAndGet(); send(packet, DnsPacket.error(packet.dns, 2)); } }
        }
        synchronized void send(DnsPacket packet, byte[] dns) {
            if (!alive || dns == null) return;
            try { output.write(packet.response(dns)); } catch (IOException ignored) { }
        }
        @Override public void close() {
            alive = false; pool.shutdownNow(); for (DatagramSocket socket : sockets) socket.close();
            try { tunnel.close(); } catch (IOException ignored) { }
        }
    }
}
