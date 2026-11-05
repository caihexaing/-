package cn.quietguard.app.rules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure verifier only. No network, activation, disk writes or signing keys. */
public final class RuleBundleVerifier {
    public static final int MAX_RULE_BYTES=8*1024*1024, MAX_ENTRIES=500_000;
    private static final Pattern META=Pattern.compile("\\Aformat=quietguard-dns-v1\\nversion=([1-9][0-9]{0,18})\\n"
        +"minAppVersionCode=([1-9][0-9]{0,9})\\nbytes=([1-9][0-9]{0,8})\\nsha256=([a-f0-9]{64})\\n"
        +"entries=([1-9][0-9]{0,5})\\nsourcesSha256=([a-f0-9]{64})\\n\\z");
    private RuleBundleVerifier() { }
    public static final class Verified {
        public final long version;
        public final int entries;
        public final String blockedText, allowedText, exactAllowedText;
        private Verified(long version,int entries,String blocked,String allowed,String exactAllowed) {
            this.version=version; this.entries=entries; blockedText=blocked; allowedText=allowed; exactAllowedText=exactAllowed;
        }
    }
    public static Verified verify(byte[] metadata,byte[] signature,byte[] rules,byte[] sources,
                                  byte[] pinnedPublicKey,long highestAcceptedVersion,int appVersionCode)
                                  throws GeneralSecurityException,IOException {
        if (metadata==null || metadata.length>1024 || signature==null || signature.length>512
            || rules==null || rules.length>MAX_RULE_BYTES || sources==null || sources.length>65536
            || pinnedPublicKey==null || pinnedPublicKey.length>4096 || highestAcceptedVersion<0)
            throw new IOException("Rule update exceeds limits or has missing input");
        RSAPublicKey key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pinnedPublicKey));
        if(key.getModulus().bitLength()<2048 || key.getModulus().bitLength()>4096)
            throw new GeneralSecurityException("Unsupported rule signing key size");
        Signature verifier=Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key); verifier.update(metadata);
        if(!verifier.verify(signature)) throw new GeneralSecurityException("Rule manifest signature mismatch");
        for(byte b:metadata) if(b<0) throw new IOException("Non-ASCII manifest");
        Matcher m=META.matcher(new String(metadata,StandardCharsets.US_ASCII));
        if(!m.matches()) throw new IOException("Unknown or non-canonical rule manifest");
        long version; int minApp,bytes,entries;
        try {
            version=Long.parseLong(m.group(1)); minApp=Integer.parseInt(m.group(2));
            bytes=Integer.parseInt(m.group(3)); entries=Integer.parseInt(m.group(5));
        } catch(NumberFormatException e) { throw new IOException("Rule manifest number out of range",e); }
        if(version<=highestAcceptedVersion) throw new IOException("Rule version is not newer than accepted version floor");
        if(appVersionCode<minApp) throw new IOException("APP update required for these rules");
        if(bytes!=rules.length || entries>MAX_ENTRIES) throw new IOException("Rule length or count mismatch");
        if(!hex(MessageDigest.getInstance("SHA-256").digest(rules)).equals(m.group(4))
            || !hex(MessageDigest.getInstance("SHA-256").digest(sources)).equals(m.group(6)))
            throw new IOException("Rule content or attribution hash mismatch");
        for(byte b:rules) if(b<0 || (b<32 && b!='\n')) throw new IOException("Non-canonical rule bytes");
        if(rules.length==0 || rules[rules.length-1]!='\n') throw new IOException("Rule text must end with LF");
        String[] lines=new String(rules,StandardCharsets.US_ASCII).split("\n",-1);
        if(lines.length-1!=entries) throw new IOException("Rule entry count mismatch");
        String previous="";
        StringBuilder block=new StringBuilder(),allow=new StringBuilder(),exactAllow=new StringBuilder();
        for(int n=0;n<entries;n++) {
            String line=lines[n];
            if(line.compareTo(previous)<=0) throw new IOException("Rules must be sorted and unique");
            previous=line;
            boolean exception=line.startsWith("!") || line.startsWith("~");
            boolean exact=line.startsWith("~");
            String domain=exception ? line.substring(1) : line;
            if(!domain(domain)) throw new IOException("Invalid DNS domain in rules");
            (exact ? exactAllow : exception ? allow : block).append(domain).append('\n');
        }
        if(block.length()==0) throw new IOException("Empty block set");
        return new Verified(version,entries,block.toString(),allow.toString(),exactAllow.toString());
    }
    private static boolean domain(String value) {
        if(value.length()>253 || !value.contains(".") || value.endsWith(".") || value.matches("[0-9.]+")) return false;
        String[] labels=value.split("\\.",-1);
        for(String label:labels) if(!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return false;
        return !labels[labels.length-1].matches("[0-9]+");
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) { out.append(Character.forDigit((b>>>4)&15,16)); out.append(Character.forDigit(b&15,16)); }
        return out.toString();
    }
}
