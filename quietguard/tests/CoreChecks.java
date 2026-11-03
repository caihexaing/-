package cn.quietguard.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CoreChecks {
    private static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static byte[] query(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0});
        for (String s : name.split("\\.")) { out.write(s.length()); out.write(s.getBytes(StandardCharsets.US_ASCII)); }
        out.write(new byte[]{0,0,1,0,1}); return out.toByteArray();
    }
    static byte[] ip(byte[] dns) {
        byte[] b = new byte[28 + dns.length]; b[0] = 0x45; DnsPacket.put(b,2,b.length); b[8] = 64; b[9] = 17;
        b[12] = 10; b[15] = 1; b[16] = 10; b[19] = 2;
        DnsPacket.put(b,20,54321); DnsPacket.put(b,22,53); DnsPacket.put(b,24,8 + dns.length); System.arraycopy(dns,0,b,28,dns.length); return b;
    }
    public static void main(String[] args) throws Exception {
        RuleSet r = new RuleSet(new ByteArrayInputStream("# test\nads.example.com\ndoubleclick.net\n".getBytes(StandardCharsets.UTF_8)), "allowed.ads.example.com");
        check(r.size()==2,"rule count"); check(r.blocks("ads.example.com"),"exact match"); check(r.blocks("A.ADS.EXAMPLE.COM."),"case and trailing dot");
        check(!r.blocks("badads.example.com"),"label boundary"); check(!r.blocks("ads.example.com.evil.test"),"suffix confusion");
        check(!r.blocks("allowed.ads.example.com"),"exception"); check(!r.blocks("a.allowed.ads.example.com"),"exception subdomain");
        check(r.blocks("doubleclick.net"),"second rule"); check(!r.blocks("正常网页"),"invalid domain safe");
        boolean invalid = false; try { RuleSet.normalize("https://example.com/path"); } catch (IllegalArgumentException e) { invalid=true; } check(invalid,"URL rejected");
        byte[] q = query("ads.example.com"); check("ads.example.com".equals(DnsPacket.domain(q)),"domain parse");
        byte[] error = DnsPacket.error(q,3); check(error.length==q.length,"question preserved"); check((error[3]&15)==3,"NXDOMAIN");
        check((error[2]&128)!=0,"response flag"); check((error[2]&1)==1,"recursion desired preserved");
        check(DnsPacket.validReply(q,error),"reply validation"); error[0]++; check(!DnsPacket.validReply(q,error),"transaction mismatch"); error[0]--;
        error[13]='b'; check(!DnsPacket.validReply(q,error),"question mismatch"); error[13]='a';
        byte[] p = ip(q); DnsPacket packet = DnsPacket.parse(p,p.length); check(packet!=null,"IPv4 UDP parse");
        byte[] reply = packet.response(DnsPacket.error(q,3)); check(DnsPacket.checksum(reply,20)==0,"IPv4 checksum");
        check(DnsPacket.u16(reply,2)==reply.length,"total length"); check(DnsPacket.u16(reply,20)==53,"reply source port");
        check(DnsPacket.u16(reply,22)==54321,"reply destination port"); check(reply[15]==2 && reply[19]==1,"IP reversal");
        byte[] malformed=q.clone(); malformed[12]=(byte)0xc0; check(DnsPacket.domain(malformed)==null,"compressed/malformed query rejected");
        check(DnsPacket.domain(new byte[3])==null,"short DNS");
        for (int n=0;n<p.length;n++) { byte[] shortP=Arrays.copyOf(p,n); check(DnsPacket.parse(shortP,n)==null,"truncated packet "+n); }
        p[9]=6; check(DnsPacket.parse(p,p.length)==null,"TCP excluded"); p[9]=17;
        p[6]=0x20; check(DnsPacket.parse(p,p.length)==null,"fragments rejected"); p[6]=0;
        DnsPacket.put(p,24,65535); check(DnsPacket.parse(p,p.length)==null,"invalid UDP length");
        Random random = new Random(123); for (int i=0;i<20000;i++) { byte[] b = new byte[random.nextInt(256)]; random.nextBytes(b); DnsPacket.parse(b,b.length); DnsPacket.domain(b); }
        check(true,"20,000 malformed packet fuzz cases without crash");
        System.out.println("PASS: " + checks + " assertions; 20,000 malformed packet fuzz cases.");
    }
}
