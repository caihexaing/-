package cn.quietguard.app;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class SniClientHello {
    static final int NEED_MORE = 0, NO_SNI = 1, FOUND_SNI = 2;
    static final class Result {
        final int state;
        final String host;
        Result(int state, String host) { this.state = state; this.host = host; }
    }

    private SniClientHello() { }

    static Result inspect(byte[] data, int length) {
        if (length <= 0) return new Result(NEED_MORE, null);
        if ((data[0] & 255) != 22) return new Result(NO_SNI, null);
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        int offset = 0, expected = -1;
        while (offset < length) {
            if (length - offset < 5) return new Result(NEED_MORE, null);
            if ((data[offset] & 255) != 22) return new Result(NO_SNI, null);
            int recordLength = u16(data, offset + 3);
            if (recordLength > 18432) return new Result(NO_SNI, null);
            if (length - offset - 5 < recordLength) return new Result(NEED_MORE, null);
            handshake.write(data, offset + 5, recordLength);
            offset += 5 + recordLength;
            byte[] bytes = handshake.toByteArray();
            if (bytes.length >= 4) {
                if ((bytes[0] & 255) != 1) return new Result(NO_SNI, null);
                expected = 4 + u24(bytes, 1);
                if (expected > 65539) return new Result(NO_SNI, null);
                if (bytes.length >= expected) return parseHello(bytes, expected);
            }
        }
        return expected < 0 || handshake.size() < expected ? new Result(NEED_MORE, null) : new Result(NO_SNI, null);
    }

    private static Result parseHello(byte[] b, int end) {
        int p = 4;
        if (p + 34 > end) return new Result(NO_SNI, null);
        p += 34;
        if (p >= end) return new Result(NO_SNI, null);
        p += 1 + (b[p] & 255);
        if (p + 2 > end) return new Result(NO_SNI, null);
        int cipherBytes = u16(b, p); p += 2 + cipherBytes;
        if (p >= end) return new Result(NO_SNI, null);
        int compressionBytes = b[p++] & 255; p += compressionBytes;
        if (p == end) return new Result(NO_SNI, null);
        if (p + 2 > end) return new Result(NO_SNI, null);
        int extensionsEnd = p + 2 + u16(b, p);
        p += 2;
        if (extensionsEnd > end) return new Result(NO_SNI, null);
        while (p + 4 <= extensionsEnd) {
            int type = u16(b, p), size = u16(b, p + 2); p += 4;
            int extEnd = p + size;
            if (extEnd > extensionsEnd) return new Result(NO_SNI, null);
            if (type == 0 && size >= 5) {
                int listEnd = p + 2 + u16(b, p); int n = p + 2;
                if (listEnd > extEnd) return new Result(NO_SNI, null);
                while (n + 3 <= listEnd) {
                    int nameType = b[n++] & 255, nameLength = u16(b, n); n += 2;
                    if (n + nameLength > listEnd) return new Result(NO_SNI, null);
                    if (nameType == 0 && nameLength > 0) {
                        String host = new String(b, n, nameLength, StandardCharsets.US_ASCII);
                        for (int i = 0; i < host.length(); i++) if (host.charAt(i) < 0x21 || host.charAt(i) > 0x7e) return new Result(NO_SNI, null);
                        try { host = RuleSet.normalize(host); }
                        catch (IllegalArgumentException e) { return new Result(NO_SNI, null); }
                        return new Result(FOUND_SNI, host);
                    }
                    n += nameLength;
                }
                return new Result(NO_SNI, null);
            }
            p = extEnd;
        }
        return new Result(NO_SNI, null);
    }

    private static int u16(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    private static int u24(byte[] b, int p) { return ((b[p] & 255) << 16) | ((b[p + 1] & 255) << 8) | (b[p + 2] & 255); }
}
