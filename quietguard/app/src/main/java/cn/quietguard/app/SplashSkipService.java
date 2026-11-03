package cn.quietguard.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Local, selected-app-only skip actions, limited to a short foreground entry interval. */
public final class SplashSkipService extends AccessibilityService {
    public static volatile boolean connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SplashWindow window = new SplashWindow();
    private boolean scanning;
    private final Runnable poll = this::inspect;

    @Override protected void onServiceConnected() { connected = true; window.reset(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!enabled()) { cancel(); return; }
        // Never inspect event text. Only read a selected app's visible tree after the package check.
        handler.removeCallbacks(poll); handler.post(poll);
    }
    private boolean enabled() {
        return Prefs.enabled(this) && Prefs.get(this).getBoolean("skip_consent", false) && !Prefs.selected(this).isEmpty();
    }
    private boolean permitted(String pkg) {
        return enabled() && pkg != null && !pkg.equals(getPackageName()) && !pkg.equals("android")
            && !pkg.equals("com.android.systemui") && Prefs.selected(this).contains(pkg);
    }
    private boolean unlocked() {
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        return lock == null || !lock.isKeyguardLocked();
    }
    private void inspect() {
        if (scanning) return;
        if (!enabled() || !unlocked()) { cancel(); return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        scanning = true;
        List<Candidate> candidates = new ArrayList<>();
        try {
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            long now = SystemClock.elapsedRealtime();
            if (!window.observe(pkg, permitted(pkg), true, now)) return;
            Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
            Snapshot snapshot = new Snapshot(); collect(root, candidates, snapshot, 0);
            if (window.canAttempt(now)) for (Candidate candidate : candidates) {
                Rect r = new Rect(); candidate.node.getBoundsInScreen(r);
                if (!SplashPolicy.candidate(candidate.label, snapshot.ad, r.left, r.top, r.right, r.bottom,
                        bounds.left, bounds.top, bounds.right, bounds.bottom)) continue;
                // Re-check foreground, selection and master switch immediately before taking an action.
                if (!foregroundMatches(pkg)) return;
                window.attempted(now);
                if (click(candidate, bounds, snapshot.ad)) {
                    long n = Prefs.get(this).getLong("skip_clicks", 0);
                    Prefs.get(this).edit().putLong("skip_clicks", n + 1).apply();
                    window.finish();
                }
                break;
            }
            if (window.observe(pkg, permitted(pkg), enabled(), SystemClock.elapsedRealtime())) handler.postDelayed(poll, 220);
        } catch (IllegalStateException | SecurityException ignored) {
            // A closing/replaced window is not a reason to click stale coordinates.
        } finally {
            for (Candidate candidate : candidates) candidate.node.recycle();
            root.recycle(); scanning = false;
        }
    }
    private boolean foregroundMatches(String pkg) {
        if (!permitted(pkg) || !unlocked()) return false;
        AccessibilityNodeInfo current = getRootInActiveWindow();
        if (current == null) return false;
        try { return current.getPackageName() != null && pkg.contentEquals(current.getPackageName()); }
        finally { current.recycle(); }
    }
    private void collect(AccessibilityNodeInfo node, List<Candidate> out, Snapshot state, int depth) {
        if (depth > 18 || state.visited++ >= 240 || !node.isVisibleToUser()) return;
        CharSequence text = node.getText(), description = node.getContentDescription();
        for (CharSequence value : new CharSequence[]{text, description}) {
            if (value == null || value.length() > 64) continue;
            String label = value.toString();
            state.ad |= SplashPolicy.adMarker(label);
            if (out.size() < 12 && node.isEnabled() && SplashPolicy.skipLabel(label)) {
                out.add(new Candidate(AccessibilityNodeInfo.obtain(node), label)); break;
            }
        }
        for (int i = 0; i < node.getChildCount() && state.visited < 240; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { try { collect(child, out, state, depth + 1); } finally { child.recycle(); } }
        }
    }
    private boolean click(Candidate candidate, Rect windowBounds, boolean ad) {
        AccessibilityNodeInfo node = candidate.node;
        if (!node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) return false;
        // AccessibilityNodeInfo objects can change while an ad is being replaced.
        boolean textStillMatches = candidate.label.contentEquals(node.getText() == null ? "" : node.getText())
            || candidate.label.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription());
        if (!textStillMatches) return false;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (!SplashPolicy.candidate(candidate.label, ad, bounds.left, bounds.top, bounds.right, bounds.bottom,
                windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            try {
                Rect p = new Rect(); parent.getBoundsInScreen(p);
                if (parent.isVisibleToUser() && parent.isEnabled() && parent.isClickable() && p.contains(bounds)
                    && p.width() <= bounds.width() * 2 + 24 && p.height() <= bounds.height() * 2 + 24
                    && SplashPolicy.candidate(candidate.label, ad, p.left, p.top, p.right, p.bottom,
                        windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)
                    && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            } finally { parent.recycle(); }
        }
        // Gesture fallback is anchored to the identified skip node, never a hard-coded screenshot coordinate.
        Path path = new Path(); path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        return dispatchGesture(new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 40)).build(), null, null);
    }
    private void cancel() { handler.removeCallbacks(poll); window.reset(); }
    @Override public void onInterrupt() { cancel(); }
    @Override public boolean onUnbind(Intent intent) { connected = false; cancel(); return super.onUnbind(intent); }
    @Override public void onDestroy() { connected = false; cancel(); super.onDestroy(); }
    private static final class Snapshot { boolean ad; int visited; }
    private static final class Candidate {
        final AccessibilityNodeInfo node; final String label;
        Candidate(AccessibilityNodeInfo node, String label) { this.node = node; this.label = label; }
    }
}
