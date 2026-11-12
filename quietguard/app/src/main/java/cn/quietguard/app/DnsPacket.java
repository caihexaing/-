package cn.quietguard.app;

import java.util.Arrays;

public final class DnsPacket {
    public final byte[] ip, dns;
    public final int header;
    private DnsPacket(byte[] p, int h, byte[] d) { ip = p; header = h; dns = d; }
    public static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }
    static void put(byte[] b, int i, int n) { b[i] = (byte)(n >> 8); b[i + 1] = (byte)n; }
    public static DnsPacket parse(byte[] p, int length) {
        if (length < 28 || length > p.length || (p[0] >> 4 & 15) != 4 || p[9] != 17) return null;
        int h = (p[0] & 15) * 4, total = u16(p, 2);
        if (h < 20 || h + 8 > length || total > length || total < h + 20 || (u16(p, 6) & 0x3fff) != 0) return null;
        int udp = u16(p, h + 4);
        if (u16(p, h + 2) != 53 || udp < 20 || h + udp > total) return null;
        byte[] d = Arrays.copyOfRange(p, h + 8, h + udp);
        if ((d[2] & 0xf8) != 0 || u16(d, 4) != 1) return null;
        return new DnsPacket(Arrays.copyOf(p, total), h, d);
    }
    public static int questionEnd(byte[] d) {
        if (d.length < 12 || u16(d, 4) != 1) return -1;
        int i = 12, labels = 0;
        while (i < d.length && labels++ < 128) {
            int n = d[i++] & 255; if (n == 0) return i + 4 <= d.length ? i + 4 : -1;
            if (n > 63 || i + n > d.length) return -1; i += n;
        }
        return -1;
    }
    public static String domain(byte[] d) {
        if (questionEnd(d) < 0) return null;
        StringBuilder s = new StringBuilder(); int i = 12;
        while (d[i] != 0) { int n = d[i++] & 255; if (s.length() > 0) s.append('.');
            for (int k = 0; k < n; k++) { int c = d[i++] & 255; if (c < 33 || c > 126 || c == '.') return null; s.append((char)c); }
        }
        return s.toString();
    }
    /** End offset of the possibly compressed name starting at {@code at}, or -1. */
    public static int nameEnd(byte[] d, int at) {
        int i = at, labels = 0;
        while (i >= 0 && i < d.length && labels++ < 128) {
            int n = d[i] & 255;
            if (n == 0) return i + 1;
            if ((n & 0xc0) == 0xc0) return i + 2 <= d.length ? i + 2 : -1;
            if (n > 63 || i + 1 + n > d.length) return -1;
            i += 1 + n;
        }
        return -1;
    }
    /** Resolves a name at {@code at}, following compression pointers; null if malformed. */
    public static String name(byte[] d, int at) {
        StringBuilder s = new StringBuilder(); int i = at, jumps = 0;
        while (i >= 0 && i < d.length && jumps++ < 64) {
            int n = d[i] & 255;
            if (n == 0) return s.length() == 0 ? null : s.toString();
            if ((n & 0xc0) == 0xc0) {
                if (i + 1 >= d.length) return null;
                int ptr = ((n & 0x3f) << 8) | (d[i + 1] & 255);
                if (ptr < 12 || ptr >= d.length) return null;
                i = ptr; continue;
            }
            if (n > 63 || i + 1 + n > d.length) return null;
            if (s.length() > 0) s.append('.');
            for (int k = 0; k < n; k++) {
                int c = d[i + 1 + k] & 255;
                if (c < 33 || c > 126) return null;
                s.append((char)c);
            }
            if (s.length() > 253) return null;
            i += 1 + n;
        }
        return null;
    }
    /** CNAME targets in the answer section, in order, at most {@code limit}. */
    public static String[] cnameTargets(byte[] reply, int limit) {
        int end = questionEnd(reply);
        if (end < 0 || reply.length < 12) return new String[0];
        int answers = u16(reply, 6), i = end, found = 0;
        String[] out = new String[Math.min(limit, Math.max(0, answers))];
        for (int n = 0; n < answers && n < 64 && found < out.length; n++) {
            i = nameEnd(reply, i);
            if (i < 0 || i + 10 > reply.length) break;
            int type = u16(reply, i), size = u16(reply, i + 8), data = i + 10;
            if (data + size > reply.length) break;
            if (type == 5) { String target = name(reply, data); if (target != null) out[found++] = target; }
            i = data + size;
        }
        if (found == out.length) return out;
        String[] trimmed = new String[found];
        System.arraycopy(out, 0, trimmed, 0, found);
        return trimmed;
    }
    /** First CNAME target in {@code reply} that the rule set would block, or null. */
    public static String blockedAlias(RuleSet rules, byte[] reply) {
        if (rules == null || reply == null || reply.length < 12) return null;
        for (String target : cnameTargets(reply, 16)) if (rules.blocks(target)) return target;
        return null;
    }
    public static byte[] error(byte[] query, int rcode) {
        int end = questionEnd(query); if (end < 0) return null;
        byte[] d = Arrays.copyOf(query, end);
        d[2] = (byte)(0x80 | (query[2] & 1)); d[3] = (byte)(0x80 | rcode);
        Arrays.fill(d, 6, 12, (byte)0); return d;
    }
    public static boolean validReply(byte[] query, byte[] reply) {
        int end = questionEnd(query), other = questionEnd(reply);
        if (end < 0 || end != other || reply.length < 12 || reply[0] != query[0] || reply[1] != query[1] || (reply[2] & 0x80) == 0 || (reply[2] & 0x78) != 0) return false;
        for (int i = 12; i < end; i++) if (query[i] != reply[i]) return false; return true;
    }
    public byte[] response(byte[] payload) {
        byte[] p = new byte[28 + payload.length]; p[0] = 0x45; put(p, 2, p.length); p[8] = 64; p[9] = 17;
        System.arraycopy(ip, 16, p, 12, 4); System.arraycopy(ip, 12, p, 16, 4);
        System.arraycopy(ip, header + 2, p, 20, 2); System.arraycopy(ip, header, p, 22, 2);
        put(p, 24, 8 + payload.length); System.arraycopy(payload, 0, p, 28, payload.length);
        put(p, 10, checksum(p, 20)); // IPv4 permits zero UDP checksum.
        return p;
    }
    public static int checksum(byte[] p, int length) {
        long sum = 0; for (int i = 0; i < length; i += 2) sum += (p[i] & 255) * 256 + (i + 1 < length ? p[i + 1] & 255 : 0);
        while ((sum >> 16) != 0) sum = (sum & 65535) + (sum >> 16); return (int)(~sum) & 65535;
    }
}
