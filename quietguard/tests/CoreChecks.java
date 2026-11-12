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
    /** A plausible reply to {@code name} answering with one CNAME record to {@code target}. */
    static byte[] replyWithCname(String name, String target) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12,0x34,(byte)0x81,(byte)0x80,0,1,0,1,0,0,0,0});
        for (String s : name.split("\\.")) { out.write(s.length()); out.write(s.getBytes(StandardCharsets.US_ASCII)); }
        out.write(new byte[]{0,0,1,0,1});
        out.write(new byte[]{(byte)0xc0,12,0,5,0,1,0,0,0,60});
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (String s : target.split("\\.")) { data.write(s.length()); data.write(s.getBytes(StandardCharsets.US_ASCII)); }
        data.write(0);
        byte[] rdata = data.toByteArray();
        out.write(rdata.length >> 8); out.write(rdata.length);
        out.write(rdata);
        return out.toByteArray();
    }
    /** A bare-SYN IPv4/TCP packet with a known sequence number, for the resolver-drop checks. */
    static byte[] syn(String src, String dst, int sport, int dport) {
        byte[] b = new byte[40];
        b[0] = 0x45; DnsPacket.put(b,2,b.length); b[8] = 64; b[9] = 6;
        System.arraycopy(address(src),0,b,12,4); System.arraycopy(address(dst),0,b,16,4);
        DnsPacket.put(b,20,sport); DnsPacket.put(b,22,dport);
        b[24] = 0x01; b[25] = 0x02; b[26] = 0x03; b[27] = 0x04;   // ISN
        b[32] = 0x50; b[33] = 0x02;                                // data offset 5, SYN
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += DnsPacket.u16(b,i);
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        int check = ~sum & 0xffff; b[10] = (byte)(check >> 8); b[11] = (byte)check;
        return b;
    }
    static byte[] address(String dotted) {
        String[] parts = dotted.split("\\."); byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) out[i] = (byte)Integer.parseInt(parts[i]);
        return out;
    }
    /** TCP checksum over the pseudo header, as a receiver would verify it. */
    static int tcpChecksum(byte[] p) {
        int sum = 6 + (p.length - 20);
        for (int i = 12; i < 20; i += 2) sum += DnsPacket.u16(p,i);
        for (int i = 20; i < p.length; i += 2) sum += DnsPacket.u16(p,i);
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return ~sum & 0xffff;
    }
    public static void main(String[] args) throws Exception {
        RuleSet r = new RuleSet(new ByteArrayInputStream("# test\nads.example.com\ndoubleclick.net\n!allowed.ads.example.com\n~g.doubleclick.net\n".getBytes(StandardCharsets.UTF_8)), "allowed2.ads.example.com");
        check(r.size()==2,"rule count"); check(r.blocks("ads.example.com"),"exact match"); check(r.blocks("A.ADS.EXAMPLE.COM."),"case and trailing dot");
        check(!r.blocks("badads.example.com"),"label boundary"); check(!r.blocks("ads.example.com.evil.test"),"suffix confusion");
        check(!r.blocks("allowed.ads.example.com"),"asset exception"); check(!r.blocks("a.allowed.ads.example.com"),"asset exception subdomain");
        check(!r.blocks("allowed2.ads.example.com"),"user exception"); check(!r.blocks("a.allowed2.ads.example.com"),"user exception subdomain");
        check(!r.blocks("g.doubleclick.net"),"exact upstream exception"); check(r.blocks("x.g.doubleclick.net"),"exact exception does not cover subdomain");
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
        RuleSet expanded;
        try (InputStream asset = new FileInputStream("app/src/main/assets/ad-domains.txt")) { expanded = new RuleSet(asset, ""); }
        check(expanded.size()==280602,"expanded DNS block count"); check(expanded.exceptionSize()==339,"upstream exceptions loaded");
        try (InputStream counts = new FileInputStream("app/src/main/assets/ad-domains.counts")) {
            RuleStats stats = RuleStats.parse(counts);
            check(stats.known(),"counts asset readable"); check(stats.blocked==expanded.size(),"counts match rule asset");
            check(stats.exceptions==expanded.exceptionSize(),"counts exceptions match rule asset");
        }
        for (String domain : new String[]{"ads.pangle.com", "gdt.qq.com", "mobads.baidu.com", "adkwai.com",
            "mintegral.com", "applovin.com", "unityads.unity3d.com", "googlesyndication.com"})
            check(expanded.blocks(domain), "SDK domain blocked: " + domain);
        check(expanded.blocks("a.applovin.com"),"AppLovin SDK endpoint blocked, not excepted");
        check(!expanded.blocks("ad.vivo.com.cn") && !expanded.blocks("x.ad.vivo.com.cn"),"upstream suffix exception retained");
        check(!expanded.blocks("weixin.qq.com") && !expanded.blocks("api.weixin.qq.com"),"core service domains stay reachable");

        // Encrypted-DNS list: blocked by name so apps cannot resolve their way around us.
        RuleSet merged;
        try (InputStream ad = new FileInputStream("app/src/main/assets/ad-domains.txt");
             InputStream bypass = new FileInputStream("app/src/main/assets/dns-bypass-domains.txt")) {
            merged = new RuleSet(new InputStream[]{ad, bypass}, "");
        }
        check(merged.blocks("dns.google"),"DoH host name blocked in merged set");
        check(merged.blocks("doh.pub"),"domestic DoH host name blocked");
        check(merged.blocks("httpdns.alicdn.com"),"app HTTPDNS endpoint blocked");
        check(merged.blocks("httpdns.meituan.com"),"HTTPDNS endpoint blocked");
        check(merged.blocks("gdt.qq.com"),"ad rules still merged in");
        check(!merged.blocks("api.weixin.qq.com"),"merging did not break exceptions");
        check(merged.size()>expanded.size(),"merged set is larger than the ad list alone");
        check(DnsEndpoint.encryptedDns("1.1.1.1",443),"Cloudflare DoH address recognised");
        check(DnsEndpoint.encryptedDns("223.5.5.5",443),"AliDNS DoH address recognised");
        check(DnsEndpoint.encryptedDns("1.1.1.1",853),"DoT address recognised");
        check(!DnsEndpoint.encryptedDns("ads.example.com",443),"ordinary host is not a resolver");
        check(!DnsEndpoint.encryptedDns("1.1.1.1",53),"plain DNS is not an encrypted transport");

        byte[] dohSyn = syn("192.168.1.7", "1.1.1.1", 41234, 443);
        check(DnsEndpoint.encryptedAttempt(dohSyn,dohSyn.length),"DoH SYN to a known resolver is dropped");
        byte[] dotSyn = syn("192.168.1.7", "8.8.8.8", 41234, 853);
        check(DnsEndpoint.encryptedAttempt(dotSyn,dotSyn.length),"DoT SYN to a known resolver is dropped");
        byte[] plainDnsSyn = syn("192.168.1.7", "1.1.1.1", 41234, 53);
        check(!DnsEndpoint.encryptedAttempt(plainDnsSyn,plainDnsSyn.length),"plain DNS to a resolver stays filtered, not dropped");
        byte[] otherSyn = syn("192.168.1.7", "1.2.3.4", 41234, 443);
        check(!DnsEndpoint.encryptedAttempt(otherSyn,otherSyn.length),"unrelated destination is untouched");
        byte[] acked = syn("192.168.1.7", "1.1.1.1", 41234, 443); acked[33] = (byte)0x12;   // SYN+ACK
        check(!DnsEndpoint.encryptedAttempt(acked,acked.length),"retransmitted/ACKed packets are not counted twice");

        byte[] reset = DnsEndpoint.reject(dohSyn,dohSyn.length);
        check(reset != null && reset.length==40,"reject builds a 40 byte packet");
        check((reset[0]&255)==0x45 && (reset[9]&255)==6,"reset is IPv4/TCP");
        check(reset[12]==1 && reset[13]==1 && reset[14]==1 && reset[15]==1,"reset source is the blocked resolver");
        check(reset[16]==(byte)192 && reset[19]==(byte)7,"reset destination is the original client");
        check(DnsPacket.u16(reset,20)==443,"reset source port mirrors the attempt");
        check(DnsPacket.u16(reset,22)==41234,"reset destination port mirrors the attempt");
        check((reset[33]&255)==0x14,"reset sets RST+ACK");
        check(DnsPacket.checksum(reset,20)==0,"reset IP checksum is valid");
        check(tcpChecksum(reset)==0,"reset TCP checksum is valid");
        check((reset[28]&255)==1 && (reset[29]&255)==2 && (reset[30]&255)==3 && (reset[31]&255)==5,"reset sequence is the client ISN plus one");
        check(DnsEndpoint.reject(plainDnsSyn,plainDnsSyn.length)==null,"no reset for traffic that is merely filtered");
        check(DnsEndpoint.reject(new byte[]{1,2,3},3)==null,"no reset for a truncated packet");

        byte[] cnameReply = replyWithCname("track.example.com","e.criteo.com");
        check("e.criteo.com".equals(DnsPacket.blockedAlias(expanded,cnameReply)),"CNAME alias to blocked host detected");
        check(DnsPacket.blockedAlias(expanded,replyWithCname("track.example.com","api.weixin.qq.com"))==null,"CNAME alias to allowed host passes");
        check(DnsPacket.blockedAlias(expanded,replyWithCname("track.example.com","cdn.example.net"))==null,"CNAME alias to unknown host passes");
        check(DnsPacket.blockedAlias(expanded,new byte[]{1,2,3})==null,"truncated reply without alias is safe");
        check(DnsPacket.cnameTargets(replyWithCname("a.example.com","b.example.com"),16).length==1,"single CNAME target");
        check(DnsPacket.cnameTargets(replyWithCname("a.example.com","b.example.com"),0).length==0,"CNAME limit zero");
        for (int n=0;n<p.length;n++) { byte[] shortP=Arrays.copyOf(p,n); check(DnsPacket.parse(shortP,n)==null,"truncated packet "+n); }
        p[9]=6; check(DnsPacket.parse(p,p.length)==null,"TCP excluded"); p[9]=17;
        p[6]=0x20; check(DnsPacket.parse(p,p.length)==null,"fragments rejected"); p[6]=0;
        DnsPacket.put(p,24,65535); check(DnsPacket.parse(p,p.length)==null,"invalid UDP length");
        Random random = new Random(123); for (int i=0;i<20000;i++) { byte[] b = new byte[random.nextInt(256)]; random.nextBytes(b); DnsPacket.parse(b,b.length); DnsPacket.domain(b); }
        check(true,"20,000 malformed packet fuzz cases without crash");
        for (int i=0;i<2000;i++) { byte[] b = new byte[random.nextInt(512)]; random.nextBytes(b); DnsPacket.cnameTargets(b,16); DnsPacket.blockedAlias(expanded,b); }
        check(true,"2,000 malformed reply fuzz cases without crash");
        System.out.println("PASS: " + checks + " assertions; 20,000 malformed packet and 2,000 malformed reply fuzz cases.");
    }
}
