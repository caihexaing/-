package cn.quietguard.app;

import java.util.ArrayList;
import java.util.List;

/** Synthetic accessibility trees exercise the production resolver, not device click behavior. */
public final class ButtonChecks {
    private static int checks, open;
    private static void check(boolean value,String name) {
        checks++; if (!value) throw new AssertionError(name);
    }
    private static class Data {
        SplashButton.Info info = new SplashButton.Info();
        Data parent; List<Data> children = new ArrayList<>();
        boolean stale, missingChild; int advertisedChildren = -1;
        Data(String text,int l,int t,int r,int b) {
            info.text=text; info.pkg="target"; info.window=1;
            info.left=l; info.top=t; info.right=r; info.bottom=b;
            info.visible=true; info.enabled=true;
        }
        Data add(Data child) { children.add(child); child.parent=this; return this; }
        Data action() { info.clickable=true; info.action=true; return this; }
    }
    private static final class Node implements SplashButton.Node {
        final Data data; boolean closed;
        Node(Data data) { this.data=data; open++; }
        void valid() { if (closed) throw new AssertionError("use after recycle"); }
        @Override public SplashButton.Node copy() { valid(); return new Node(data); }
        @Override public SplashButton.Info read() { valid(); return data.stale ? null : data.info; }
        @Override public SplashButton.Node parent() { valid(); return data.parent==null ? null : new Node(data.parent); }
        @Override public int children() { valid(); return data.advertisedChildren>=0 ? data.advertisedChildren : data.children.size(); }
        @Override public SplashButton.Node child(int i) { valid(); return data.missingChild ? null : new Node(data.children.get(i)); }
        @Override public boolean same(SplashButton.Node other) { valid(); return other instanceof Node && data==((Node)other).data; }
        @Override public void close() { valid(); closed=true; open--; }
    }
    private static SplashButton.Info screen() {
        SplashButton.Info info=new SplashButton.Info(); info.right=1000; info.bottom=2000; return info;
    }
    private static Data label() { return new Data("跳过",800,100,880,140); }
    private static Data button(Data label) { return new Data(null,760,90,900,150).action().add(label); }
    private static void expect(Data label,Data expected,boolean container,boolean ad,String name) {
        try (Node node=new Node(label); SplashButton.Result result=SplashButton.resolve(node,"target",1,screen(),ad,false,2f)) {
            check(expected==null ? result.node==null : result.node instanceof Node && ((Node)result.node).data==expected,name);
            if (expected!=null) check(result.container==container,name+" target type");
            else check(!result.reason.isEmpty(),name+" diagnostic");
        }
        check(open==0,name+" node ownership");
    }
    public static void main(String[] args) {
        Data l=label().action(); expect(l,l,false,true,"direct semantic action retained");
        l=label().action(); l.info.clickable=false;
        expect(l,l,false,true,"advertised action does not require redundant clickable flag");
        l=label(); Data p=button(l);
        expect(l,p,true,true,"nonclickable skip text resolves small button");
        p.add(new Data("5s",765,100,790,140));
        expect(l,p,true,true,"split countdown and skip button");
        l=label(); Data wrapper=new Data(null,795,95,885,145).add(l); p=button(wrapper);
        expect(l,p,true,true,"one nonclickable wrapper inside button");
        l=label(); wrapper=new Data(null,795,95,885,145).add(l);
        Data wrapper2=new Data(null,790,95,890,145).add(wrapper); button(wrapper2);
        expect(l,null,false,true,"third ancestor never used");
        l=label(); p=new Data(null,0,0,1000,2000).action().add(l);
        expect(l,null,false,true,"full ad container refused");
        l=label(); p=button(l); p.info.right=999;
        expect(l,null,false,true,"excessive button padding refused");
        l=label(); p=button(l); p.info.text="立即进入";
        expect(l,null,false,true,"promotion container refused");
        l=label(); p=button(l); p.add(new Data("下载",765,100,790,140));
        expect(l,null,false,true,"promotion sibling refused");
        l=label(); p=button(l); p.info.description="广告详情";
        expect(l,null,false,true,"conflicting outer description refused");
        l=label(); l.info.description="立即进入"; button(l);
        expect(l,null,false,true,"conflicting skip description refused");
        l=label(); p=button(l); p.add(new Data(null,765,100,790,140));
        expect(l,null,false,true,"unlabelled image leaf refused");
        l=label(); p=button(l); p.add(new Data("5s",765,100,790,140).action());
        expect(l,null,false,true,"other interactive target refused");
        l=label(); p=button(l); p.add(new Data("跳过",765,100,790,140));
        expect(l,null,false,true,"ambiguous two skip targets refused");
        l=label(); p=button(l); p.info.window=2;
        expect(l,null,false,true,"parent from different window refused");
        l=label(); p=button(l); p.info.pkg="other";
        expect(l,null,false,true,"parent from different app refused");
        l=label(); p=button(l); p.info.scrollable=true;
        expect(l,null,false,true,"scrollable parent refused");
        l=label(); p=button(l); p.info.editable=true;
        expect(l,null,false,true,"editable parent refused");
        l=label(); p=button(l); p.info.enabled=false;
        expect(l,null,false,true,"disabled parent refused");
        l=label(); p=button(l); p.stale=true;
        expect(l,null,false,true,"stale parent refused");
        l=label(); p=button(l); l.stale=true;
        expect(l,null,false,true,"stale label refused");
        l=label(); p=button(l); l.info.visible=false;
        expect(l,null,false,true,"invisible label refused");
        l=label(); p=button(l); p.missingChild=true;
        expect(l,null,false,true,"partially readable subtree refused");
        l=label(); p=button(l); p.advertisedChildren=100;
        expect(l,null,false,true,"unbounded subtree refused");
        l=label(); p=button(l); p.children.clear();
        expect(l,null,false,true,"label absent from parent subtree refused");
        l=label(); p=button(l); p.children.add(p);
        expect(l,null,false,true,"cyclic subtree refused");
        l=label(); l.parent=l;
        expect(l,null,false,true,"cyclic parent refused");
        l=label(); p=button(l); p.info.action=false;
        expect(l,null,false,true,"clickable flag alone never authorizes action");
        l=label(); p=button(l);
        expect(l,null,false,false,"plain tutorial remains off by default");
        l.info.text="4s|跳过";
        expect(l,p,true,false,"countdown supplies evidence for small button");
        l=label(); p=button(l); p.add(new Data("广告详情",765,100,790,140));
        expect(l,null,false,true,"ad details sibling refused");
        l=label(); p=button(l); p.add(new Data(" ".repeat(65),765,100,790,140));
        expect(l,null,false,true,"oversized leaf text refused");
        SplashDiagnostic d=new SplashDiagnostic();
        d.note("no text",1); d.note("button rejected",2); d.note("window unavailable",0);
        check(d.reason().equals("button rejected"),"window retry cannot erase rejection");
        check(d.status().equals("window unavailable"),"latest window state retained separately");
        d.note("no text",1); check(d.reason().equals("button rejected"),"later homepage cannot erase rejection");
        d.note("click accepted",3); d.note("another rejection",2);
        check(d.reason().equals("click accepted"),"click result outranks rejection");
        d.note("post-click observation",3);
        check(d.reason().equals("post-click observation"),"same priority decision updates");
        d.reset(); d.note("window unavailable",0);
        check(d.reason().equals("window unavailable"),"new session clears previous decision");
        System.out.println("PASS: "+checks+" semantic button tree, ownership and diagnostic retention checks.");
    }
}
