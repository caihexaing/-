package cn.quietguard.app.rules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;

public final class BundleChecks {
    private static KeyPair key;
    private static int checks;
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
    private static String hash(byte[] value) throws Exception {
        StringBuilder s=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(value)) s.append(String.format("%02x",b&255)); return s.toString();
    }
    private static byte[] meta(byte[] rules,byte[] sources,int count) throws Exception {
        return bytes("format=quietguard-dns-v1\nversion=2026110401\nminAppVersionCode=17\nbytes="+rules.length
            +"\nsha256="+hash(rules)+"\nentries="+count+"\nsourcesSha256="+hash(sources)+"\n");
    }
    private static byte[] sign(byte[] data) throws Exception {
        Signature s=Signature.getInstance("SHA256withRSA"); s.initSign(key.getPrivate()); s.update(data); return s.sign();
    }
    private static void check(boolean value) { checks++; if(!value) throw new AssertionError("Check "+checks); }
    private interface Run { void run() throws Exception; }
    private static void reject(Run run) throws Exception {
        boolean rejected=false; try { run.run(); } catch(java.io.IOException | GeneralSecurityException e) { rejected=true; }
        check(rejected);
    }
    private static RuleBundleVerifier.Verified verify(byte[] m,byte[] s,byte[] r,byte[] a,long floor,int app) throws Exception {
        return RuleBundleVerifier.verify(m,s,r,a,key.getPublic().getEncoded(),floor,app);
    }
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); key=generator.generateKeyPair();
        byte[] r=bytes("!safe.ads.example.com\nads.example.com\n~exact.ads.example.com\n"), a=bytes("{}\n"), m=meta(r,a,3), s=sign(m);
        RuleBundleVerifier.Verified v=verify(m,s,r,a,0,17);
        check(v.version==2026110401L && v.entries==3);
        check(v.blockedText.equals("ads.example.com\n") && v.allowedText.equals("safe.ads.example.com\n")
            && v.exactAllowedText.equals("exact.ads.example.com\n"));
        byte[] badM=m.clone(); badM[0]^=1; reject(()->verify(badM,s,r,a,0,17));
        byte[] badR=r.clone(); badR[1]^=1; reject(()->verify(m,s,badR,a,0,17));
        reject(()->verify(m,s,r,bytes("[]\n"),0,17));
        reject(()->verify(m,s,r,a,2026110401L,17));
        reject(()->verify(m,s,r,a,2026110402L,17));
        reject(()->verify(m,s,r,a,0,16));
        reject(()->verify(m,new byte[256],r,a,0,17));
        reject(()->verify(null,s,r,a,0,17));
        reject(()->verify(m,s,new byte[RuleBundleVerifier.MAX_RULE_BYTES+1],a,0,17));
        reject(()->verify(m,s,r,new byte[65537],0,17));
        byte[] wrongCount=meta(r,a,4); reject(()->verify(wrongCount,sign(wrongCount),r,a,0,17));
        for(String text:new String[]{"ads.example.com\nads.example.com\n", "z.example.com\na.example.com\n",
            "!safe.example.com\n", "~bad..example.com\n", "127.0.0.1\n", "ads.example.com\r\n", "||ads.example.com^\n", "ads.example.com", "a..com\n"}) {
            byte[] invalid=bytes(text); int count=(int)text.chars().filter(c->c=='\n').count();
            byte[] metadata=meta(invalid,a,Math.max(1,count)); reject(()->verify(metadata,sign(metadata),invalid,a,0,17));
        }
        byte[] unknown=bytes(new String(m,StandardCharsets.US_ASCII).replace("quietguard-dns-v1","quietguard-dns-v2"));
        reject(()->verify(unknown,sign(unknown),r,a,0,17));
        byte[] overflow=bytes(new String(m,StandardCharsets.US_ASCII).replace("2026110401","9999999999999999999"));
        reject(()->verify(overflow,sign(overflow),r,a,0,17));
        if(args.length==1) {
            Path p=Path.of(args[0]); byte[] data=Files.readAllBytes(p.resolve("rules.txt")), sources=Files.readAllBytes(p.resolve("sources.json")), metadata=Files.readAllBytes(p.resolve("rules.meta"));
            v=verify(metadata,sign(metadata),data,sources,0,17);
            check(v.entries>10000); // Fixture source is a real upstream DNS-filter snapshot.
            System.out.println("Upstream candidate validated with EPHEMERAL TEST KEY: "+v.entries+" entries; not a production signature.");
        }
        System.out.println("PASS: "+checks+" rule bundle verification checks.");
    }
}
