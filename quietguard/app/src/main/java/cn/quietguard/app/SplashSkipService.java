package cn.quietguard.app;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Local, selected-app-only skip actions, limited to a short foreground entry interval. */
public final class SplashSkipService extends AccessibilityService {
    public static volatile boolean connected;
    private static final int MAX_NODES = 1200, MAX_DEPTH = 32, MAX_CANDIDATES = 24;
    private final HandlerThread scanThread = new HandlerThread("quietguard-skip");
    private Handler handler;
    private final SplashScanBudget budget = new SplashScanBudget();
    private final SplashWindow window = new SplashWindow();
    private final SplashDiagnostic diagnostic = new SplashDiagnostic();
    private boolean scanning, pollScheduled;
    private volatile boolean destroyed;
    private volatile String latestEventPkg = "";
    private String eventPkg = "", sessionPkg = "", lastReason = "";
    private long waitingUntil, lastReport, maxScanMs;
    private int maxNodes;
    private boolean limited, sawSkip, sawAd;
    private final Runnable poll = () -> { pollScheduled = false; inspect(); };
    private final Runnable eventTask = () -> {
        if (!enabled()) { cancel(); return; }
        String pkg = latestEventPkg;
        if (!pkg.equals(eventPkg)) {
            eventPkg = pkg;
            waitingUntil = permitted(pkg) ? SystemClock.elapsedRealtime() + SplashWindow.DURATION_MS : 0;
        }
        if (pkg.equals(sessionPkg) && !window.observe(pkg, true, true, SystemClock.elapsedRealtime())) return;
        schedule();
    };

