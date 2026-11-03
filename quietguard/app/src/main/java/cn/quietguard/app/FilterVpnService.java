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
    public static volatile String status = "过滤未运行";
    public static final AtomicLong queries = new AtomicLong(), blocked = new AtomicLong(), failed = new AtomicLong();
    public static final String STOP = "cn.quietguard.STOP";
    private Session session;
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("filter", "广告过滤服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示过滤运行状态，并提供停止按钮");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, FilterVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "filter").setSmallIcon(cn.quietguard.app.R.drawable.ic_shield)
            .setContentTitle("净屏 · 本地广告过滤").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "停止过滤", stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopFilter("过滤已停止"); stopSelf(); return START_NOT_STICKY; }
        try {
            Notification n = notification("正在配置过滤…");
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            else startForeground(7, n);
            closeSession();
            Set<String> selected = Prefs.selected(this);
            if (selected.isEmpty()) { stopFilter("请先选择需要过滤的应用"); stopSelf(); return START_NOT_STICKY; }
            if (VpnService.prepare(this) != null) throw new IllegalStateException("VPN 授权已失效，请重新启动");
            RuleSet rules = new RuleSet(getAssets().open("ad-domains.txt"), Prefs.get(this).getString("exceptions", ""));
            Builder builder = new Builder().setSession("净屏 · 按应用广告过滤").setMtu(32767)
                .addAddress("10.77.0.1", 32).addDnsServer("10.77.0.2").addRoute("10.77.0.2", 32)
                .allowFamily(OsConstants.AF_INET).allowFamily(OsConstants.AF_INET6)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            int count = 0;
            for (String pkg : selected) {
                if (pkg.equals(getPackageName())) continue;
                try { builder.addAllowedApplication(pkg); count++; } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
            }
            if (count == 0) throw new IllegalStateException("选中的应用已卸载，请刷新列表");
            ParcelFileDescriptor tunnel = builder.establish();
            if (tunnel == null) throw new IOException("系统未建立 VPN，请检查授权");
            String resolver = Prefs.get(this).getString("resolver", "223.5.5.5");
            if (!Arrays.asList("223.5.5.5", "119.29.29.29", "1.1.1.1").contains(resolver)) resolver = "223.5.5.5";
            try { session = new Session(tunnel, rules, resolver); } catch (Exception e) { tunnel.close(); throw e; }
            queries.set(0); blocked.set(0); failed.set(0);
            running = true; status = "正在过滤 " + count + " 个应用";
            getSystemService(NotificationManager.class).notify(7, notification(status));
            session.start();
        } catch (Exception e) { stopFilter("启动失败：" + readable(e)); stopSelf(); }
        return START_NOT_STICKY;
    }
    private static String readable(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private void closeSession() { running = false; if (session != null) { session.close(); session = null; } }
    private void stopFilter(String text) { closeSession(); status = text; stopForeground(STOP_FOREGROUND_REMOVE); }
    @Override public void onRevoke() { stopFilter("VPN 授权已撤销或被其他 VPN 替换"); stopSelf(); super.onRevoke(); }
    @Override public void onDestroy() { closeSession(); if (status.startsWith("正在过滤")) status = "过滤已停止"; super.onDestroy(); }

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
