package cn.quietguard.app;

import java.util.HashSet;
import java.util.Set;

public final class ControlChecks {
    static int checks;
    static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    public static void main(String[] args) {
        ProtectionControl intent = new ProtectionControl(false, 0);
        intent.request(true); long first = intent.revision;
        check(intent.acceptsPermission(first,true), "active request accepts system authorization");
        intent.request(false);
        check(!intent.acceptsPermission(first,true), "pause rejects an in-flight permission result");
        intent.request(true);
        check(!intent.acceptsPermission(first,true), "pause/resume cannot revive an older request");
        check(intent.acceptsPermission(intent.revision,true), "new request may connect");
        check(!intent.acceptsPermission(intent.revision,false), "bulk disable/empty selection prevents connection");
        check(!intent.acceptsPermission(-1,true), "unsolicited activity result is ignored");
        long current = intent.revision; intent.request(true);
        check(intent.revision == current, "active reconfiguration does not invalidate its permission request");
        ProtectionControl loaded = new ProtectionControl(intent.enabled,intent.revision);
        check(loaded.acceptsPermission(current,true), "persisted revision survives activity recreation");

        Set<String> selected = new HashSet<>(), network = new HashSet<>();
        for (int i=0;i<102;i++) { selected.add("app"+i); if (i<100) network.add("app"+i); }
        ProtectionStatus state = new ProtectionStatus(false,selected,network,network,true,false,true);
        check(state.action().equals("恢复防护"), "lingering service cannot keep the pause button active");
        check(state.title().equals("防护已暂停") && !state.skipReady, "user pause overrides stale service observations");
        check(state.networkActiveCount == 0, "no active scope is advertised after manual pause");
        check(state.selectedCount == 102 && state.networkCount == 100 && state.skipOnlyCount == 2, "102 selections split into 100 network + 2 skip-only");
        state = new ProtectionStatus(true,selected,network,network,true,false,true);
        check(state.networkActiveCount == 100 && state.title().equals("防护中"), "actual network scope is represented separately");
        Set<String> partial = new HashSet<>(network); partial.remove("app0"); partial.add("foreign");
        state = new ProtectionStatus(true,selected,network,partial,true,false,true);
        check(state.networkActiveCount == 99, "missing packages and unrelated sessions do not inflate active count");
        state = new ProtectionStatus(true,selected,network,Set.of(),false,true,true);
        check(state.skipReady && state.action().equals("暂停防护"), "resume during update enables skip immediately");
        check(state.title().contains("网络直连"), "update pause remains visible");
        state = new ProtectionStatus(true,selected,network,Set.of(),false,false,false);
        check(state.title().equals("防护待就绪"), "requested intent alone is not reported as running");
        state = new ProtectionStatus(true,Set.of(),Set.of(),network,true,false,true);
        check(!state.skipReady && state.networkActiveCount==0, "empty selection has no effective scope");

        Set<String> before = new HashSet<>(Set.of("existing-network","existing-skip"));
        Set<String> skip = new HashSet<>(Set.of("existing-skip"));
        Set<String> candidates = Set.of("existing-network","existing-skip","new","bank","explicit-network");
        SelectionPlan plan = new SelectionPlan(before,skip,Set.of("explicit-network"),Set.of("bank"),candidates,true);
        check(plan.added==2 && plan.selected.size()==4, "bulk selection skips persistent exemption");
        check(!plan.selected.contains("bank"), "one tap enable cannot undo bank exemption");
        check(plan.skipOnly.contains("new"), "conservative default never routes a new app through DNS");
        check(!plan.skipOnly.contains("existing-network"), "existing network choice is preserved");
        check(!plan.skipOnly.contains("explicit-network"), "explicit per-app mode wins over default");
        check(plan.skipOnly.contains("existing-skip"), "existing skip compatibility is preserved");
        check(before.size()==2 && skip.equals(Set.of("existing-skip")), "plan never mutates shared preference inputs");
        plan = new SelectionPlan(before,skip,Set.of(),Set.of(),Set.of("new"),false);
        check(!plan.skipOnly.contains("new"), "explicitly disabled conservative default permits DNS");
        plan = new SelectionPlan(before,skip,Set.of(),Set.of("existing-network"),Set.of(),true);
        check(!plan.selected.contains("existing-network"), "persisted exemption defeats stale stored selection");
        System.out.println("PASS: " + checks + " control, effective scope and conservative selection checks.");
    }
}
