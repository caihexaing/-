package cn.quietguard.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
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
    private SplashOcr ocr;
    private final SplashScanBudget budget = new SplashScanBudget();
    private final SplashWindow window = new SplashWindow();
    private final SplashTextTap textTap = new SplashTextTap();
    private final SplashOcrPolicy ocrPolicy = new SplashOcrPolicy();
    private final Runnable verify = this::verifyTextTap;
    private AndroidNode tapAnchor;
    /** Session in which a clicked skip control proved inert and one coordinate fallback was spent. */
    private long inertTapSession = -1;
    /** The one retry allowed after an OCR touch produced no visible change. */
    private boolean ocrRetried;
    private float lastTouchX, lastTouchY;
    private long tapIdentity, sessionId;
    private String pendingPkg = "";
    private int pendingWindow;
    private final SplashDiagnostic diagnostic = new SplashDiagnostic();
    private boolean scanning, pollScheduled;
    private volatile boolean destroyed;
    private volatile String latestEventPkg = "";
    private String eventPkg = "", sessionPkg = "", lastReason = "";
    private long waitingUntil, lastReport, maxScanMs;
    private int maxNodes;
    private boolean limited, sawSkip, sawAd;
    private boolean ocrInFlight, ocrAdSeen;
    private int ocrFrames, ocrProbes, ocrPass;
    private boolean ocrSawSkip;
    private long lastOcrDone;
    private long lastOcrAt, lastOcrProbeAt;
    private String ocrStatus = "未运行";
    private final Runnable poll = () -> { pollScheduled = false; inspect(); };
    private final Runnable eventTask = () -> {
        if (!enabled()) { cancel(); return; }
        String pkg = latestEventPkg;
        if (!pkg.equals(eventPkg)) {
            eventPkg = pkg;
            budget.foreground(pkg, permitted(pkg), SystemClock.elapsedRealtime());
            waitingUntil = permitted(pkg) ? SystemClock.elapsedRealtime() + SplashWindow.DURATION_MS : 0;
        }
        if (pkg.equals(sessionPkg) && !window.observe(pkg, true, true, SystemClock.elapsedRealtime())) return;
        schedule();
    };

    @Override public void onCreate() {
        super.onCreate(); scanThread.start(); handler = new Handler(scanThread.getLooper()); ocr = new SplashOcr(this, handler);
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
        budget.foreground(pkg, permitted(pkg), SystemClock.elapsedRealtime());
        if (pkg.equals(sessionPkg)) return;
        window.reset(); resetTextTap(); sessionId++;
        handler.removeCallbacks(verify); pendingPkg = ""; pendingWindow = 0;
        sessionPkg = pkg; maxNodes = 0; limited = false; sawSkip = false; sawAd = false;
        ocrPolicy.clear(); ocrFrames = 0; ocrProbes = 0; ocrAdSeen = false; ocrPass = 0; lastOcrDone = 0; ocrSawSkip = false; ocrRetried = false;
        lastOcrAt = 0; lastOcrProbeAt = 0; ocrStatus = Prefs.screenshotOcrConsent(this) ? "等待控件树扫描" : "未授权";
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
            .putString("ocr_status", ocrStatus).putInt("ocr_frames", ocrFrames)
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
            if (!permitted(pkg)) { budget.foreground(pkg, false, SystemClock.elapsedRealtime()); window.reset(); resetTextTap(); sessionId++; sessionPkg = ""; return; }
            beginReport(pkg);
            long now = SystemClock.elapsedRealtime();
            if (!window.observe(pkg, true, true, now)) return;
            Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
            long started = SystemClock.elapsedRealtime();
            boolean plain = Prefs.plainSkip(this, pkg);
            Snapshot snapshot = new Snapshot(); search(root, candidates, snapshot, plain);
            boolean needsTree = candidates.isEmpty() || (!plain && !snapshot.ad
                && candidates.stream().noneMatch(c -> SplashPolicy.countdownLabel(c.label) || SplashPolicy.explicitAdSkip(c.label)));
            if (needsTree && !window.hasAccepted() && budget.allowTree(started)) collect(root, candidates, snapshot);
            maxScanMs = Math.max(maxScanMs, SystemClock.elapsedRealtime() - started);
            now = SystemClock.elapsedRealtime();
            if (!window.observe(pkg, permitted(pkg), enabled(), now)) return;
            maxNodes = Math.max(maxNodes, snapshot.visited); limited |= snapshot.limited;
            sawSkip |= !candidates.isEmpty(); sawAd |= snapshot.ad;
            boolean lacksEvidence = false, wrongBounds = false, originalStillReadable = false, countdownPending = false;
            boolean rejected = false, sentNow = false;
            long actionDeadline = SystemClock.elapsedRealtime() + 120;
            for (Candidate candidate : candidates) {
                if (!SplashPolicy.readyToSkip(candidate.label)) { countdownPending = true; continue; }
                Rect r = new Rect(); candidate.node.getBoundsInScreen(r);
                originalStillReadable |= window.hasAccepted() && window.matchesAcceptedTarget(r.left,r.top,r.right,r.bottom);
                if (!SplashPolicy.candidate(candidate.label, snapshot.ad, plain, r.left,r.top,r.right,r.bottom,
                        bounds.left,bounds.top,bounds.right,bounds.bottom)) {
                    if (!plain && !snapshot.ad && !SplashPolicy.countdownLabel(candidate.label)
                            && !SplashPolicy.explicitAdSkip(candidate.label)) lacksEvidence = true;
                    else wrongBounds = true;
                    continue;
                }
                if (window.hasAttempted()) {
                    // WebView-hosted ad SDKs often publish a clickable skip control that silently ignores
                    // ACTION_CLICK. Once a sent click provably left the same control in place, one guarded
                    // coordinate touch at that same leaf is allowed, and only once per launch.
                    if (originalStillReadable && inertTapSession != sessionId) {
                        inertTapSession = sessionId;
                        Rect fallback = new Rect();
                        if (tapInert(new AndroidNode(AccessibilityNodeInfo.obtain(candidate.node)), pkg,
                                root.getWindowId(), toInfo(bounds),
                                snapshot.ad, plain, fallback)) {
                            window.accepted(fallback.left, fallback.top, fallback.right, fallback.bottom);
                            report("公开点击未生效，已改用坐标触摸同一个小按钮；不补点", 3);
                            sentNow = true;
                            break;
                        }
                    }
                    continue;
                }
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
                    report(originalStillReadable ? "一次操作后原跳过仍可读取；已停止，不补点"
                        : "一次操作后原跳过未再读到；不等同于已清除广告",3);
                    window.finish();
                }
            } else if (!window.hasAttempted() && !rejected) {
                if (candidates.isEmpty() && Prefs.screenshotOcrConsent(this)) {
                    if (startOcr(pkg, root.getWindowId(), bounds, snapshot.ad))
                        report("控件树未读到跳过，正在本机截图 OCR 检查",1);
                    else if (ocrInFlight) report("本机 OCR 正在识别当前画面",1);
                    else if (ocrFrames >= SplashOcr.MAX_FRAMES) report("本次本机 OCR 帧数已达上限，未再截图",1);
                    else report(ocrStatus,2);
                } else if (countdownPending) report("跳过按钮仍在倒计时，等倒计时消失后再点击",1);
                else if (lacksEvidence) report("读到跳过，但缺少可读取的倒计时或广告标识；可为此应用开启兼容识别",2);
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
    private void search(AccessibilityNodeInfo root, List<Candidate> out, Snapshot state, boolean plain) {
        // Ask for relevant text first instead of performing hundreds of child IPC calls on every event.
        // Plain-skip opt-in does not require a separate ad marker; still check both skip languages.
        String[] terms = plain ? new String[]{"跳过", "skip"} : new String[]{"跳过", "skip", "广告", "ad"};
        for (String term : terms) {
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
    private boolean startOcr(String pkg, int windowId, Rect screen, boolean treeAd) {
        if (ocrInFlight) return false;
        if (!Prefs.screenshotOcrConsent(this)) { ocrStatus = "本机截图 OCR 未授权"; return false; }
        if (Build.VERSION.SDK_INT < 30) { ocrStatus = "系统版本不支持截图 OCR"; return false; }
        if (!Prefs.textTapEnabled(this, pkg)) { ocrStatus = "此应用坐标触摸已关闭，未执行 OCR"; return false; }
        if (SplashTextTap.disabled(Prefs.get(this).getInt("text_tap_strikes_" + pkg, 0))) {
            ocrStatus = "此应用坐标触摸已自动停用，未执行 OCR"; return false;
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null || (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) == 0) {
            ocrStatus = "系统截图能力未授予，请重新开启净屏无障碍服务"; return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (!window.canAttempt(now) || now > waitingUntil || ocrFrames >= SplashOcr.MAX_FRAMES) return false;
        // Once OCR has read a skip word this splash, keep capturing at the fast cadence: the two-frame
        // confirmation needs a second frame inside SplashOcrPolicy.MAX_GAP_MS, and the slow probe cadence
        // can never produce one.
        boolean adContext = treeAd || ocrAdSeen || ocrSawSkip;
        if (ocrFrames > 0 && now - lastOcrAt < SplashOcr.MIN_CAPTURE_GAP_MS) return false;
        // The first two captures always use the fast cadence: a splash ad often lives ~5s, and the
        // two-frame confirmation needs both frames inside SplashOcrPolicy.MAX_GAP_MS.
        if (ocrFrames >= 2 && !adContext && now - lastOcrProbeAt < SplashOcr.PROBE_GAP_MS) return false;
        ocrInFlight = true; ocrFrames++; lastOcrAt = now;
        if (!adContext) { ocrProbes++; lastOcrProbeAt = now; }
        ocrStatus = "正在读取当前窗口画面";
        long id = sessionId; Rect bounds = new Rect(screen);
        ocr.capture(windowId, bounds, (result, failure) -> handleOcr(pkg, id, windowId, bounds, treeAd, result, failure));
        return true;
    }

    private void handleOcr(String pkg, long id, int windowId, Rect screen, boolean treeAd,
                           SplashOcr.Result result, String failure) {
        ocrInFlight = false;
        if (destroyed || id != sessionId || !pkg.equals(sessionPkg)) return;
        long now = SystemClock.elapsedRealtime();
        long gap = lastOcrDone == 0 ? -1 : now - lastOcrDone;
        lastOcrDone = now;
        if (!enabled() || !permitted(pkg) || !unlocked() || !window.observe(pkg, true, true, now)
                || !foregroundMatches(pkg, windowId) || !sameScreen(pkg, windowId, toInfo(screen))) {
            ocrPolicy.clear(); ocrStatus = "截图后活动窗口变化，已放弃"; report(ocrStatus,2); return;
        }
        if (failure != null || result == null) {
            ocrPolicy.clear();
            ocrStatus = "unsupported".equals(failure) ? "系统版本不支持截图 OCR"
                : "recognition_failed".equals(failure) ? "本机 OCR 识别失败，未点击" : "系统截图失败或窗口受保护，未点击";
            report(ocrStatus,2); return;
        }
        boolean ad = treeAd || result.adMarker || ocrAdSeen;
        if (result.adMarker) { ocrAdSeen = true; sawAd = true; }
        boolean plain = Prefs.plainSkip(this, pkg), recognized = false;
        float density = getResources().getDisplayMetrics().density;
        for (SplashOcr.Match match : result.skips) {
            Rect r = new Rect(match.bounds);
            // Some ad SDKs draw "跳过" and the countdown digit as two separate elements. Fold a digit that
            // sits flush against the word into one target so the small word alone is not rejected as too thin.
            boolean joined = false;
            int wordWidth = match.wordWidth;
            for (Rect digit : result.countdownDigits) {
                if (digit.width() >= r.width()) continue;
                if (!SplashOcrPolicy.beside(r.left, r.top, r.right, r.bottom,
                        digit.left, digit.top, digit.right, digit.bottom, density)) continue;
                joined = true;
                // The merged box is wider than the word: measure against the original word width.
                if (wordWidth <= 0) wordWidth = r.width();
                r.union(digit);
                break;
            }
            if (!SplashOcrPolicy.valid(match.label, ad, plain, r.left,r.top,r.right,r.bottom, wordWidth,
                    screen.left,screen.top,screen.right,screen.bottom, joined, joined, density)) {
                // Diagnostics record geometry only, never recognized text.
                getSharedPreferences("splash_diagnostic",MODE_PRIVATE).edit()
                    .putString("ocr_reject", "w=" + (r.width()/density) + "dp h=" + (r.height()/density)
                        + "dp word=" + (wordWidth/density) + "dp joined=" + joined
                        + " digit=" + result.countdownDigits.size()
                        + " skips=" + result.skips.size()).apply();
                continue;
            }
            sawSkip = true; recognized = true; ocrSawSkip = true;
            if (!ocrPolicy.observe(match.label, ad, plain, r.left,r.top,r.right,r.bottom, wordWidth,
                    screen.left,screen.top,screen.right,screen.bottom, joined, joined, pkg,windowId,density,now, r.left)) {
                ocrPass++;
                getSharedPreferences("splash_diagnostic",MODE_PRIVATE).edit()
                    .putString("ocr_wait", "gap=" + gap + "ms pass=" + ocrPass
                        + " w=" + (r.width()/density) + "dp h=" + (r.height()/density) + "dp").apply();
                ocrStatus = "OCR 已读到跳过，等待第二帧确认同一位置"; continue;
            }
            if (window.hasAttempted() || !Prefs.textTapEnabled(this,pkg)
                    || SplashTextTap.disabled(Prefs.get(this).getInt("text_tap_strikes_" + pkg,0))) {
                ocrPolicy.clear(); ocrStatus = "点击额度已用或此应用坐标触摸已停用"; report(ocrStatus,2); return;
            }
            if (!foregroundMatches(pkg,windowId) || !sameScreen(pkg,windowId,toInfo(screen))
                    || !enabled() || !permitted(pkg) || !unlocked()
                    || !ocrPolicy.matches(pkg,windowId,r.left,r.top,r.right,r.bottom,
                        screen.left,screen.top,screen.right,screen.bottom)) {
                ocrPolicy.clear(); ocrStatus = "点击前窗口或文字位置变化，未触摸"; report(ocrStatus,2); return;
            }
            try {
                if (getMagnificationController().getScale() != 1f) {
                    ocrPolicy.clear(); ocrStatus = "系统屏幕放大已开启，OCR 触摸已放弃"; report(ocrStatus,2); return;
                }
            } catch (RuntimeException e) { ocrPolicy.clear(); ocrStatus = "无法核实屏幕缩放，OCR 触摸已放弃"; report(ocrStatus,2); return; }
            if (!window.attempted(now)) { ocrPolicy.clear(); return; }
            ocrPolicy.clear(); ocrStatus = "已提交一次 OCR 触摸请求";
            getSharedPreferences("splash_diagnostic",MODE_PRIVATE).edit()
                .putString("ocr_point", ((r.left+r.right)/2) + "," + ((r.top+r.bottom)/2)
                    + " box=" + r.left + "," + r.top + "," + r.right + "," + r.bottom)
                .putString("target","两帧本机 OCR 确认的跳过文字中心").apply();
            boolean queued = dispatchTextTouch((r.left+r.right)/2f,(r.top+r.bottom)/2f,pkg,id,true,
                "OCR 触摸已执行；等待界面变化核验","系统取消 OCR 触摸；已停止","系统未接受 OCR 触摸请求；已停止");
            if (!queued) return;
            window.accepted(r.left,r.top,r.right,r.bottom);
            long clicks = Prefs.get(this).getLong("skip_clicks",0);
            Prefs.get(this).edit().putLong("skip_clicks",clicks+1).apply();
            report("已向两帧确认的 OCR 跳过文字发送一次触摸；不补点",3);
            return;
        }
        ocrStatus = recognized ? "跳过位置不满足安全条件，未触摸"
            : ad ? "OCR 未读到符合条件的跳过文字" : "OCR 未确认广告标识，等待下一次探测";
        if (!recognized) ocrPolicy.clear();
        report(ocrStatus,recognized ? 2 : 1);
    }

    private static SplashButton.Info toInfo(Rect rect) {
        SplashButton.Info info = new SplashButton.Info();
        info.left=rect.left; info.top=rect.top; info.right=rect.right; info.bottom=rect.bottom;
        return info;
    }

    private boolean click(Candidate candidate, String pkg, int windowId, Rect screen,
                          boolean ad, boolean plain, Rect acted) {
        if (!foregroundMatches(pkg,windowId)) {
            report("当前活动窗口变化，未发送点击",2); return false;
        }
        SplashButton.Info screenBox = new SplashButton.Info();
        screenBox.left=screen.left; screenBox.top=screen.top; screenBox.right=screen.right; screenBox.bottom=screen.bottom;
        SplashButton.Info bounds = new SplashButton.Info();
        bounds.left=screen.left; bounds.top=screen.top; bounds.right=screen.right; bounds.bottom=screen.bottom;
        try (AndroidNode source = new AndroidNode(AccessibilityNodeInfo.obtain(candidate.node));
             SplashButton.Result target = SplashButton.resolve(source,pkg,windowId,bounds,ad,plain,
                 getResources().getDisplayMetrics().density)) {
            if (target.node == null) {
                // The SDK refused to expose ACTION_CLICK. Before 0.4.20 this fell back to a guarded
                // coordinate tap; 0.4.20 deleted it and every self-drawn skip button stopped working.
                if (target.textTapEligible && validTextTarget(source,screenBox,pkg,windowId,ad,plain))
                    return tapText(source,pkg,windowId,screenBox,ad,plain,acted);
                resetTextTap();
                report(target.reason,2);
                return false;
            }
            resetTextTap();
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
    /** Whether the labelled leaf itself may be tapped after the button path refused it. */
    private boolean validTextTarget(AndroidNode source, SplashButton.Info screen, String pkg, int windowId,
                                    boolean ad, boolean plain) {
        if (destroyed || !enabled() || !permitted(pkg) || !unlocked()) return false;
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null || (info.getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) {
            report("系统尚未授予触摸能力，请在系统无障碍中关闭净屏后重新开启",2); return false;
        }
        if (getMagnificationController().getScale() != 1f) {
            report("系统屏幕放大已开启，文字定位触摸已放弃",2); return false;
        }
        if (SplashTextTap.disabled(Prefs.get(this).getInt("text_tap_strikes_" + pkg, 0))) {
            report("此应用此前两次坐标触摸后界面都没有变化，已退回只认公开点击动作",2); return false;
        }
        if (!Prefs.textTapEnabled(this, pkg)) {
            report("此应用已关闭坐标触摸，只认公开点击动作",2); return false;
        }
        SplashButton.Info leaf = source.read();
        float density = getResources().getDisplayMetrics().density;
        if (source.children() != 0 || !SplashTextTap.valid(leaf,screen,pkg,windowId,ad,plain,density)) return false;
        return SplashTextTap.evidence(leaf.text,leaf.description,plain);
    }
    /**
     * Taps the centre of a leaf that only labels the skip control. Requires two scans of the same node at the
     * same place, re-reads the foreground and window bounds immediately before input, and re-reads the label
     * so a screen that swapped after the first scan cannot be tapped blind.
     */
    private boolean tapText(AndroidNode source, String pkg, int windowId, SplashButton.Info screen,
                            boolean ad, boolean plain, Rect acted) {
        float density = getResources().getDisplayMetrics().density;
        SplashButton.Info info = source.read();
        if (source.children() != 0 || !SplashTextTap.valid(info,screen,pkg,windowId,ad,plain,density)) {
            resetTextTap(); report("文字定位要求明确、可见的小尺寸跳过文字，未发送触摸",2); return false;
        }
        if (tapAnchor == null || !tapAnchor.same(source)) {
            resetTextTap();
            if (tapAnchor != null) tapAnchor.close();
            tapAnchor = (AndroidNode) source.copy(); tapIdentity++;
        }
        long identity = tapIdentity;
        boolean stable = textTap.observe(info,screen,pkg,windowId,identity,enabled(),ad,plain,density,
            SystemClock.elapsedRealtime());
        if (!stable) { report("已读到跳过文字，等待下一轮确认同一位置稳定",1); return false; }
        if (!foregroundMatches(pkg,windowId) || !sameScreen(pkg,windowId,screen) || !unlocked() || !permitted(pkg)
                || !Prefs.textTapEnabled(this,pkg)) {
            resetTextTap(); report("触摸前活动窗口变化，已放弃",2); return false;
        }
        SplashButton.Info fresh = source.read();
        if (source.children() != 0 || !SplashTextTap.valid(fresh,screen,pkg,windowId,ad,plain,density)
                || !SplashTextTap.evidence(fresh.text,fresh.description,plain)
                || !textTap.matches(fresh,screen,pkg,windowId,identity)) {
            resetTextTap(); report("触摸前跳过文字或位置变化，已放弃",2); return false;
        }
        if (!window.attempted(SystemClock.elapsedRealtime())) return false;
        float x = fresh.left + (fresh.right - fresh.left) / 2f;
        float y = fresh.top + (fresh.bottom - fresh.top) / 2f;
        long id = sessionId;
        acted.set(fresh.left,fresh.top,fresh.right,fresh.bottom);
        getSharedPreferences("splash_diagnostic",MODE_PRIVATE).edit()
            .putString("target","连续两次核对位置稳定的跳过文字中心（坐标触摸）").apply();
        return dispatchTextTouch(x,y,pkg,id,false,"坐标触摸已执行；1.5 秒后核对界面是否变化，不补点",
            "坐标触摸被系统取消；已停止，不补点","系统未接受这一次坐标触摸请求；已停止，不补点");
    }
    private boolean dispatchTextTouch(float x, float y, String pkg, long id, boolean ocrAction,
                                      String completedMessage, String cancelledMessage, String rejectedMessage) {
        lastTouchX = x; lastTouchY = y;
        Path point = new Path(); point.moveTo(x,y); point.lineTo(x+1f,y+1f);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(point,0,80)).build();
        holdVerification(pkg,id); resetTextTap();
        boolean queued = dispatchGesture(gesture,new GestureResultCallback() {
            private boolean current() { return !destroyed && id == sessionId && pkg.equals(sessionPkg); }
            @Override public void onCompleted(GestureDescription ignored) {
                if (current()) { if (ocrAction) ocrStatus = completedMessage; report(completedMessage,3); }
            }
            @Override public void onCancelled(GestureDescription ignored) {
                if (current()) { if (ocrAction) ocrStatus = cancelledMessage; report(cancelledMessage,3); window.finish(); }
            }
        },handler);
        if (!queued) {
            if (ocrAction) ocrStatus = rejectedMessage;
            report(rejectedMessage,3); window.finish();
        }
        return queued;
    }
    /**
     * Taps the centre of a skip leaf whose published click action already proved inert. The two-scan
     * stability rule is satisfied without waiting again: the accepted rectangle was recorded when the
     * click was sent, and this pass re-read the same leaf on a later scan at the very same place.
     */
    private boolean tapInert(AndroidNode source, String pkg, int windowId, SplashButton.Info screen,
                             boolean ad, boolean plain, Rect acted) {
        if (destroyed || !enabled() || !permitted(pkg) || !unlocked()) return false;
        if (!window.hasAccepted()) return false;
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null || (info.getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) == 0) {
            report("公开点击未生效，但系统未授予触摸能力，无法改用坐标触摸", 2); return false;
        }
        if (SplashTextTap.disabled(Prefs.get(this).getInt("text_tap_strikes_" + pkg, 0))) {
            report("此应用此前坐标触摸后界面都没有变化，已退回只认公开点击动作", 2); return false;
        }
        if (!Prefs.textTapEnabled(this, pkg)) {
            report("此应用已关闭坐标触摸，只认公开点击动作", 2); return false;
        }
        try {
            if (getMagnificationController().getScale() != 1f) {
                report("公开点击未生效；系统屏幕放大已开启，坐标触摸已放弃", 2); return false;
            }
        } catch (RuntimeException e) {
            report("公开点击未生效；无法核实屏幕缩放，坐标触摸已放弃", 2); return false;
        }
        SplashButton.Info leaf = source.read();
        float density = getResources().getDisplayMetrics().density;
        if (source.children() != 0 || !SplashTextTap.valid(leaf, screen, pkg, windowId, ad, plain, density)
                || !SplashTextTap.evidence(leaf.text, leaf.description, plain)) {
            report("公开点击未生效，但这个控件不是明确的跳过文字，未改用坐标触摸", 2); return false;
        }
        if (!window.matchesAcceptedTarget(leaf.left, leaf.top, leaf.right, leaf.bottom)) {
            report("公开点击未生效，但控件位置已经改变，未改用坐标触摸", 2); return false;
        }
        if (!foregroundMatches(pkg, windowId) || !sameScreen(pkg, windowId, screen)
                || !enabled() || !permitted(pkg) || !unlocked() || !Prefs.textTapEnabled(this, pkg)) {
            report("触摸前应用或窗口变化，未改用坐标触摸", 2); return false;
        }
        float x = leaf.left + (leaf.right - leaf.left) / 2f;
        float y = leaf.top + (leaf.bottom - leaf.top) / 2f;
        acted.set(leaf.left, leaf.top, leaf.right, leaf.bottom);
        getSharedPreferences("splash_diagnostic", MODE_PRIVATE).edit()
            .putString("target", "公开点击未生效后改用坐标触摸的跳过文字中心").apply();
        return dispatchTextTouch(x, y, pkg, sessionId, false,
            "坐标触摸已执行；1.5 秒后核对界面是否变化，不补点",
            "坐标触摸被系统取消；已停止，不补点", "系统未接受这一次坐标触摸请求；已停止，不补点");
    }
    /** Remembers what the foreground looked like so the tap can be judged afterwards. */
    private void holdVerification(String pkg, long id) {
        handler.removeCallbacks(verify);
        pendingPkg = pkg; pendingWindow = 0;
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            try { pendingWindow = active.getWindowId(); } finally { active.recycle(); }
        }
        handler.postDelayed(verify, SplashTextTap.VERIFY_MS);
    }
    /** A tap that left the foreground unchanged did not skip anything; enough misses retire the mode. */
    private void verifyTextTap() {
        String pkg = pendingPkg;
        int window = pendingWindow;
        pendingPkg = ""; pendingWindow = 0;
        if (pkg.isEmpty() || destroyed) return;
        boolean effective = false;
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) {
            try {
                String current = active.getPackageName() == null ? "" : active.getPackageName().toString();
                effective = !pkg.equals(current) || active.getWindowId() != window;
            } finally { active.recycle(); }
        }
        if (!effective && !ocrRetried && pkg.equals(sessionPkg)) {
            // The H5 splash layer silently drops injected gestures now and then, so one same-spot retry
            // is allowed before the miss is counted against this app.
            ocrRetried = true;
            dispatchTextTouch(lastTouchX, lastTouchY, pkg, sessionId, false,
                "第一次坐标触摸未生效，已在同一位置补发一次", "补发的坐标触摸被系统取消；已停止，不补点",
                "系统未接受补发的坐标触摸；已停止，不补点");
            report("坐标触摸后界面未变化，已在同一位置补发一次", 3);
            return;
        }
        SharedPreferences prefs = Prefs.get(this);
        String key = "text_tap_strikes_" + pkg;
        int strikes = SplashTextTap.penalties(prefs.getInt(key,0), effective);
        prefs.edit().putInt(key,strikes).apply();
        if (SplashTextTap.disabled(strikes))
            report("连续两次坐标触摸后界面没有变化，此应用已退回只认公开点击动作",3);
        else if (effective) report("坐标触摸后界面已变化",3);
        else report("坐标触摸后界面未变化，下次失效将退回只认公开点击动作",2);
    }
    private boolean sameScreen(String pkg, int windowId, SplashButton.Info screen) {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active == null) return false;
        try {
            Rect r = new Rect(); active.getBoundsInScreen(r);
            return active.getWindowId() == windowId && active.getPackageName() != null
                && pkg.contentEquals(active.getPackageName())
                && r.left == screen.left && r.top == screen.top && r.right == screen.right && r.bottom == screen.bottom;
        } finally { active.recycle(); }
    }
    private void resetTextTap() {
        textTap.reset();
        if (tapAnchor != null) { tapAnchor.close(); tapAnchor = null; }
    }
    private static final class AndroidNode implements SplashButton.Node {        final AccessibilityNodeInfo node;
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
        ocrPolicy.clear(); ocrAdSeen = false; ocrFrames = 0; ocrProbes = 0; lastOcrAt = 0; lastOcrProbeAt = 0; ocrPass = 0; lastOcrDone = 0; ocrSawSkip = false;
        if (tapAnchor != null) tapAnchor.close();
        handler.post(this::resetTextTap);
        handler.removeCallbacks(verify); pendingPkg = ""; pendingWindow = 0;
        sessionId++; sessionPkg = ""; eventPkg = ""; waitingUntil = 0;
    }
    @Override public void onInterrupt() { handler.post(this::cancel); }
    @Override public boolean onUnbind(Intent intent) { connected = false; handler.post(this::cancel); return super.onUnbind(intent); }
    @Override public void onDestroy() {
        connected = false; destroyed = true; handler.removeCallbacksAndMessages(null);
        if (tapAnchor != null) tapAnchor.close();
        if (ocr != null) ocr.close();
        scanThread.quitSafely(); super.onDestroy();
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