    @Override public void onCreate() {
        super.onCreate(); scanThread.start(); handler = new Handler(scanThread.getLooper());
    }
    @Override protected void onServiceConnected() { connected = true; handler.post(this::cancel); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Package metadata can schedule retries; event text is never read.
        if (destroyed) return;
        latestEventPkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        handler.removeCallbacks(eventTask); handler.post(eventTask);
    }
    private boolean enabled() {
        return connected && !destroyed && Prefs.enabled(this) && Prefs.get(this).getBoolean("skip_consent", false) && !Prefs.selected(this).isEmpty();
    }
    private boolean permitted(String pkg) {
        return enabled() && pkg != null && !pkg.equals(getPackageName()) && !pkg.equals("android")
            && !pkg.equals("com.android.systemui") && Prefs.selected(this).contains(pkg);
    }
    private boolean unlocked() {
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        return lock == null || !lock.isKeyguardLocked();
    }
    private void beginReport(String pkg) {
        if (pkg.equals(sessionPkg)) return;
        window.reset(); sessionPkg = pkg; maxNodes = 0; limited = false; sawSkip = false; sawAd = false;
        lastReason = ""; lastReport = 0; maxScanMs = 0; diagnostic.reset();
        getSharedPreferences("splash_diagnostic",MODE_PRIVATE).edit().remove("target").apply();
    }
    private void report(String reason) { report(reason, 0); }
    private void report(String reason, int priority) {
        if (sessionPkg.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (reason.equals(lastReason) && now - lastReport < 1000) return;
        lastReason = reason; lastReport = now; diagnostic.note(reason, priority);
        // Only fixed diagnostic strings and package metadata persist, never page text or screenshots.
        getSharedPreferences("splash_diagnostic", MODE_PRIVATE).edit()
            .putString("package", sessionPkg).putString("reason", diagnostic.reason())
            .putString("status", diagnostic.status())
            .putInt("nodes", maxNodes).putBoolean("limited", limited)
            .putBoolean("skip", sawSkip).putBoolean("ad", sawAd)
            .putLong("max_scan_ms", maxScanMs)
            .putString("action", window.hasAccepted() ? "控件点击请求被接受"
                : window.hasAttempted() ? "已发出一次控件点击请求" : "本次未发送点击请求")
            .putLong("time", System.currentTimeMillis()).apply();
    }
    private void schedule() {
        if (destroyed || pollScheduled) return;
        pollScheduled = true; handler.postDelayed(poll, budget.delay(SystemClock.elapsedRealtime()));
    }
    private void inspect() {
        if (scanning) return;
        if (budget.delay(SystemClock.elapsedRealtime()) > 0) { schedule(); return; }
        budget.completed(SystemClock.elapsedRealtime());
        if (!enabled() || !unlocked()) { cancel(); return; }
        AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); }
        catch (IllegalStateException | SecurityException e) { report("当前窗口读取被系统中断"); return; }
        if (root == null) {
            budget.completed(SystemClock.elapsedRealtime());
            if (SystemClock.elapsedRealtime() < waitingUntil && permitted(eventPkg)) {
                beginReport(eventPkg); report("暂时无法读取当前窗口，正在重试"); schedule();
            }
            return;
        }
        scanning = true;
        List<Candidate> candidates = new ArrayList<>();
        try {
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (!permitted(pkg)) { window.reset(); sessionPkg = ""; return; }
            beginReport(pkg);
            long now = SystemClock.elapsedRealtime();
            if (!window.observe(pkg, true, true, now)) return;
            Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
            long started = SystemClock.elapsedRealtime();
            boolean plain = Prefs.plainSkip(this, pkg);
            Snapshot snapshot = new Snapshot(); search(root, candidates, snapshot);
            boolean needsTree = candidates.isEmpty() || (!plain && !snapshot.ad
                && candidates.stream().noneMatch(c -> SplashPolicy.countdownLabel(c.label) || SplashPolicy.explicitAdSkip(c.label)));
            if (needsTree && !window.hasAccepted() && budget.allowTree(started)) collect(root, candidates, snapshot);
            maxScanMs = Math.max(maxScanMs, SystemClock.elapsedRealtime() - started);
            now = SystemClock.elapsedRealtime();
            if (!window.observe(pkg, permitted(pkg), enabled(), now)) return;
            maxNodes = Math.max(maxNodes, snapshot.visited); limited |= snapshot.limited;
            sawSkip |= !candidates.isEmpty(); sawAd |= snapshot.ad;
            boolean lacksEvidence = false, wrongBounds = false, originalStillReadable = false;
            boolean rejected = false, sentNow = false;
            long actionDeadline = SystemClock.elapsedRealtime() + 120;
            for (Candidate candidate : candidates) {
                Rect r = new Rect(); candidate.node.getBoundsInScreen(r);
                originalStillReadable |= window.hasAccepted() && window.matchesAcceptedTarget(r.left,r.top,r.right,r.bottom);
                if (!SplashPolicy.candidate(candidate.label, snapshot.ad, plain, r.left,r.top,r.right,r.bottom,
                        bounds.left,bounds.top,bounds.right,bounds.bottom)) {
                    if (!plain && !snapshot.ad && !SplashPolicy.countdownLabel(candidate.label)
                            && !SplashPolicy.explicitAdSkip(candidate.label)) lacksEvidence = true;
                    else wrongBounds = true;
                    continue;
                }
                if (window.hasAttempted()) continue;
                if (SystemClock.elapsedRealtime() >= actionDeadline) {
                    report("本轮按钮核对达到时间预算，等待下一轮",0); rejected = true; break;
                }
                Rect acted = new Rect();
                if (click(candidate,pkg,root.getWindowId(),bounds,snapshot.ad,plain,acted)) {
                    long n = Prefs.get(this).getLong("skip_clicks",0);
                    Prefs.get(this).edit().putLong("skip_clicks",n+1).apply();
                    window.accepted(acted.left,acted.top,acted.right,acted.bottom);
                    report("已向核对后的跳过按钮发送一次点击；不补点，等待界面变化",3);
                    sentNow = true; break;
                }
                rejected = true;
                if (window.hasAttempted()) break;
            }
            if (window.hasAccepted() && !sentNow) {
                if (window.exhausted(now)) {
                    report(originalStillReadable ? "一次点击后原跳过仍可读取；已停止，不补点"
                        : "一次点击后原跳过未再读到；不等同于已清除广告",3);
                    window.finish();
                }
            } else if (!window.hasAttempted() && !rejected) {
                if (lacksEvidence) report("读到跳过，但缺少可读取的倒计时或广告标识；可为此应用开启兼容识别",2);
                else if (wrongBounds) report("读到跳过，但控件大小或边界不符合点击条件",2);
                else if (snapshot.full && !snapshot.limited && snapshot.labelled == 0)
                    report("窗口没有可读取的文字；图片或自绘按钮暂不支持",1);
                else report("本次扫描未读到跳过文字；可能为图片、自绘按钮或未出现",1);
            }
            if (window.observe(pkg, permitted(pkg), enabled(), SystemClock.elapsedRealtime())) schedule();
        } catch (IllegalStateException | SecurityException ignored) {
            report("读取或点击被窗口变化/系统限制中断");
        } finally {
            for (Candidate candidate : candidates) candidate.node.recycle();
            root.recycle(); scanning = false; budget.completed(SystemClock.elapsedRealtime());
        }
    }
    private boolean foregroundMatches(String pkg, int windowId) {
        if (!permitted(pkg) || !unlocked()) return false;
        AccessibilityNodeInfo current = getRootInActiveWindow();
        if (current == null) return false;
        try { return current.getWindowId() == windowId && current.getPackageName() != null && pkg.contentEquals(current.getPackageName()); }
        finally { current.recycle(); }
    }
    private void search(AccessibilityNodeInfo root, List<Candidate> out, Snapshot state) {
        // Ask for relevant text first instead of performing hundreds of child IPC calls on every event.
        for (String term : new String[]{"跳过", "skip", "广告", "ad"}) {
            if (destroyed) return;
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(term);
            if (found == null) continue;
            try {
                int count = Math.min(found.size(), 40);
                for (int i = 0; i < count; i++) {
                    AccessibilityNodeInfo node = found.get(i);
                    if (node != null) { state.visited++; consume(node, out, state); }
                }
                state.limited |= found.size() > count;
            } finally { for (AccessibilityNodeInfo node : found) if (node != null) node.recycle(); }
        }
    }
    private void consume(AccessibilityNodeInfo node, List<Candidate> out, Snapshot state) {
        if (!node.isVisibleToUser()) return;
        String skip = null;
        for (CharSequence value : new CharSequence[]{node.getText(), node.getContentDescription()}) {
            if (value == null || value.length() == 0 || value.length() > 64) continue;
            state.labelled++;
            String label = value.toString(); state.ad |= SplashPolicy.adMarker(label);
            if (node.isEnabled() && SplashPolicy.skipLabel(label)
                    && (skip == null || SplashPolicy.countdownLabel(label) || SplashPolicy.explicitAdSkip(label))) skip = label;
        }
        if (skip == null) return;
        for (Candidate existing : out) if (existing.node.equals(node)) return;
        if (out.size() < MAX_CANDIDATES) out.add(new Candidate(AccessibilityNodeInfo.obtain(node), skip));
        else state.limited = true;
    }
    private void collect(AccessibilityNodeInfo root, List<Candidate> out, Snapshot state) {
        state.full = true;
        long deadline = SystemClock.elapsedRealtime() + SplashScanBudget.TREE_SLICE_MS;
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(AccessibilityNodeInfo.obtain(root), 0));
        int enqueued = state.visited + 1;
        try {
            while (!queue.isEmpty()) {
                if (destroyed || SystemClock.elapsedRealtime() >= deadline) { state.limited = true; break; }
                NodeDepth item = queue.removeFirst();
                AccessibilityNodeInfo node = item.node;
                try {
                    state.visited++;
                    consume(node, out, state);
                    // An invisible container can have visible descendants; do not prune it wholesale.
                    int children = node.getChildCount();
                    for (int i = 0; i < children; i++) {
                        if (item.depth >= MAX_DEPTH || enqueued >= MAX_NODES || destroyed
                                || SystemClock.elapsedRealtime() >= deadline) { state.limited = true; break; }
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) { queue.addLast(new NodeDepth(child, item.depth + 1)); enqueued++; }
                    }
                } finally { node.recycle(); }
            }
        } finally { for (NodeDepth item : queue) item.node.recycle(); }
    }
    private boolean click(Candidate candidate, String pkg, int windowId, Rect screen,
                          boolean ad, boolean plain, Rect acted) {
        if (!foregroundMatches(pkg,windowId)) {
            report("当前活动窗口变化，未发送点击",2); return false;
        }
        SplashButton.Info bounds = new SplashButton.Info();
        bounds.left=screen.left; bounds.top=screen.top; bounds.right=screen.right; bounds.bottom=screen.bottom;
        try (AndroidNode source = new AndroidNode(AccessibilityNodeInfo.obtain(candidate.node));
             SplashButton.Result target = SplashButton.resolve(source,pkg,windowId,bounds,ad,plain,
                 getResources().getDisplayMetrics().density)) {
            if (target.node == null) { report(target.reason,2); return false; }
            if (!foregroundMatches(pkg,windowId) || !enabled() || !permitted(pkg)) {
                report("点击前应用或窗口变化，未发送点击",2); return false;
            }
            AndroidNode control = (AndroidNode)target.node;
            // Resolve again after the foreground IPC so stale container contents cannot authorize input.
            try (SplashButton.Result fresh = SplashButton.resolve(source,pkg,windowId,bounds,ad,plain,
                    getResources().getDisplayMetrics().density)) {
                if (fresh.node == null || !fresh.node.same(control)) {
                    report("点击前跳过按钮结构变化，未发送点击",2); return false;
                }
                AndroidNode action = (AndroidNode)fresh.node;
                Rect rect = new Rect(); action.node.getBoundsInScreen(rect);
                if (!permitted(pkg) || !unlocked()) {
                    report("点击前防护被暂停或手机锁屏，未发送点击",2); return false;
                }
                if (!window.attempted(SystemClock.elapsedRealtime())) return false;
                acted.set(rect);
                getSharedPreferences("splash_diagnostic",MODE_PRIVATE).edit()
                    .putString("target",fresh.container ? "仅含跳过内容的小按钮" : "跳过文字控件自身").apply();
                if (action.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                report("系统未接受这一次按钮点击请求，已停止；不补点",3);
                window.finish(); return false;
            }
        }
    }
    private static final class AndroidNode implements SplashButton.Node {
        final AccessibilityNodeInfo node;
        AndroidNode(AccessibilityNodeInfo node) { this.node=node; }
        @Override public SplashButton.Node copy() { return new AndroidNode(AccessibilityNodeInfo.obtain(node)); }
        @Override public SplashButton.Info read() {
            if (!node.refresh()) return null;
            SplashButton.Info i = new SplashButton.Info();
            CharSequence text=node.getText(), description=node.getContentDescription(), pkg=node.getPackageName();
            i.text=text==null ? null : text.toString(); i.description=description==null ? null : description.toString();
            i.pkg=pkg==null ? null : pkg.toString(); i.window=node.getWindowId();
            Rect r=new Rect(); node.getBoundsInScreen(r);
            i.left=r.left; i.top=r.top; i.right=r.right; i.bottom=r.bottom;
            i.visible=node.isVisibleToUser(); i.enabled=node.isEnabled(); i.clickable=node.isClickable();
            i.action=node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            i.editable=node.isEditable(); i.scrollable=node.isScrollable(); return i;
        }
        @Override public SplashButton.Node parent() {
            AccessibilityNodeInfo parent=node.getParent(); return parent==null ? null : new AndroidNode(parent);
        }
        @Override public int children() { return node.getChildCount(); }
        @Override public SplashButton.Node child(int index) {
            AccessibilityNodeInfo child=node.getChild(index); return child==null ? null : new AndroidNode(child);
        }
        @Override public boolean same(SplashButton.Node other) {
            return other instanceof AndroidNode && node.equals(((AndroidNode)other).node);
        }
        @Override public void close() { node.recycle(); }
    }
    private void cancel() {
        handler.removeCallbacks(poll); pollScheduled = false; budget.reset(); window.reset();
        sessionPkg = ""; eventPkg = ""; waitingUntil = 0;
    }
    @Override public void onInterrupt() { handler.post(this::cancel); }
    @Override public boolean onUnbind(Intent intent) { connected = false; handler.post(this::cancel); return super.onUnbind(intent); }
    @Override public void onDestroy() {
        connected = false; destroyed = true; handler.removeCallbacksAndMessages(null); scanThread.quitSafely(); super.onDestroy();
    }
    private static final class Snapshot { boolean ad, limited, full; int visited, labelled; }
    private static final class NodeDepth {
        final AccessibilityNodeInfo node; final int depth;
        NodeDepth(AccessibilityNodeInfo node, int depth) { this.node = node; this.depth = depth; }
    }
    private static final class Candidate {
        final AccessibilityNodeInfo node; final String label;
        Candidate(AccessibilityNodeInfo node, String label) { this.node = node; this.label = label; }
    }
}
