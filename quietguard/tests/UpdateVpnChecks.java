package cn.quietguard.app;

public final class UpdateVpnChecks {
    private static int checks;
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static boolean restore(UpdateVpnState s) { return s.canRestore(true, true, true); }
    public static void main(String[] args) {
        UpdateVpnState s = new UpdateVpnState();
        check(!s.paused && !restore(s), "idle never enables VPN");
        s.beginCheck(10, true);
        check(s.paused && s.restore && !restore(s), "check records and pauses running VPN");
        s.endCheck(9); check(s.paused, "stale check completion ignored");
        s.endCheck(10); check(!s.paused && restore(s), "check finish restores original VPN");
        s.restored(); check(!restore(s), "restore completes exactly once");

        s = new UpdateVpnState(); s.beginCheck(1, false); s.endCheck(1);
        check(!restore(s), "check never enables originally off VPN");
        s.beginDownload(false); s.attachDownload(22); s.endDownload(22);
        check(!restore(s) && !s.paused, "download never enables originally off VPN");

        s = new UpdateVpnState(); s.beginDownload(true);
        check(s.paused && s.downloadId == -2 && !restore(s), "pause before enqueue");
        s.endDownload(-2); check(restore(s), "enqueue failure restores VPN");
        s.restored(); s.beginDownload(true); s.attachDownload(23);
        check(s.downloadId == 23 && s.paused, "track only actual owned download");
        s.endDownload(999); check(s.paused, "foreign download broadcast cannot restore early");
        s.endDownload(23); check(restore(s), "terminal download restores");
        s.restored(); s.endDownload(23); check(!restore(s), "duplicate completion does not restart");

        s = new UpdateVpnState(); s.beginDownload(true); s.attachDownload(30); s.userDisabled();
        check(!s.restore && s.paused, "manual stop suppresses resume but keeps transfer pause");
        s.endDownload(30); check(!restore(s) && !s.paused, "cancel/finish respects manual stop");

        s = new UpdateVpnState(); s.beginCheck(10, true); s.beginDownload(false); s.attachDownload(31);
        s.endCheck(10); check(s.paused && s.restore, "check completion cannot resume during download");
        s.endDownload(31); check(restore(s), "last outstanding operation releases pause");

        s = new UpdateVpnState(); s.beginDownload(true); s.attachDownload(32); s.beginCheck(11, false);
        s.endDownload(32); check(s.paused, "download cannot resume while check owns pause");
        s.endCheck(11); check(restore(s), "check last restores");

        s = new UpdateVpnState(); s.installStarted(true, 9);
        check(s.installing && s.installCode == 9 && s.paused && !restore(s), "persist and pause running VPN before installer");
        s.installFinished(); check(!s.installing && restore(s), "package replacement retains restore intent");
        check(!s.canRestore(false, true, true), "master off prevents restart");
        check(!s.canRestore(true, false, true), "empty app selection prevents restart");
        check(!s.canRestore(true, true, false), "revoked permission or another VPN prevents restart");
        s.restored(); check(!restore(s), "new package marks successful restoration");

        s = new UpdateVpnState(); s.installStarted(false, 9); s.installFinished();
        check(!restore(s), "install while VPN off stays off");
        s.installStarted(true, 9); s.userDisabled(); s.installFinished();
        check(!restore(s), "manual stop wins over install restore intent");

        s = new UpdateVpnState(); s.beginDownload(true); s.attachDownload(40);
        // Simulate persisted state loaded by the replacement process.
        UpdateVpnState loaded = new UpdateVpnState(); loaded.paused = s.paused; loaded.restore = s.restore;
        loaded.downloadId = s.downloadId; loaded.installStarted(false, 9); loaded.installFinished();
        check(loaded.paused && !restore(loaded), "process reload preserves active transfer lease");
        loaded.endDownload(40); check(restore(loaded), "restored process can release terminal transfer");

        s = new UpdateVpnState(); s.beginCheck(50, true); s.endCheck(50); s.beginCheck(51, false);
        s.endCheck(50); check(s.checking && s.checkToken == 51 && s.paused, "late old callback cannot release new check");
        s.endCheck(51); check(restore(s), "pending restore survives consecutive check");
        System.out.println("PASS: " + checks + " update VPN pause, recovery and manual override checks.");
    }
}
