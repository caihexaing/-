package cn.quietguard.app;

/** Requires two independent scans of the same small, explicitly labelled node. No coordinates persist. */
public final class SplashTextTap {
    public static final long MIN_STABLE_MS=350, MAX_GAP_MS=1300;
    private long seenAt, identity;
    private String pkg="";
    private int window, left,top,right,bottom, screenLeft,screenTop,screenRight,screenBottom;
    private boolean seen;
    public static boolean valid(SplashButton.Info i, SplashButton.Info screen, String pkg,int window,
                                boolean ad,boolean plain,float density) {
        if (i==null || screen==null || !i.visible || !i.enabled || i.editable || i.scrollable
                || !pkg.equals(i.pkg) || i.window!=window || !Float.isFinite(density) || density<=0) return false;
        String label=SplashPolicy.actionLabel(i.text,i.description);
        if (label==null || !SplashPolicy.candidate(label,ad,plain,i.left,i.top,i.right,i.bottom,
                screen.left,screen.top,screen.right,screen.bottom)) return false;
        long w=(long)i.right-i.left, h=(long)i.bottom-i.top;
        long sw=(long)screen.right-screen.left, sh=(long)screen.bottom-screen.top;
        return w>=8*density && h>=8*density && w<=180*density && h<=64*density
            && w<=sw*.30 && h<=sh*.08;
    }
    public boolean observe(SplashButton.Info i,SplashButton.Info screen,String pkg,int window,long identity,
                           boolean enabled,boolean ad,boolean plain,float density,long now) {
        if (!enabled || !valid(i,screen,pkg,window,ad,plain,density)) { reset(); return false; }
        if (!matches(i,screen,pkg,window,identity) || now<seenAt || now-seenAt>MAX_GAP_MS) {
            seen=true; seenAt=now; this.identity=identity; this.pkg=pkg; this.window=window;
            left=i.left;top=i.top;right=i.right;bottom=i.bottom;
            screenLeft=screen.left;screenTop=screen.top;screenRight=screen.right;screenBottom=screen.bottom;
            return false;
        }
        return now-seenAt>=MIN_STABLE_MS;
    }
    public boolean matches(SplashButton.Info i,SplashButton.Info screen,String pkg,int window,long identity) {
        return seen && this.identity==identity && this.pkg.equals(pkg) && this.window==window
            && left==i.left && top==i.top && right==i.right && bottom==i.bottom
            && screenLeft==screen.left && screenTop==screen.top && screenRight==screen.right && screenBottom==screen.bottom;
    }
    public void reset() { seen=false; pkg=""; }
}
