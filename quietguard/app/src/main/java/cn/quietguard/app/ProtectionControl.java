package cn.quietguard.app;

/** Durable user intent, independent from asynchronous Android service teardown. */
final class ProtectionControl {
    boolean enabled;
    long revision;
    ProtectionControl(boolean enabled, long revision) { this.enabled = enabled; this.revision = revision; }
    void request(boolean on) {
        if (enabled != on || !on) revision++;
        enabled = on;
    }
    boolean acceptsPermission(long token, boolean hasSelection) {
        return enabled && hasSelection && token >= 0 && token == revision;
    }
}
