package cn.quietguard.app;

public final class AccessibilityChecks {
    private static int checks;
    private static void check(boolean value,String name) { checks++; if (!value) throw new AssertionError(name); }
    public static void main(String[] args) {
        String full="cn.quietguard.app/cn.quietguard.app.SplashSkipService", brief="cn.quietguard.app/.SplashSkipService";
        String talkback="com.google.android.marvin.talkback/.TalkBackService", other="other/other.Service";
        check(AccessibilitySelection.add(null,full,brief).equals(full),"unset selection");
        check(AccessibilitySelection.add("",full,brief).equals(full),"empty selection");
        check(AccessibilitySelection.add(full,full,brief).equals(full),"already full component no duplicate");
        check(AccessibilitySelection.add(brief,full,brief).equals(brief),"already short component preserved");
        check(AccessibilitySelection.add(talkback,full,brief).equals(talkback+":"+full),"preserve assistive service");
        check(AccessibilitySelection.add(talkback+":"+other,full,brief).equals(talkback+":"+other+":"+full),"preserve multiple services");
        check(AccessibilitySelection.add(talkback+":"+brief+":"+other,full,brief).equals(talkback+":"+brief+":"+other),"existing middle selection unchanged");
        check(AccessibilitySelection.add(talkback+":",full,brief).equals(talkback+":"+full),"trailing delimiter preserved");
        check(AccessibilitySelection.add(":"+other,full,brief).equals(":"+other+":"+full),"do not rewrite unfamiliar entries");
        check(!AccessibilitySelection.contains("prefix"+full,full,brief),"component prefix is not exact selection");
        check(!AccessibilitySelection.contains(full+"Suffix",full,brief),"component suffix is not exact selection");
        check(!AccessibilitySelection.contains(null,full,brief),"null contains false");
        check(!AccessibilitySelection.contains("",full,brief),"empty contains false");
        check(AccessibilitySelection.contains(talkback+":"+full,full,brief),"detect full service after another");
        check(AccessibilitySelection.contains(brief+":"+other,full,brief),"detect short service before another");
        String once=AccessibilitySelection.add(talkback,full,brief);
        check(AccessibilitySelection.add(once,full,brief).equals(once),"repeat enable idempotent");
        System.out.println("PASS: "+checks+" accessibility selection preservation and idempotence checks.");
    }
}
