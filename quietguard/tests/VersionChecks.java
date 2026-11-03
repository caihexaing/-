package cn.quietguard.app;

public final class VersionChecks {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        check(UpdateVersion.compare("0.2.0", "0.1.0") > 0);
        check(UpdateVersion.compare("0.10.0", "0.9.0") > 0);
        check(UpdateVersion.compare("1.0.0", "0.999.999") > 0);
        check(UpdateVersion.compare("0.2.0", "0.2.0") == 0);
        check(UpdateVersion.compare("0.1.9", "0.2.0") < 0);
        check(UpdateVersion.fromTag("v0.3.3") == null);
        check(UpdateVersion.fromTag("quietguard-v0.3.0-beta") == null);
        check(UpdateVersion.fromTag("quietguard-vbad") == null);
        check("0.3.0".equals(UpdateVersion.fromTag("quietguard-v0.3.0")));
        check(UpdateVersion.fromTag("quietguard-v999999999999.0.0") == null);
        System.out.println("PASS: 10 version comparisons and release tag isolation checks.");
    }
}
