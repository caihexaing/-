package cn.quietguard.app;

public final class TextTapChecks {
    private static int checks;
    private static void check(boolean ok,String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static SplashButton.Info info(String text,int l,int t,int r,int b) {
        SplashButton.Info i=new SplashButton.Info(); i.text=text; i.pkg="target";i.window=1;
        i.visible=true;i.enabled=true;i.left=l;i.top=t;i.right=r;i.bottom=b;return i;
    }
    private static SplashButton.Info label() { return info("跳过",800,100,880,140); }
    private static SplashButton.Info screen() { return info(null,0,0,1000,2000); }
    private static boolean valid(SplashButton.Info i,SplashButton.Info screen) {
        return SplashTextTap.valid(i,screen,"target",1,true,false,2f);
    }
    private static boolean observe(SplashTextTap tap,SplashButton.Info i,long identity,boolean on,long now) {
        return tap.observe(i,screen(),"target",1,identity,on,true,false,2f,now);
    }
    public static void main(String[] args) {
        SplashTextTap tap=new SplashTextTap(); SplashButton.Info i=label();
        check(valid(i,screen()),"small labelled text eligible");
        check(!observe(tap,i,1,false,100),"explicit per app opt-in required");
        check(!observe(tap,i,1,true,100),"first scan never authorizes gesture");
        check(!observe(tap,i,1,true,449),"too soon is not independent confirmation");
        check(observe(tap,i,1,true,450),"second stable scan after 350ms eligible");
        check(observe(tap,i,1,true,600),"500ms production scan interval compatible");
        tap.reset();check(!tap.matches(i,screen(),"target",1,1),"reset discards pending location");
        check(!observe(tap,i,1,true,100),"reset needs first scan again");
        check(!observe(tap,i,2,true,600),"different node at same position resets");
        check(observe(tap,i,2,true,1100),"new node gets own stable interval");
        tap.reset();observe(tap,i,1,true,100);i.left++;
        check(!observe(tap,i,1,true,600),"one pixel movement resets");
        check(observe(tap,i,1,true,1100),"new position can stabilize");
        i=label();tap.reset();observe(tap,i,1,true,100);
        check(!observe(tap,i,1,true,1401),"stale observation gap resets");
        check(observe(tap,i,1,true,1901),"new observation after gap may stabilize");
        tap.reset();observe(tap,i,1,true,100);
        check(!observe(tap,i,1,true,99),"clock regression resets");
        check(!observe(tap,i,1,false,600),"turning mode off cancels pending observation");
        check(!observe(tap,i,1,true,1100),"reenabling requires fresh scan");
        tap.reset();observe(tap,i,1,true,100);
        SplashButton.Info changed=screen();changed.right=999;
        check(!tap.observe(i,changed,"target",1,1,true,true,false,2f,600),"window resize resets");
        check(!tap.matches(i,screen(),"target",1,1),"old screen does not match after resize");
        tap.reset();observe(tap,i,1,true,100);
        i.text="4s|跳过";
        check(observe(tap,i,1,true,600),"countdown update at stable node accepted");
        i.text="3s|跳过";
        check(valid(i,screen()),"countdown labels supported");
        check(!SplashTextTap.valid(label(),screen(),"target",1,false,false,2f),"plain tutorial evidence still required");
        check(SplashTextTap.valid(label(),screen(),"target",1,false,true,2f),"existing plain skip setting honored");
        i=label();i.text="下载";check(!valid(i,screen()),"download never eligible");
        i=label();i.description="立即进入";check(!valid(i,screen()),"conflicting description refused");
        i=label();i.text="跳过并领取奖励";check(!valid(i,screen()),"reward CTA refused");
        i=label();i.visible=false;check(!valid(i,screen()),"invisible text refused");
        i=label();i.enabled=false;check(!valid(i,screen()),"disabled text refused");
        i=label();i.editable=true;check(!valid(i,screen()),"editable text refused");
        i=label();i.scrollable=true;check(!valid(i,screen()),"scrollable text refused");
        i=label();i.pkg="other";check(!valid(i,screen()),"cross application refused");
        i=label();i.window=2;check(!valid(i,screen()),"cross window refused");
        i=label();i.left=0;i.top=0;i.right=1000;i.bottom=2000;
        check(!valid(i,screen()),"full ad region never eligible");
        i=label();i.left=700;i.right=1001;check(!valid(i,screen()),"offscreen text refused");
        i=label();i.right=i.left;check(!valid(i,screen()),"zero width refused");
        i=label();i.right=i.left+15;check(!valid(i,screen()),"tiny text width refused");
        i=label();i.bottom=i.top+15;check(!valid(i,screen()),"tiny text height refused");
        i=label();i.left=600;i.right=901;check(!valid(i,screen()),"wide text region refused");
        i=label();i.bottom=i.top+129;check(!valid(i,screen()),"tall text region refused");
        check(!valid(null,screen()),"unavailable node refused");
        check(!valid(label(),null),"unavailable window refused");
        check(!SplashTextTap.valid(label(),screen(),"target",1,true,false,Float.NaN),"invalid density refused");
        check(!SplashTextTap.valid(label(),screen(),"target",1,true,false,0),"zero density refused");
        tap.reset();i=label();observe(tap,i,1,true,100);i.description="下载";
        check(!observe(tap,i,1,true,600),"conflict before second scan cancels");
        i.description=null;check(!observe(tap,i,1,true,1100),"after invalid scan fresh confirmation required");
        SplashWindow window=new SplashWindow();window.observe("target",true,true,100);
        check(window.attempted(600),"gesture reserves shared action quota");
        check(!window.attempted(601),"gesture cannot be followed by semantic click or retry");
        check(!window.canAttempt(1100),"cancelled or rejected request still consumes quota");
        System.out.println("PASS: "+checks+" text gesture stability, consent, geometry and shared quota checks.");
    }
}
