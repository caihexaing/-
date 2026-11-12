package cn.quietguard.app;

import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import hev.htproxy.TProxyService;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class SniTunnelSession implements AutoCloseable {
    interface Listener { void onReady(); void onFailure(String reason); }
    private final VpnService service;
    private final ParcelFileDescriptor tunnel;
    private final RuleSet rules;
    private final String resolver;
    private final boolean blockDnsBypass;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private EnhancedSocks5Proxy proxy;
    private File config;
    private Thread nativeThread;
    private volatile boolean startReturned;
    private volatile long startReturnTime;

    SniTunnelSession(VpnService service, ParcelFileDescriptor tunnel, RuleSet rules, String resolver, boolean blockDnsBypass, Listener listener) {
        this.service=service; this.tunnel=tunnel; this.rules=rules; this.resolver=resolver; this.blockDnsBypass=blockDnsBypass; this.listener=listener;
    }
    void start() {
        try {
            proxy = new EnhancedSocks5Proxy(service, rules, resolver, blockDnsBypass);
            config = new File(service.getCacheDir(), "quietguard-sni-tunnel.yaml");
            String yaml = "tunnel:\n  name: tun0\n  mtu: 1500\n  ipv4: 10.77.0.1\n  ipv6: 'fd77:77::1'\n  icmp: 'off'\nsocks5:\n  address: 127.0.0.1\n  port: " + proxy.port() + "\n  udp: 'udp'\n";
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(config), java.nio.charset.StandardCharsets.UTF_8)) { writer.write(yaml); }
        } catch (Exception e) { fail("本地转发器启动失败：" + readable(e)); return; }

        nativeThread = new Thread(() -> {
            if (!alive.get()) { startReturned = true; startReturnTime = System.currentTimeMillis(); return; }
            try { TProxyService.TProxyStartService(config.getAbsolutePath(), tunnel.getFd()); }
            catch (Throwable e) { if (alive.get()) fail("隧道引擎启动失败：" + readable(e)); }
            finally { startReturned = true; startReturnTime = System.currentTimeMillis(); }
        }, "quietguard-hev-tunnel");
        nativeThread.setDaemon(true); nativeThread.start();
        Thread watcher = new Thread(this::watchStartup, "quietguard-hev-watch"); watcher.setDaemon(true); watcher.start();
    }
    private void watchStartup() {
        long deadline = System.currentTimeMillis() + 8000;
        boolean announced = false;
        while (alive.get()) {
            try {
                if (TProxyService.TProxyIsRunning()) {
                    if (!announced) { announced = true; main.post(() -> { if (alive.get()) listener.onReady(); }); }
                } else if (announced) { fail("SNI 隧道意外停止"); return; }
            } catch (Throwable e) { fail("隧道状态读取失败：" + readable(e)); return; }
            if (!announced && startReturned && System.currentTimeMillis() - startReturnTime > 1000) {
                fail("隧道引擎未能建立 TUN 转发"); return;
            }
            if (!announced && System.currentTimeMillis() >= deadline) { fail("隧道引擎启动超时"); return; }
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
    private void fail(String reason) { if (alive.get()) main.post(() -> { if (alive.get()) listener.onFailure(reason); }); }
    @Override public void close() {
        if (!alive.compareAndSet(true,false)) return;
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) { }
        if (nativeThread != null && nativeThread.isAlive()) main.postDelayed(() -> {
            try { if (nativeThread != null && nativeThread.isAlive()) TProxyService.TProxyStopService(); } catch (Throwable ignored) { }
        }, 250);
        if (proxy != null) proxy.close();
        try { tunnel.close(); } catch (IOException ignored) { }
        if (config != null) config.delete();
    }
    private static String readable(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
