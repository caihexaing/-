package cn.quietguard.app;

import java.nio.charset.StandardCharsets;

/**
 * Extracts the effective host name from a cleartext HTTP/1.x request prefix.
 *
 * Ads served over plain HTTP still name their host in the request, so a Host header is
 * as good a filter key as a TLS SNI value. Only the request head is inspected: the first
 * request line plus headers, never the body.
 */
final class HttpHost {
    static final int NEED_MORE = 0, NO_HOST = 1, FOUND = 2;
    static final int MAX_HEAD = 8192;

    static final class Result {
        final int state;
        final String host;
        Result(int state, String host) { this.state = state; this.host = host; }
    }

    private HttpHost() { }

    static Result inspect(byte[] data, int length) {
        if (length <= 0) return new Result(NEED_MORE, null);
        int c = data[0] & 255;
        if (c < 'A' || c > 'Z') return new Result(NO_HOST, null);  // TLS, HTTP/2 preface, binary
        int head = headEnd(data, length);
        if (head < 0) {
            if (length < MAX_HEAD) return new Result(NEED_MORE, null);
            // Header block too large to wait for: use only the lines that are already complete,
            // because a half-read Host value could normalize into an unrelated, blocked name.
            head = lastLineEnd(data, length);
            if (head <= 0) return new Result(NO_HOST, null);
        }

        int lineEnd = lineEnd(data, 0, head);
        if (lineEnd < 0) return new Result(NO_HOST, null);
        if (!requestLine(data, 0, lineEnd, null)) return new Result(NO_HOST, null);

        String absolute = absoluteHost(data, 0, lineEnd);
        String host = headerHost(data, lineEnd, head);
        if (host == null) host = absolute;
        if (host == null) return new Result(NO_HOST, null);
        try { return new Result(FOUND, RuleSet.normalize(host)); }
        catch (IllegalArgumentException e) { return new Result(NO_HOST, null); }
    }

    /** Offset of the terminating CRLFCRLF plus 4, or -1 when the head is still incomplete. */
    private static int headEnd(byte[] b, int length) {
        for (int i = 0; i + 3 < length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i + 4;
        }
        if (length >= 2 && b[length - 2] == '\n' && b[length - 1] == '\n') return length;  // bare LF form
        return -1;
    }

    private static int lineEnd(byte[] b, int from, int end) {
        for (int i = from; i < end; i++) if (b[i] == '\n') return i;
        return -1;
    }

    /** Offset just past the last complete line in the buffer, or -1 when there is none. */
    private static int lastLineEnd(byte[] b, int length) {
        for (int i = length - 1; i >= 0; i--) if (b[i] == '\n') return i + 1;
        return -1;
    }

    /** Validates "METHOD SP target SP HTTP/1.x" and optionally returns the request target. */
    private static boolean requestLine(byte[] b, int from, int end, String[] target) {
        int p = from, methodEnd = -1;
        for (; p < end; p++) {
            int c = b[p] & 255;
            if (c == ' ') { methodEnd = p; break; }
            if (c < 'A' || c > 'Z') return false;  // methods are upper-case tokens
            if (p - from >= 16) return false;
        }
        if (methodEnd <= from) return false;
        int targetStart = methodEnd + 1, targetEnd = -1;
        for (p = targetStart; p < end; p++) if ((b[p] & 255) == ' ') { targetEnd = p; break; }
        if (targetEnd <= targetStart) return false;
        int versionStart = targetEnd + 1;
        if (versionStart + 8 > end) return false;
        String version = new String(b, versionStart, end - versionStart, StandardCharsets.US_ASCII).trim();
        if (!version.startsWith("HTTP/1.")) return false;
        if (target != null) target[0] = new String(b, targetStart, targetEnd - targetStart, StandardCharsets.US_ASCII);
        return true;
    }

    /** Host taken from an absolute-form request target, e.g. "GET http://ads.example.com/x". */
    private static String absoluteHost(byte[] b, int from, int lineEnd) {
        String[] target = new String[1];
        if (!requestLine(b, from, lineEnd, target) || target[0] == null) return null;
        String t = target[0];
        int scheme = t.startsWith("http://") ? 7 : t.startsWith("https://") ? 8 : -1;
        if (scheme < 0) return null;
        int end = t.length();
        for (int i = scheme; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        return authority(t.substring(scheme, end));
    }

    private static String headerHost(byte[] b, int lineEnd, int head) {
        int p = lineEnd + 1;
        while (p < head) {
            int end = lineEnd(b, p, head);
            if (end < 0) end = head;
            int colon = -1;
            for (int i = p; i < end; i++) {
                int c = b[i] & 255;
                if (c == ':') { colon = i; break; }
                if (c == ' ' || c == '\t') break;
            }
            if (colon > p && colon - p == 4) {
                String name = new String(b, p, 4, StandardCharsets.US_ASCII);
                if (name.equalsIgnoreCase("host")) {
                    int start = colon + 1, stop = end;
                    while (start < stop && (b[start] == ' ' || b[start] == '\t')) start++;
                    while (stop > start && (b[stop - 1] == ' ' || b[stop - 1] == '\t' || b[stop - 1] == '\r')) stop--;
                    if (stop <= start) return null;
                    return authority(new String(b, start, stop - start, StandardCharsets.US_ASCII));
                }
            }
            p = end + 1;
        }
        return null;
    }

    /** Strips user info, port and brackets from a Host/authority value. */
    static String authority(String raw) {
        String value = raw.trim();
        int at = value.lastIndexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            return close > 1 ? value.substring(1, close) : null;
        }
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        if (value.isEmpty()) return null;
        return value;
    }
}
