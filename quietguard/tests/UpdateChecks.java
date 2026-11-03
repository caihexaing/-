package cn.quietguard.app;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class UpdateChecks {
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static int checks;
    private static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static void reject(String text, String hash, long max) throws Exception {
        boolean rejected = false;
        try { UpdateIntegrity.copyAndCheck(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), new ByteArrayOutputStream(), hash, max); }
        catch (IOException e) { rejected = true; }
        check(rejected, "corrupt/invalid payload must not pass verification");
    }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        check(UpdateIntegrity.copyAndCheck(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),out,ABC,3)==3,"valid payload");
        check(out.toString("UTF-8").equals("abc"),"private install copy is byte-identical");
        reject("abd",ABC,100); reject("ab",ABC,100); reject("",ABC,100); reject("abcd",ABC,3);
        reject("abc","",100); reject("abc","../fake",100); reject("abc",ABC,0);
        check(UpdateIntegrity.copyAndCheck(new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream(),ABC.toUpperCase(java.util.Locale.ROOT),3)==3,"uppercase digest");
        try { Thread.currentThread().interrupt(); reject("abc",ABC,3); } finally { Thread.interrupted(); }
        boolean networkError = false;
        try { UpdateIntegrity.copyAndCheck(new InputStream() { public int read() throws IOException { throw new IOException("stream interrupted"); } },new ByteArrayOutputStream(),ABC,100); }
        catch (IOException e) { networkError = true; }
        check(networkError,"broken input stream must not produce install-ready result");
        check(UpdateIntegrity.progress(0,-1,false)==0,"unknown total");
        check(UpdateIntegrity.progress(20,100,false)==20,"actual byte progress");
        check(UpdateIntegrity.progress(100,100,false)==99,"not complete before download completion");
        check(UpdateIntegrity.progress(100,100,true)==100,"download complete");
        check(UpdateIntegrity.progress(200,100,false)==99,"progress clamped");
        check(UpdateIntegrity.progress(-1,100,false)==0,"negative byte count");
        check(UpdateIntegrity.progress(Long.MAX_VALUE,Long.MAX_VALUE,false)==99,"no integer overflow");
        String url = "https://github.com/caihexaing/-/releases/download/quietguard-v0.4.1/QuietGuard-0.4.1.apk";
        check(UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url),"release asset URL");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url.replace("https:","http:")),"no cleartext");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url.replace("github.com","github.com.evil.test")),"no lookalike host");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url.replace("github.com","evil.test@github.com")),"no userinfo");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url+"?redirect=evil"),"no query redirection");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url+"#fragment"),"no fragments");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url.replace("caihexaing/-","someone/else")),"repo isolation");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.2",url),"version path must agree");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","../0.4.1",url),"invalid version rejected");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",url.replace("/QuietGuard-","%2fQuietGuard-")),"no encoded path ambiguity");
        check(!UpdateIntegrity.validApkUrl("caihexaing/-","0.4.1",null),"null URL rejected");
        System.out.println("PASS: " + checks + " update integrity, cancellation, byte progress and URL scope checks.");
    }
}
