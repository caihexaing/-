package cn.quietguard.app;

import java.util.ArrayList;
import java.util.List;

/** Resolves one semantic control. It never sends input or falls back to screen coordinates. */
public final class SplashButton {
    private SplashButton() { }
    public interface Node extends AutoCloseable {
        Node copy();
        Info read(); // A refreshed snapshot; null means stale/unavailable.
        Node parent();
        int children();
        Node child(int index);
        boolean same(Node other);
        @Override void close();
    }
    public static final class Info {
        public String text, description, pkg;
        public int window, left, top, right, bottom;
        public boolean visible, enabled, clickable, action, editable, scrollable;
    }
    public static final class Result implements AutoCloseable {
        public final Node node;
        public final boolean container;
        public final String reason;
        private Result(Node node, boolean container, String reason) {
            this.node = node; this.container = container; this.reason = reason;
        }
        @Override public void close() { if (node != null) node.close(); }
    }
    private static Result reject(String reason) { return new Result(null, false, reason); }
    private static boolean basic(Info i, String pkg, int window) {
        return i != null && pkg.equals(i.pkg) && i.window == window && i.visible && i.enabled
            && !i.editable && !i.scrollable;
    }
    private static boolean inside(Info inner, Info outer) {
        return inner.right > inner.left && inner.bottom > inner.top
            && inner.left >= outer.left && inner.top >= outer.top
            && inner.right <= outer.right && inner.bottom <= outer.bottom;
    }
    private static boolean compact(Info control, Info label, float density) {
        long w = (long)label.right - label.left, h = (long)label.bottom - label.top;
        return density > 0 && Float.isFinite(density) && inside(label, control)
            && (long)control.right - control.left <= w * 3
            && (long)control.bottom - control.top <= h * 3
            && label.left - (long)control.left <= 32 * density
            && control.right - (long)label.right <= 32 * density
            && label.top - (long)control.top <= 16 * density
            && control.bottom - (long)label.bottom <= 16 * density;
    }
    public static Result resolve(Node source, String pkg, int window, Info screen,
                                 boolean ad, boolean plain, float density) {
        Info label = source.read();
        if (!basic(label, pkg, window)) return reject("跳过文字已失效、不可见或不属于当前窗口");
        String text = SplashPolicy.actionLabel(label.text, label.description);
        if (text == null) return reject("跳过文字与控件描述冲突，未发送点击");
        if (!SplashPolicy.candidate(text, ad, plain, label.left,label.top,label.right,label.bottom,
                screen.left,screen.top,screen.right,screen.bottom))
            return reject("跳过文字缺少广告依据或大小边界不符合条件");
        Node cursor = source.copy();
        try {
            for (int level = 0; level <= 2; level++) {
                Info info = cursor.read();
                if (!basic(info, pkg, window)) return reject("跳过按钮失效或所属窗口变化");
                if (!SplashPolicy.supportingLabel(info.text) || !SplashPolicy.supportingLabel(info.description))
                    return reject("跳过按钮外层含其他语义，已拒绝点击");
                if (level > 0 && (!compact(info,label,density)
                        || !SplashPolicy.candidate(text,ad,plain,info.left,info.top,info.right,info.bottom,
                            screen.left,screen.top,screen.right,screen.bottom)))
                    return reject("跳过文字外层过大或越界，已拒绝点击");
                if (info.action) {
                    if (cursor.children() > 0 && !onlySkipContents(cursor,source,pkg,window,info))
                        return reject("跳过按钮内容不唯一、含其他入口或读取不完整，已拒绝点击");
                    if (level > 0 && cursor.children() == 0)
                        return reject("无法确认跳过文字属于该按钮，已拒绝点击");
                    return new Result(cursor.copy(), level > 0, level > 0
                        ? "已核对仅含跳过文字的小按钮" : "已核对文字控件自身点击动作");
                }
                // Stop at a clickable-looking control that doesn't advertise ACTION_CLICK.
                if (info.clickable) return reject("按钮未公开点击动作，未发送点击");
                if (level == 2) break;
                Node parent = cursor.parent();
                if (parent == null) break;
                boolean cycle = parent.same(cursor) || parent.same(source);
                cursor.close(); cursor = parent;
                if (cycle) return reject("控件结构异常，未发送点击");
            }
            return reject("跳过文字和两层内的小按钮均未提供点击动作");
        } finally { cursor.close(); }
    }
    private static boolean onlySkipContents(Node root, Node original, String pkg, int window, Info bounds) {
        List<Node> nodes = new ArrayList<>(); List<Integer> depths = new ArrayList<>();
        long deadline = System.nanoTime() + 80_000_000L;
        nodes.add(root.copy()); depths.add(0);
        boolean found = false, skip = false; int skipLeaves = 0;
        try {
            for (int i = 0; i < nodes.size(); i++) {
                if (System.nanoTime() > deadline) return false;
                Node node = nodes.get(i); Info info = node.read();
                if (!basic(info,pkg,window) || !inside(info,bounds)) return false;
                if (!SplashPolicy.supportingLabel(info.text) || !SplashPolicy.supportingLabel(info.description)) return false;
                if (i != 0 && (info.clickable || info.action)) return false;
                found |= node.same(original);
                boolean labelledSkip = SplashPolicy.actionLabel(info.text,info.description) != null;
                skip |= labelledSkip;
                int count = node.children();
                if (count < 0 || count > 12 || (count > 0 && depths.get(i) >= 3)) return false;
                if (count == 0) {
                    if ((info.text == null || info.text.trim().isEmpty())
                            && (info.description == null || info.description.trim().isEmpty())) return false;
                    if (labelledSkip && ++skipLeaves > 1) return false;
                }
                for (int j = 0; j < count; j++) {
                    if (nodes.size() >= 12 || System.nanoTime() > deadline) return false;
                    Node child = node.child(j);
                    if (child == null) return false;
                    boolean duplicate = false;
                    for (Node previous : nodes) duplicate |= previous.same(child);
                    if (duplicate) { child.close(); return false; }
                    nodes.add(child); depths.add(depths.get(i)+1);
                }
            }
            return found && skip;
        } finally { for (Node node : nodes) node.close(); }
    }
}
