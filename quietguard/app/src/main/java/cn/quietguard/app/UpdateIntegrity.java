package cn.quietguard.app;

import java.io.*;
import java.security.*;
import java.util.Locale;
import java.net.URI;

/** Stream verification shared by the updater and the corruption/cancellation tests. */
public final class UpdateIntegrity {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    private UpdateIntegrity() { }
    public static boolean validHash(String hash) { return hash != null && hash.matches("[a-fA-F0-9]{64}"); }
    public static boolean validApkUrl(String repo, String version, String value) {
        if (version == null || !version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) return false;
        try {
            URI uri = new URI(value);
            String path = "/" + repo + "/releases/download/quietguard-v" + version + "/QuietGuard-" + version + ".apk";
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost()) && path.equals(uri.getRawPath())
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception e) { return false; }
    }
    public static long copyAndCheck(InputStream source, OutputStream destination, String expected, long maxBytes) throws IOException {
        if (!validHash(expected)) throw new IOException("无效的 SHA-256 校验值");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        byte[] buffer = new byte[65536]; int n; long total = 0;
        while ((n = source.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("校验已取消");
            if (n == 0) continue;
            if (n > maxBytes - total) throw new IOException("更新文件超过允许的大小");
            total += n; digest.update(buffer, 0, n); destination.write(buffer, 0, n);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("校验已取消");
        if (total == 0) throw new IOException("更新文件为空");
        StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!hex.toString().equalsIgnoreCase(expected)) throw new IOException("文件校验不通过，请重新下载");
        return total;
    }
    public static int progress(long received, long total, boolean complete) {
        if (complete) return 100;
        if (total <= 0 || received <= 0) return 0;
        return (int)Math.min(99, Math.max(0, (double)received / total * 100));
    }
}
