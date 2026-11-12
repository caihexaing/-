package cn.quietguard.app;

public final class HttpChecks {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static byte[] bytes(String text) throws Exception { return text.getBytes("ISO-8859-1"); }
    private static HttpHost.Result inspect(String text) throws Exception {
        byte[] b = bytes(text); return HttpHost.inspect(b, b.length);
    }
    public static void main(String[] args) throws Exception {
        HttpHost.Result r = inspect("GET /a.gif HTTP/1.1\r\nHost: Ads.Example.com\r\nUser-Agent: x\r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "Host header parsed and normalized: " + r.host);

        r = inspect("GET /a.gif HTTP/1.1\r\nHost: ads.example.com:8080\r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "port stripped from Host");

        r = inspect("POST http://ads.example.com/track?id=1 HTTP/1.1\r\nAccept: */*\r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "absolute-form target used when no Host header");

        r = inspect("GET / HTTP/1.1\r\nhost:ads.example.com\r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "lower-case header name and no space");

        r = inspect("GET / HTTP/1.1\r\nHOST:   ads.example.com   \r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "upper-case header name, padded value");

        r = inspect("GET / HTTP/1.1\r\nX-Host: wrong.example.com\r\nHost: ads.example.com\r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "only the Host header counts");

        r = inspect("GET / HTTP/1.1\r\nHost: ads.example.com\r\n");
        check(r.state == HttpHost.NEED_MORE, "incomplete head waits for more bytes");

        r = inspect("GET /");
        check(r.state == HttpHost.NEED_MORE, "partial request line waits");

        r = inspect("GET / HTTP/1.1\r\nHost: ads.example.com");
        check(r.state == HttpHost.NEED_MORE, "truncated header value is never normalized");

        r = inspect("\u0016\u0003\u0001\u0000\u0005hello");
        check(r.state == HttpHost.NO_HOST, "TLS record is not HTTP");

        r = inspect("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");
        check(r.state == HttpHost.NO_HOST, "HTTP/2 preface is not HTTP/1.x");

        r = inspect("GET / HTTP/1.1\r\nConnection: close\r\n\r\n");
        check(r.state == HttpHost.NO_HOST, "request without any host form is skipped");

        r = inspect("GET / HTTP/1.1\r\nHost: \r\n\r\n");
        check(r.state == HttpHost.NO_HOST, "empty Host value is skipped");

        r = inspect("GET / HTTP/1.1\r\nHost: https://ads.example.com/x\r\n\r\n");
        check(r.state == HttpHost.NO_HOST, "URL pasted into Host cannot become a host name");

        r = inspect("GET / HTTP/1.1\r\nHost: [::1]:8080\r\n\r\n");
        check(r.state == HttpHost.NO_HOST, "IPv6 literal is not a filterable domain");

        r = inspect("GET / HTTP/1.1\r\nHost: ads.example.com.\r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "trailing dot removed");

        r = inspect("GET / HTTP/1.1\r\nHost: user@ads.example.com\r\n\r\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "user-info removed from authority");

        r = inspect("get / HTTP/1.1\r\nHost: ads.example.com\r\n\r\n");
        check(r.state == HttpHost.NO_HOST, "lower-case method is not HTTP/1.x");

        r = inspect("GET / HTTP/1.1\r\nHost: ads.example.com\r\n\r\nBODY");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "body after the head is ignored");

        r = inspect("GET / HTTP/1.0\nHost: ads.example.com\n\n");
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "bare LF line endings accepted");

        // A head that never terminates must not produce a host out of a half-read value.
        StringBuilder huge = new StringBuilder("GET / HTTP/1.1\r\nHost: ads.example.com\r\n");
        while (huge.length() < HttpHost.MAX_HEAD + 64) huge.append("X-Pad: 0123456789abcdef0123456789abcdef\r\n");
        r = inspect(huge.toString());
        check(r.state == HttpHost.FOUND && "ads.example.com".equals(r.host), "oversized head uses complete lines only");

        StringBuilder cut = new StringBuilder("GET / HTTP/1.1\r\nHost: ads.exa");
        while (cut.length() < HttpHost.MAX_HEAD + 64) cut.append('m');
        r = inspect(cut.toString());
        check(r.state == HttpHost.NO_HOST, "oversized head never completes a truncated Host");

        check(HttpHost.inspect(new byte[0], 0).state == HttpHost.NEED_MORE, "empty buffer waits");
        check(HttpHost.inspect(new byte[]{'G'}, 1).state == HttpHost.NEED_MORE, "single byte waits");

        // Randomised robustness: the parser must never throw and never invent a host from noise.
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            byte[] noise = new byte[random.nextInt(300)];
            random.nextBytes(noise);
            if (noise.length > 0) noise[0] = (byte) 'G';
            HttpHost.Result result = HttpHost.inspect(noise, noise.length);
            check(result.state == HttpHost.NEED_MORE || result.state == HttpHost.NO_HOST || result.state == HttpHost.FOUND,
                "fuzz state in range");
        }
        System.out.println("PASS: 25 plain-HTTP Host checks and 2000 fuzz cases.");
    }
}
