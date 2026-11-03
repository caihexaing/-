package cn.quietguard.app;

/** Persistable state machine for a temporary pause of this app's own VPN only. */
final class UpdateVpnState {
    boolean paused, restore, checking, installing;
    long checkToken, downloadId = -1, installCode;
    void beginCheck(long token, boolean running) {
        paused = true; restore |= running; checking = true; checkToken = token;
    }
    void endCheck(long token) {
        if (checking && token == checkToken) { checking = false; checkToken = 0; releaseIfIdle(); }
    }
    void beginDownload(boolean running) { paused = true; restore |= running; downloadId = -2; }
    void attachDownload(long id) { if (downloadId == -2) downloadId = id; }
    void endDownload(long id) { if (downloadId == id) { downloadId = -1; releaseIfIdle(); } }
    void installStarted(boolean running, long code) { paused = true; restore |= running; installing = true; installCode = code; }
    void installFinished() { installing = false; installCode = 0; releaseIfIdle(); }
    void userDisabled() { restore = false; installing = false; installCode = 0; releaseIfIdle(); }
    void restored() { restore = false; }
    boolean canRestore(boolean enabled, boolean selected, boolean authorized) {
        return !paused && restore && enabled && selected && authorized;
    }
    private void releaseIfIdle() { if (!checking && downloadId == -1 && !installing) paused = false; }
}
