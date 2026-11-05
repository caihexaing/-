package cn.quietguard.app;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class SniChecks {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static byte[] hello(String host) throws Exception {
        byte[] name = host.getBytes("US-ASCII");
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        int serverNameExtensionLength = name.length + 5;
        ext.write(new byte[]{0,0,(byte)(serverNameExtensionLength >>> 8),(byte)serverNameExtensionLength});
        ext.write(new byte[]{0,(byte)(name.length + 3),0,0,(byte)name.length}); ext.write(name);
        byte[] extensions = ext.toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(new byte[]{3,3}); body.write(new byte[32]); body.write(0);
        body.write(new byte[]{0,2,0,47,1,0}); body.write((extensions.length >>> 8) & 255); body.write(extensions.length & 255); body.write(extensions);
        byte[] helloBody = body.toByteArray();
        ByteArrayOutputStream hs = new ByteArrayOutputStream(); hs.write(1); hs.write((helloBody.length >>> 16) & 255);
        hs.write((helloBody.length >>> 8) & 255); hs.write(helloBody.length & 255); hs.write(helloBody);
        byte[] handshake = hs.toByteArray();
        ByteArrayOutputStream record = new ByteArrayOutputStream(); record.write(new byte[]{22,3,3});
        record.write((handshake.length >>> 8) & 255); record.write(handshake.length & 255); record.write(handshake);
        return record.toByteArray();
    }
    private static byte[] record(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(new byte[]{22,3,3});
        out.write((payload.length >>> 8) & 255); out.write(payload.length & 255); out.write(payload); return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        byte[] full = hello("Ads.Example.com");
        SniClientHello.Result result = SniClientHello.inspect(full, full.length);
        check(result.state == SniClientHello.FOUND_SNI && "ads.example.com".equals(result.host), "TLS SNI parsed and normalized: state=" + result.state + " host=" + result.host);
        int split = full.length / 2;
        byte[] fragmented = new byte[full.length + 5];
        System.arraycopy(record(Arrays.copyOfRange(full, 5, 5 + split - 5)), 0, fragmented, 0, 5 + split - 5);
        byte[] secondPayload = Arrays.copyOfRange(full, split, full.length);
        byte[] second = record(secondPayload);
        int firstLength = 5 + split - 5;
        System.arraycopy(second, 0, fragmented, firstLength, second.length);
        result = SniClientHello.inspect(fragmented, firstLength + second.length);
        check(result.state == SniClientHello.FOUND_SNI && "ads.example.com".equals(result.host), "TLS handshake across records");
        check(SniClientHello.inspect(full, 4).state == SniClientHello.NEED_MORE, "partial record waits");
        check(SniClientHello.inspect(new byte[]{23,3,3,0,0}, 5).state == SniClientHello.NO_SNI, "non-handshake bypasses parser");
        byte[] malformed = full.clone(); malformed[3] = 0x7f; malformed[4] = (byte)0xff;
        check(SniClientHello.inspect(malformed, malformed.length).state == SniClientHello.NO_SNI, "oversized record rejected");
        System.out.println("PASS: 5 TLS SNI parser checks.");
    }
}
