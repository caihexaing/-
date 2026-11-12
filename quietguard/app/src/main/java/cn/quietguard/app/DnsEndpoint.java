package cn.quietguard.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Public encrypted-DNS resolvers, addressed by IPv4 literal.
 *
 * Name-based blocking cannot stop a client that already knows the resolver's address, and a
 * DoH request to {@code https://1.1.1.1/dns-query} carries no SNI, so the SNI check cannot see
 * it either. These addresses are routed into the tunnel and dropped, which is what makes
 * DNS-level filtering hold: an app that cannot reach its own resolver has to use the filtered one.
 */
final class DnsEndpoint {
    private static final Set<String> ADDRESSES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "1.1.1.1", "1.0.0.1",                    // Cloudflare
        "8.8.8.8", "8.8.4.4",                    // Google
        "9.9.9.9", "149.112.112.112",            // Quad9
        "94.140.14.14", "94.140.15.15",          // AdGuard DNS
        "208.67.222.222", "208.67.220.220",      // OpenDNS
        "223.5.5.5", "223.6.6.6",                // AliDNS
        "119.29.29.29", "182.254.116.116",       // DNSPod
        "120.53.53.53", "1.12.12.12",            // DNSPod / Tencent DoH
        "180.76.76.76",                          // Baidu
        "101.226.4.6", "218.30.118.6"            // 360
    )));

    private DnsEndpoint() { }

    /** Every address whose traffic must not leave unfiltered when the bypass switch is on. */
    static Set<String> addresses() { return ADDRESSES; }

    /** True when a destination is one of these resolvers on an encrypted transport port. */
    static boolean encryptedDns(String host, int port) {
        return (port == 443 || port == 853) && host != null && ADDRESSES.contains(host);
    }

    /**
     * True for a packet that asks one of these resolvers over an encrypted transport (DoH on
     * TCP/443, DoT on TCP/853, DoQ/QUIC on UDP/443). Such a packet cannot be filtered — the
     * query is inside TLS — so it is dropped and the app falls back to the resolver we provide.
     *
     * Plain UDP/53 to these addresses is not matched here: that traffic is resolved by us like
     * any other query, which keeps an app that hardcodes its own resolver filtered.
     */
    static boolean encryptedAttempt(byte[] p, int length) {
        if (length < 28 || (p[0] >> 4 & 15) != 4) return false;
        int h = (p[0] & 15) * 4;
        if (h < 20 || h + 8 > length) return false;
        if (!ADDRESSES.contains(dotted(p, 16))) return false;
        int proto = p[9] & 255;
        if (proto == 17) {
            int port = u16(p, h + 2);
            return port == 443 || port == 853;
        }
        if (proto != 6 || h + 20 > length) return false;
        if (((p[h + 12] & 255) >> 4) * 4 < 20) return false;
        int port = u16(p, h + 2);
        if (port != 443 && port != 853) return false;
        int flags = p[h + 13] & 255;
        return (flags & 0x02) != 0 && (flags & 0x10) == 0;  // bare SYN: count once per connection
    }

    /**
     * A TCP reset for a connection attempt that is being dropped, or null when the packet is not
     * a bare SYN.
     *
     * Silently dropping the SYN makes the client wait out its connect timeout before it gives up
     * on its own resolver. Refusing the connection answers in one round trip instead, so the app
     * retries through the resolver we provide rather than appearing frozen.
     */
    static byte[] reject(byte[] p, int length) {
        if (!encryptedAttempt(p, length) || (p[9] & 255) != 6) return null;
        int h = (p[0] & 15) * 4;
        byte[] out = new byte[40];
        out[0] = 0x45;
        out[2] = 0; out[3] = 40;              // total length
        out[8] = 64;                          // ttl
        out[9] = 6;                           // tcp
        out[12] = p[16]; out[13] = p[17]; out[14] = p[18]; out[15] = p[19];   // src = original dst
        out[16] = p[12]; out[17] = p[13]; out[18] = p[14]; out[19] = p[15];
        out[20] = p[h + 2]; out[21] = p[h + 3];   // sport = original dport
        out[22] = p[h]; out[23] = p[h + 1];
        long seq = ((p[h + 4] & 255L) << 24 | (p[h + 5] & 255L) << 16 | (p[h + 6] & 255L) << 8 | (p[h + 7] & 255L)) + 1;
        out[28] = (byte) (seq >>> 24); out[29] = (byte) (seq >>> 16); out[30] = (byte) (seq >>> 8); out[31] = (byte) seq;
        out[32] = 0x50;                       // data offset 5, no options
        out[33] = 0x14;                       // RST + ACK
        int ip = 0;
        for (int i = 0; i < 20; i += 2) ip += u16(out, i);
        while ((ip >>> 16) != 0) ip = (ip & 0xffff) + (ip >>> 16);
        int ipCheck = ~ip & 0xffff;
        out[10] = (byte) (ipCheck >>> 8); out[11] = (byte) ipCheck;
        int tcp = 6 + 20;                      // protocol + segment length
        for (int i = 12; i < 20; i += 2) tcp += u16(out, i);
        for (int i = 20; i < 40; i += 2) tcp += u16(out, i);
        while ((tcp >>> 16) != 0) tcp = (tcp & 0xffff) + (tcp >>> 16);
        int tcpCheck = ~tcp & 0xffff;
        out[36] = (byte) (tcpCheck >>> 8); out[37] = (byte) tcpCheck;
        return out;
    }

    private static int u16(byte[] b, int i) { return ((b[i] & 255) << 8) | (b[i + 1] & 255); }
    private static String dotted(byte[] b, int at) {
        return (b[at] & 255) + "." + (b[at + 1] & 255) + "." + (b[at + 2] & 255) + "." + (b[at + 3] & 255);
    }
}
