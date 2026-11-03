package cn.quietguard.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Path;
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
    private boolean scanning, pollScheduled;
    private volatile boolean destroyed;
    private volatile String latestEventPkg = "";
    private String eventPkg = "", sessionPkg = "", lastReason = "";
    private long waitingUntil, lastReport, acceptedAt, maxScanMs;
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
        sessionPkg = pkg; maxNodes = 0; limited = false; sawSkip = false; sawAd = false;
        lastReason = ""; lastReport = 0; acceptedAt = 0; maxScanMs = 0;
    }
    private void report(String reason) {
        if (sessionPkg.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (reason.equals(lastReason) && now - lastReport < 1000) return;
        lastReason = reason; lastReport = now;
        // Only fixed diagnostic strings and package metadata persist, never page text or screenshots.
        getSharedPreferences("splash_diagnostic", MODE_PRIVATE).edit()
            .putString("package", sessionPkg).putString("reason", reason)
            .putInt("nodes", maxNodes).putBoolean("limited", limited)
            .putBoolean("skip", sawSkip).putBoolean("ad", sawAd)
            .putLong("max_scan_ms", maxScanMs)
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
            Candidate target = null;
            boolean lacksEvidence = false, wrongBounds = false, originalStillReadable = false;
            for (Candidate candidate : candidates) {
                Rect r = new Rect(); candidate.node.getBoundsInScreen(r);
                originalStillReadable |= window.hasAccepted() && window.retryTarget(r.left, r.top, r.right, r.bottom);
                if (!SplashPolicy.candidate(candidate.label, snapshot.ad, plain, r.left, r.top, r.right, r.bottom,
                        bounds.left, bounds.top, bounds.right, bounds.bottom)) {
                    if (!plain && !snapshot.ad && !SplashPolicy.countdownLabel(candidate.label)
                            && !SplashPolicy.explicitAdSkip(candidate.label)) lacksEvidence = true;
                    else wrongBounds = true;
                    continue;
                }
                if (window.retryTarget(r.left, r.top, r.right, r.bottom)) { target = candidate; break; }
            }
            if (target == null) {
                if (window.hasAccepted()) {
                    if (originalStillReadable) report("原跳过控件仍可读取，但缺少当前广告证据，未继续点击");
                    else if (snapshot.limited) report("已发送点击请求，当前窗口不足以判断原按钮是否还在");
                    else if (now - acceptedAt >= 500) {
                        report("点击后原跳过控件未再读到；不等同于已清除广告"); window.finish();
                    }
                } else if (lacksEvidence) report("读到跳过，但缺少可读取的倒计时或广告标识；可为此应用开启兼容识别");
                else if (wrongBounds) report("读到跳过，但控件大小或边界不符合点击条件");
                else if (snapshot.full && !snapshot.limited && snapshot.labelled == 0) report("窗口没有可读取的文字；图片或自绘按钮暂不支持");
                else report("本次扫描未读到跳过文字；可能为图片、自绘按钮或未出现");
            } else if (window.canAttempt(now)) {
                if (!foregroundMatches(pkg)) return;
                boolean retryGesture = window.hasAccepted();
                window.attempted(now);
                Rect acted = new Rect();
                if (click(target, bounds, snapshot.ad, plain, retryGesture, acted)) {
                    long n = Prefs.get(this).getLong("skip_clicks", 0);
                    Prefs.get(this).edit().putLong("skip_clicks", n + 1).apply();
                    window.accepted(acted.left, acted.top, acted.right, acted.bottom); acceptedAt = now;
                    report(retryGesture ? "同一跳过控件仍存在，已补发一次手势；等待界面变化"
                        : "系统接受了点击请求，正在检查原跳过控件是否还在");
                } else report("找到符合条件的跳过，但控件失效或系统未接受点击");
            } else if (window.exhausted(now)) {
                report("两次尝试后原跳过控件仍可读取；尚未确认跳过成功"); window.finish();
            }
            if (window.observe(pkg, permitted(pkg), enabled(), SystemClock.elapsedRealtime())) schedule();
        } catch (IllegalStateException | SecurityException ignored) {
            report("读取或点击被窗口变化/系统限制中断");
        } finally {
            for (Candidate candidate : candidates) candidate.node.recycle();
            root.recycle(); scanning = false; budget.completed(SystemClock.elapsedRealtime());
        }
    }
    private boolean foregroundMatches(String pkg) {
        if (!permitted(pkg) || !unlocked()) return false;
        AccessibilityNodeInfo current = getRootInActiveWindow();
        if (current == null) return false;
        try { return current.getPackageName() != null && pkg.contentEquals(current.getPackageName()); }
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
    private boolean click(Candidate candidate, Rect windowBounds, boolean ad, boolean plain,
                          boolean gestureOnly, Rect acted) {
        AccessibilityNodeInfo node = candidate.node;
        if (!node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) return false;
        CharSequence text = node.getText(), desc = node.getContentDescription();
        // Countdown text can change between reads; validate the current meaning, not stale text.
        String label = text != null && SplashPolicy.skipLabel(text.toString()) ? text.toString()
            : desc != null && SplashPolicy.skipLabel(desc.toString()) ? desc.toString() : "";
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (!window.retryTarget(bounds.left, bounds.top, bounds.right, bounds.bottom)
                || !SplashPolicy.candidate(label, ad, plain, bounds.left, bounds.top, bounds.right, bounds.bottom,
                    windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)) return false;
        acted.set(bounds);
        if (!gestureOnly && node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo parent = gestureOnly ? null : node.getParent();
        if (parent != null) {
            try {
                Rect p = new Rect(); parent.getBoundsInScreen(p);
                if (parent.isVisibleToUser() && parent.isEnabled() && parent.isClickable() && p.contains(bounds)
                    && p.width() <= bounds.width() * 2 + 24 && p.height() <= bounds.height() * 2 + 24
                    && SplashPolicy.candidate(label, ad, plain, p.left, p.top, p.right, p.bottom,
                        windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)
                    && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            } finally { parent.recycle(); }
        }
        Path path = new Path(); path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 40)).build(), null, null);
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
