package cn.quietguard.app;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Transport behavior with deterministic HTTP peers; exercises actual stream/file code. */
public final class DownloadChecks {
    private static final String REPO = "caihexaing/-", VERSION = "0.4.2";
    private static final String PRIMARY = "https://github.com/" + REPO + "/releases/download/quietguard-v" + VERSION + "/QuietGuard-" + VERSION + ".apk";
    private static final byte[] BODY = new byte[98000];
    private static int checks;
    static { new Random(42).nextBytes(BODY); }
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte v : MessageDigest.getInstance("SHA-256").digest(bytes)) b.append(String.format("%02x", v & 255));
        return b.toString();
    }
    static final class Peer extends HttpURLConnection {
        int code = 200; long length = BODY.length;
        String location; byte[] body = BODY; IOException failure;
        boolean disconnected; int reads, failAfter = -1;
        Peer() throws Exception { super(new URL(PRIMARY)); }
        @Override public int getResponseCode() throws IOException { if (failure != null) throw failure; return code; }
        @Override public long getContentLengthLong() { return length; }
        @Override public String getHeaderField(String field) { return "Location".equals(field) ? location : null; }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                final ByteArrayInputStream bytes = new ByteArrayInputStream(body);
                @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (failAfter >= 0 && reads >= failAfter) throw new SocketTimeoutException();
                    reads++; return bytes.read(b, off, len);
                }
            };
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
    static final class Wire implements UpdateHttpDownloader.Connections {
        final List<Peer> peers = new ArrayList<>(); final List<URL> urls = new ArrayList<>();
        @Override public HttpURLConnection open(URL url) throws IOException {
            urls.add(url);
            if (peers.isEmpty()) throw new IOException("Unexpected connection");
            return peers.remove(0);
        }
    }
    static final class Observer implements UpdateHttpDownloader.Listener {
        final List<Integer> sources = new ArrayList<>(); long received, total; int progress;
        Runnable hook;
        @Override public void connecting(int source, String host) { sources.add(source); }
        @Override public void progress(long n, long all) { received = n; total = all; progress++; if (hook != null) hook.run(); }
    }
    private static Peer status(int code) throws Exception { Peer p = new Peer(); p.code = code; return p; }
    private static void fails(UpdateHttpDownloader d, File file, String expected) throws Exception {
        try { d.download(REPO, VERSION, PRIMARY, hash(BODY), file, new Observer()); throw new AssertionError("expected rejection"); }
        catch (IOException e) { check(e.getMessage().contains(expected), "failure: " + expected); }
    }
    public static void main(String[] args) throws Exception {
        File partial = Files.createTempFile("quietguard-update-", ".part").toFile();
        try {
            Wire wire = new Wire(); Peer ok = new Peer(); wire.peers.add(ok);
            UpdateHttpDownloader d = new UpdateHttpDownloader(wire); Observer observer = new Observer();
            d.download(REPO, VERSION, PRIMARY, hash(BODY), partial, observer);
            check(Arrays.equals(Files.readAllBytes(partial.toPath()), BODY), "full stream saved without corruption");
            check(observer.received == BODY.length && observer.total == BODY.length && observer.progress > 2, "real incremental byte progress");
            check(observer.sources.equals(Arrays.asList(0)), "primary does not open fallback on success");
            check(ok.getConnectTimeout() == 8000 && ok.getReadTimeout() == 8000, "bounded connection and idle read");
            check(!ok.getInstanceFollowRedirects(), "redirect validation cannot be bypassed");
            check("identity".equals(ok.getRequestProperty("Accept-Encoding")), "length corresponds to transfer bytes");
            check(ok.disconnected, "connection closed after success");

            wire = new Wire(); Peer timeout = new Peer(); timeout.failure = new SocketTimeoutException(); wire.peers.add(timeout); wire.peers.add(new Peer());
            observer = new Observer();
            new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, observer);
            check(observer.sources.equals(Arrays.asList(0, 1)), "timeout switches source instead of waiting forever");
            check(wire.urls.get(1).toString().equals(UpdateHttpDownloader.fallback(REPO, VERSION)), "fallback bound to same repo and version");
            check(timeout.disconnected, "failed connection closed");

            wire = new Wire(); Peer stalled = new Peer(); stalled.failAfter = 1; wire.peers.add(stalled); wire.peers.add(new Peer());
            observer = new Observer(); new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, observer);
            check(observer.sources.equals(Arrays.asList(0, 1)) && Arrays.equals(Files.readAllBytes(partial.toPath()), BODY), "stalled partial restarts cleanly on fallback");

            wire = new Wire(); Peer dns = new Peer(); dns.failure = new UnknownHostException("github.com"); wire.peers.add(dns); wire.peers.add(new Peer());
            new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, new Observer());
            check(wire.urls.size() == 2, "DNS failure uses independent fallback hostname");

            wire = new Wire(); Peer redirect = status(302); redirect.location = "https://release-assets.githubusercontent.com/github-production-release-asset/test?signature=secret";
            wire.peers.add(redirect); wire.peers.add(new Peer());
            new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, new Observer());
            check(wire.urls.size() == 2 && wire.urls.get(1).getHost().equals("release-assets.githubusercontent.com"), "signed GitHub asset redirect supported");
            check(redirect.disconnected, "redirect connection closed");

            for (String location : Arrays.asList("http://github.com/bad", "https://evil.example/apk", "https://github.com.evil.example/apk", "https://user@github.com/apk", "https://github.com:444/apk")) {
                wire = new Wire(); redirect = status(302); redirect.location = location; wire.peers.add(redirect); wire.peers.add(new Peer());
                new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, new Observer());
                check(wire.urls.size() == 2 && wire.urls.get(1).getHost().equals("raw.githubusercontent.com"), "reject unsafe redirect " + location);
            }

            wire = new Wire(); redirect = status(302); redirect.location = "/download/next"; wire.peers.add(redirect); wire.peers.add(new Peer());
            new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, new Observer());
            check(wire.urls.get(1).toString().equals("https://github.com/download/next"), "relative HTTPS redirects");

            wire = new Wire(); for (int i = 0; i < 6; i++) { redirect = status(302); redirect.location = PRIMARY; wire.peers.add(redirect); }
            wire.peers.add(new Peer());
            new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, new Observer());
            check(wire.urls.size() == 7 && wire.urls.get(6).getHost().equals("raw.githubusercontent.com"), "redirect loops bounded then fallback");

            wire = new Wire(); Peer shortFile = new Peer(); shortFile.length++; wire.peers.add(shortFile); wire.peers.add(status(503));
            fails(new UpdateHttpDownloader(wire), partial, "下载文件不完整");
            check(!partial.exists(), "failed partial not installable");

            wire = new Wire(); Peer oversized = new Peer(); oversized.length = UpdateIntegrity.MAX_BYTES + 1; wire.peers.add(oversized); wire.peers.add(status(404));
            fails(new UpdateHttpDownloader(wire), partial, "文件大小无效");
            check(oversized.reads == 0, "oversized declaration rejected before writing");

            wire = new Wire(); Peer login = new Peer(); login.body = "<html>校园网登录</html>".getBytes("UTF-8"); login.length = login.body.length;
            wire.peers.add(login); wire.peers.add(status(404));
            fails(new UpdateHttpDownloader(wire), partial, "校验不通过");

            wire = new Wire(); Peer unknown = new Peer(); unknown.length = -1; wire.peers.add(unknown); observer = new Observer();
            new UpdateHttpDownloader(wire).download(REPO, VERSION, PRIMARY, hash(BODY), partial, observer);
            check(observer.total == -1 && observer.received == BODY.length, "unknown length preserves actual bytes");

            wire = new Wire(); Peer empty = new Peer(); empty.length = -1; empty.body = new byte[0]; wire.peers.add(empty); wire.peers.add(status(500));
            fails(new UpdateHttpDownloader(wire), partial, "下载文件不完整");

            wire = new Wire(); wire.peers.add(status(403)); wire.peers.add(status(429));
            fails(new UpdateHttpDownloader(wire), partial, "主线路：服务器返回 HTTP 403；备用线路：服务器返回 HTTP 429");
            check(wire.urls.size() == 2, "finite attempts on blocked network");

            wire = new Wire(); wire.peers.add(new Peer()); d = new UpdateHttpDownloader(wire); d.cancel();
            fails(d, partial, "取消"); check(wire.urls.isEmpty(), "cancel before connect does not access network");

            wire = new Wire(); wire.peers.add(new Peer()); d = new UpdateHttpDownloader(wire); observer = new Observer();
            final UpdateHttpDownloader active = d; final Observer cancellable = observer;
            observer.hook = () -> { if (cancellable.received > 0) active.cancel(); };
            try { d.download(REPO, VERSION, PRIMARY, hash(BODY), partial, observer); throw new AssertionError("cancel required"); }
            catch (InterruptedIOException e) { check(wire.urls.size() == 1 && cancellable.received > 0, "midstream cancel does not start backup"); }
            check(!partial.exists(), "midstream cancellation removes partial file");

            wire = new Wire(); d = new UpdateHttpDownloader(wire);
            try { d.download(REPO, VERSION, PRIMARY, "invalid", partial, new Observer()); throw new AssertionError(); }
            catch (IOException e) { check(wire.urls.isEmpty(), "invalid hash rejected before network"); }
            try { d.download(REPO, VERSION, PRIMARY.replace("caihexaing", "other"), hash(BODY), partial, new Observer()); throw new AssertionError(); }
            catch (IOException e) { check(wire.urls.isEmpty(), "wrong repository rejected before network"); }
            System.out.println("PASS: " + checks + " direct download, failover, timeout, cancellation and HTTPS checks.");
        } finally { partial.delete(); }
    }
}
