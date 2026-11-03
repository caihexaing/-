package cn.quietguard.app;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.SSLException;

/** Bounded, cancellable HTTPS transfer. No dependency on Android's download queue. */
final class UpdateHttpDownloader {
    interface Listener {
        void connecting(int source, String host);
        void progress(long received, long total);
    }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    private volatile boolean cancelled;
    private volatile HttpURLConnection active;
    static final int CONNECT_TIMEOUT = 8000, READ_TIMEOUT = 8000, MAX_REDIRECTS = 5;
    UpdateHttpDownloader() { this(url -> (HttpURLConnection) url.openConnection()); }
    UpdateHttpDownloader(Connections connections) { this.connections = connections; }

    void cancel() {
        cancelled = true;
        HttpURLConnection connection = active;
        if (connection != null) {
            Thread close = new Thread(connection::disconnect, "update-disconnect");
            close.setDaemon(true); close.start();
        }
    }
    private void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
    }
    static String fallback(String repo, String version) {
        return "https://raw.githubusercontent.com/" + repo + "/master/quietguard/download/QuietGuard-" + version + ".apk";
    }
    static boolean allowed(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null && url.getPort() == -1
            && url.getRef() == null && Arrays.asList("github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "raw.githubusercontent.com").contains(host);
    }
    void download(String repo, String version, String primary, String hash, File partial, Listener listener) throws IOException {
        if (!UpdateIntegrity.validApkUrl(repo, version, primary) || !UpdateIntegrity.validHash(hash))
            throw new IOException("更新下载地址或校验信息无效");
        List<String> problems = new ArrayList<>();
        String[] sources = {primary, fallback(repo, version)};
        for (int source = 0; source < sources.length; source++) {
            check();
            try {
                listener.connecting(source, new URL(sources[source]).getHost());
                transfer(new URL(sources[source]), hash, partial, listener);
                check(); return;
            } catch (IOException e) {
                if (partial.exists()) partial.delete();
                check();
                problems.add((source == 0 ? "主线路：" : "备用线路：") + explain(e));
            }
        }
        throw new IOException(String.join("；", problems) + "。可切换手机热点/移动网络后重试");
    }
    private void transfer(URL start, String expected, File partial, Listener listener) throws IOException {
        URL url = start;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            check();
            if (!allowed(url)) throw new IOException("服务器跳转到不受信任的地址");
            HttpURLConnection c = connections.open(url); active = c;
            try {
                check();
                c.setConnectTimeout(CONNECT_TIMEOUT); c.setReadTimeout(READ_TIMEOUT);
                c.setInstanceFollowRedirects(false); c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "QuietGuard-Updater");
                c.setRequestProperty("Accept-Encoding", "identity");
                int code = c.getResponseCode(); check();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null) throw new IOException("服务器跳转缺少地址");
                    url = new URL(url, location); continue;
                }
                if (code != 200) throw new IOException("服务器返回 HTTP " + code);
                long length = c.getContentLengthLong();
                if (length == 0 || length > UpdateIntegrity.MAX_BYTES) throw new IOException("服务器返回的文件大小无效");
                MessageDigest digest;
                try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
                long received = 0;
                listener.progress(0, length);
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(partial, false)) {
                    byte[] buffer = new byte[32768]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        check(); if (count == 0) continue;
                        if (count > UpdateIntegrity.MAX_BYTES - received) throw new IOException("安装包超过允许的大小");
                        out.write(buffer, 0, count); digest.update(buffer, 0, count); received += count;
                        listener.progress(received, length);
                    }
                    out.getFD().sync();
                }
                check();
                if (received == 0 || (length > 0 && received != length)) throw new IOException("下载文件不完整");
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
                if (!hex.toString().equalsIgnoreCase(expected)) throw new IOException("文件校验不通过，可能返回了网络登录页面");
                return;
            } finally { c.disconnect(); if (active == c) active = null; }
        }
        throw new IOException("服务器跳转次数过多");
    }
    private static String explain(IOException e) {
        if (e instanceof SocketTimeoutException) return "连接或读取超时";
        if (e instanceof UnknownHostException) return "域名解析失败";
        if (e instanceof SSLException) return "HTTPS 安全连接失败";
        if (e instanceof ConnectException) return "无法连接下载服务器";
        return e.getMessage() == null ? "网络或文件读写失败" : e.getMessage();
    }
}
