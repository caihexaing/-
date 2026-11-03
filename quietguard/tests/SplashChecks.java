package cn.quietguard.app;

public final class SplashChecks {
    static int checks;
    static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    static boolean candidate(String text, boolean ad, int l, int t, int r, int b) {
        return SplashPolicy.candidate(text, ad, l,t,r,b, 0,0,922,2048);
    }
    public static void main(String[] args) {
        check(candidate("4s | 跳过",false,704,138,890,201), "screenshot countdown skip");
        check(candidate("4s｜跳过",false,704,138,890,201), "full width divider");
        check(candidate("跳过 (5秒)",false,704,138,890,201), "Chinese countdown suffix");
        check(candidate("Skip Ad 5s",false,704,138,890,201), "English countdown");
        check(candidate("跳过",true,704,138,890,201), "separate ad marker");
        check(candidate("关闭广告",true,704,138,890,201), "explicit ad close");
        check(!candidate("跳过",false,704,138,890,201), "plain tutorial skip without ad marker");
        check(!candidate("跳过此步骤",true,704,138,890,201), "business tutorial");
        check(!candidate("5秒后跳过",true,704,138,890,201), "not yet skippable wording");
        check(!candidate("跳过并领取奖励",true,704,138,890,201), "reward CTA");
        check(!candidate("启动",true,704,138,890,201), "promoted app launch CTA");
        check(!candidate("关闭",true,704,138,890,201), "generic close");
        check(!candidate("×",true,704,138,890,201), "ambiguous X");
        check(candidate("4s|跳过",false,50,138,200,201), "top left skip");
        check(candidate("4s|跳过",false,704,900,890,965), "middle right skip");
        check(candidate("跳过",true,30,1900,210,1960), "bottom left with ad marker");
        check(candidate("跳过",true,704,1900,890,1960), "bottom right with ad marker");
        check(candidate("Skip Ad 5s",false,370,990,550,1060), "center countdown skip");
        check(candidate("关闭广告",true,370,1900,550,1960), "bottom center close ad");
        check(!candidate("跳过",false,30,1900,210,1960), "bottom tutorial without evidence");
        check(!candidate("×",true,30,1900,210,1960), "bottom ambiguous close");
        check(!candidate("4s|跳过",false,100,900,800,965), "oversized width anywhere");
        check(!candidate("4s|跳过",false,100,900,280,1400), "oversized height anywhere");
        check(!candidate("4s|跳过",false,30,2000,210,2060), "outside bottom edge");
        check(SplashPolicy.candidate("跳过",true,120,2000,300,2060,100,100,1022,2148), "offset bottom left");
        check(!candidate("4s|跳过",false,0,0,922,2048), "full-screen parent");
        check(!candidate("4s|跳过",false,700,100,700,170), "zero width");
        check(!candidate("4s|跳过",false,700,-10,890,170), "off screen");
        check(!candidate("4s|跳过",false,700,0,950,170), "outside right edge");
        check(!SplashPolicy.candidate("4s|跳过",false,0,0,2,2, 0,0,0,0), "invalid window");
        check(SplashPolicy.candidate("4s|跳过",false,804,238,990,301,100,100,1022,2148), "offset window coordinates");
        check(!SplashPolicy.skipLabel(null), "null label");
        check(!SplashPolicy.skipLabel("跳过" + new String(new char[80]).replace('\0',' ')), "long label limit");
        check(SplashPolicy.adMarker("广告"), "ad marker");
        check(SplashPolicy.adMarker("AD"), "English marker");
        check(!SplashPolicy.adMarker("我的广告设置"), "not an arbitrary ad-related page");

        SplashWindow w = new SplashWindow();
        check(!w.observe("target",true,false,100), "master disabled");
        check(!w.canAttempt(100), "no actions while disabled");
        check(!w.observe("other",false,true,100), "unselected app");
        check(w.observe("target",true,true,100), "target foreground entry");
        check(w.canAttempt(100), "first attempt"); w.attempted(100);
        check(!w.canAttempt(999), "retry interval");
        check(w.canAttempt(1100), "second attempt allowed"); w.attempted(1100);
        check(!w.canAttempt(2500), "two attempt cap");
        check(!w.observe("target",true,true,15101), "startup window expires");
        check(!w.observe("target",true,true,17000), "same-package events do not renew window");
        check(!w.observe("launcher",false,true,18000), "foreground exit resets");
        check(w.observe("target",true,true,19000), "new launch window");
        w.finish(); check(!w.canAttempt(19100), "accepted action ends session");
        check(!w.observe("target",true,true,19200), "accepted action stays finished");
        w.reset(); check(w.observe("target",true,true,20000), "explicit reset");
        check(!w.canAttempt(19999), "monotonic time regression");
        check(w.canAttempt(35000), "end boundary"); check(!w.canAttempt(35001), "after boundary");
        check(!w.observe("target",false,true,35002), "deselect cancels");
        check(!w.canAttempt(35002), "no action after deselect");
        System.out.println("PASS: " + checks + " screenshot matching, misclick prevention and startup window checks.");
    }
}
