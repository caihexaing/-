package cn.quietguard.app;

/** Retains an actionable decision when later polls only see a loading or closed window. */
public final class SplashDiagnostic {
    private String reason = "", status = "";
    private int priority = -1;
    public void note(String message, int importance) {
        status = message;
        if (importance >= priority) { reason = message; priority = importance; }
    }
    public String reason() { return reason; }
    public String status() { return status; }
    public void reset() { reason = ""; status = ""; priority = -1; }
}
