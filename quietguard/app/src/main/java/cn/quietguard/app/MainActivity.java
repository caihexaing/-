package cn.quietguard.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.IOException;
import java.io.InputStream;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int INK = 0xff192c32, MUTED = 0xff687a80, GREEN = 0xff12856f, BG = 0xfff5f7f8;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final ExecutorService updates = Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps = new ArrayList<>(), visible = new ArrayList<>();
    private AppAdapter adapter;
    private TextView statusText, countText, statsText, empty, skipStatus, scopeText, networkStatus, updateFeedback;
    private Button updateButton;
    private AlertDialog skipDialog, updateCheckDialog;
    private long pendingVpnRevision = -1;
    private Button power, enableAll, disableAll;
    private EditText search;
    private InAppUpdater updater;
    private boolean showSystem, onlySelected, destroyed, scanning, checkingUpdate, resumed;
    private final Runnable ticker = new Runnable() { @Override public void run() { updateStatus(); handler.postDelayed(this, 1000); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Prefs.migrateModes(this);
        if (state != null) pendingVpnRevision = state.getLong("pending_vpn_revision", -1);
        updater = new InAppUpdater(this); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setPadding(dp(20), dp(8), dp(20), 0);
        root.setFitsSystemWindows(true); setContentView(root);
        LinearLayout top = row(); TextView brand = text("净屏", 29, INK); brand.setTypeface(null, Typeface.BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button settings = button("设置", false); settings.setOnClickListener(v -> showSettings()); top.addView(settings); root.addView(top);
        LinearLayout card = column(); card.setPadding(dp(12), dp(8), dp(12), dp(8)); card.setBackground(shape(0xffe3f1ec, 18));
        LinearLayout summary = row();
        statusText = text("过滤未运行", 18, INK); statusText.setTypeface(null, Typeface.BOLD); statusText.setMaxLines(2);
        summary.addView(statusText, new LinearLayout.LayoutParams(0, -2, 1));
        power = button("启动过滤", true); power.setTextSize(13); power.setPadding(dp(8),0,dp(8),0);
        power.setOnClickListener(v -> { if (Prefs.enabled(this)) stopFiltering(); else requestStart(); });
        summary.addView(power, new LinearLayout.LayoutParams(dp(100), dp(48)));
        Button fold = button("展开 ▾", false); fold.setTextSize(13); fold.setMinWidth(0); fold.setPadding(dp(4),0,dp(4),0);
        LinearLayout.LayoutParams foldLp = new LinearLayout.LayoutParams(dp(64),dp(48)); foldLp.leftMargin=dp(8); summary.addView(fold,foldLp);
        card.addView(summary);

        // Keep expanded details bounded so the application list remains usable.
        ScrollView detailScroll = new ScrollView(this);
        LinearLayout details = column(); details.setPadding(0,dp(8),0,0);
        scopeText = text("", 12, INK); details.addView(scopeText);
        skipStatus = text("", 12, MUTED); details.addView(skipStatus);
        networkStatus = text("", 12, MUTED); details.addView(networkStatus);
        statsText = text("", 12, MUTED); statsText.setPadding(0,dp(8),0,0); details.addView(statsText);
        details.addView(text("DNS 为本次网络会话计数；点击为本机累计请求数，均不代表广告清除数。",11,MUTED));
        detailScroll.addView(details);
        card.addView(detailScroll,new LinearLayout.LayoutParams(-1,Math.min(dp(220),getResources().getDisplayMetrics().heightPixels/4)));
        boolean expanded = Prefs.get(this).getBoolean("home_details_expanded",false);
        detailScroll.setVisibility(expanded ? View.VISIBLE : View.GONE);
        fold.setText(expanded ? "收起 ▴" : "展开 ▾"); fold.setContentDescription(expanded ? "收起防护详情" : "展开防护详情");
        fold.setOnClickListener(v -> {
            boolean open = detailScroll.getVisibility() != View.VISIBLE;
            detailScroll.setVisibility(open ? View.VISIBLE : View.GONE);
            fold.setText(open ? "收起 ▴" : "展开 ▾"); fold.setContentDescription(open ? "收起防护详情" : "展开防护详情");
            Prefs.get(this).edit().putBoolean("home_details_expanded",open).apply();
        });
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1,-2); cardLp.bottomMargin=dp(8); root.addView(card,cardLp);
        updateFeedback = text("", 12, INK); updateFeedback.setVisibility(View.GONE);

        search = new EditText(this); search.setSingleLine(true); search.setTextSize(14); search.setTextColor(INK);
        search.setHintTextColor(MUTED); search.setHint("搜索应用名称或包名"); search.setPadding(dp(16), 0, dp(16), 0); search.setBackground(shape(Color.WHITE, 14));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT); root.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout filters = row(); CheckBox selected = new CheckBox(this); selected.setText("仅已开启"); selected.setTextSize(12); selected.setButtonTintList(ColorStateList.valueOf(GREEN));
        selected.setOnCheckedChangeListener((b, on) -> { onlySelected = on; filter(); }); filters.addView(selected);
        CheckBox system = new CheckBox(this); system.setText("系统应用"); system.setTextSize(12); system.setButtonTintList(ColorStateList.valueOf(GREEN));
        system.setOnCheckedChangeListener((b, on) -> { showSystem = on; filter(); }); filters.addView(system);
        Space spacer = new Space(this); filters.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView refresh = text("刷新", 13, GREEN); refresh.setPadding(dp(8), dp(16), 0, dp(16)); refresh.setOnClickListener(v -> scan()); filters.addView(refresh); root.addView(filters);
        countText = text("正在读取已安装应用…", 12, MUTED);
        root.addView(countText, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout batch = row();
        enableAll = button("一键开启", false); enableAll.setEnabled(false);
        enableAll.setOnClickListener(v -> enableAllApps());
        batch.addView(enableAll, new LinearLayout.LayoutParams(0, dp(48), 1));
        disableAll = button("一键关闭", false);
        disableAll.setOnClickListener(v -> confirmDisableAllApps());
        batch.addView(disableAll, new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(batch);
        FrameLayout listArea = new FrameLayout(this); root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this); list.setDivider(null); list.setVerticalScrollBarEnabled(false); list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(16));
        adapter = new AppAdapter(); list.setAdapter(adapter); listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text("正在读取…", 15, MUTED); empty.setGravity(Gravity.CENTER); listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        scan();
        handler.postDelayed(() -> {
            if (!Prefs.get(this).getBoolean("onboarding_v1", false)) showHelp(true);
            else checkUpdate(false);
        }, 700);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; UpdateVpnPause.recover(this); updater.resume(); handler.removeCallbacks(ticker); handler.post(ticker);
        if (adapter != null) { adapter.notifyDataSetChanged(); updateStatus(); }
        handler.postDelayed(this::offerSkipSetup, 800);
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putLong("pending_vpn_revision", pendingVpnRevision); super.onSaveInstanceState(out); }
    @Override protected void onPause() { resumed = false; updater.pause(); handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; updater.close(); handler.removeCallbacksAndMessages(null); scanner.shutdownNow(); updates.shutdownNow();
        if (skipDialog != null) skipDialog.dismiss(); if (updateCheckDialog != null) updateCheckDialog.dismiss();
        super.onDestroy(); }
    private void scan() {
        if (scanning) return; scanning = true; enableAll.setEnabled(false); countText.setText("正在读取已安装应用…");
        scanner.execute(() -> {
            List<AppEntry> found = new ArrayList<>(); String problem = null;
            try {
                PackageManager pm = getPackageManager();
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    if (info.packageName.equals(getPackageName()) || !info.enabled) continue;
                    found.add(new AppEntry(info, info.loadLabel(pm).toString()));
                }
                Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE); found.sort((a,b) -> collator.compare(a.label, b.label));
            } catch (Exception e) { problem = "读取失败，请刷新或检查系统权限"; }
            final String error = problem;
            handler.post(() -> {
                if (destroyed) return; scanning = false;
                if (error != null) { countText.setText(error); empty.setText(error); return; }
                apps.clear(); apps.addAll(found); Set<String> valid = new HashSet<>(); for (AppEntry a : found) valid.add(a.info.packageName);
                Set<String> selected = Prefs.selected(this); if (selected.retainAll(valid)) { Prefs.get(this).edit().putStringSet("apps", selected).apply(); if (FilterVpnService.running) applyChanges(); }
                enableAll.setEnabled(true); filter();
            });
        });
    }
    private void enableAllApps() {
        if (scanning || !enableAll.isEnabled()) return;
        Set<String> candidates = new HashSet<>();
        for (AppEntry app : apps) if ((app.info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) candidates.add(app.info.packageName);
        if (candidates.isEmpty()) { toast("没有可开启的普通应用，请刷新应用列表"); return; }
        SelectionPlan plan = new SelectionPlan(Prefs.selected(this), Prefs.get(this).getStringSet("skip_only_apps", new HashSet<>()),
            Prefs.get(this).getStringSet("mode_configured_apps", new HashSet<>()), Prefs.exemptions(this), candidates, Prefs.conservative(this));
        int network = ProtectionMode.networkApps(plan.selected, plan.skipOnly).size();
        new AlertDialog.Builder(this).setTitle("一键开启前确认")
            .setMessage("将新增开启 " + plan.added + " 个普通应用。\n开启后共选择 " + plan.selected.size() + " 个，其中网络过滤 " + network + " 个。\n\n新应用" + (Prefs.conservative(this) ? "默认仅开屏跳过" : "默认网络过滤+跳过") + "；已有兼容设置保留。已豁免应用不会重新开启。\n\n建议先选择少量常用应用验证效果。网络过滤可能影响登录、支付或下载；出现异常可一键豁免。")
            .setNegativeButton("取消", null).setPositiveButton("确认开启", (d,w) -> {
                Prefs.get(this).edit().putStringSet("apps", plan.selected).putStringSet("skip_only_apps", plan.skipOnly).apply();
                filter(); requestStart();
            }).show();
    }
    private void confirmDisableAllApps() {
        int count = Prefs.selected(this).size();
        new AlertDialog.Builder(this).setTitle("一键关闭前确认")
            .setMessage("将清空全部 " + count + " 个应用选择，并暂停网络过滤和开屏跳过。\n\n各应用的兼容设置和豁免名单会保留，但本次选择清单不会保留；一键开启会按当前应用和豁免规则重新选择。\n\n如果只是临时停用，请取消并使用首页的“暂停防护”，它会保留选择。")
            .setNegativeButton("取消",null).setPositiveButton("确认关闭",(d,w) -> disableAllApps()).show();
    }
    private void disableAllApps() {
        // Clear the full selection, including hidden/system apps; retain compatibility settings.
        Prefs.get(this).edit().putBoolean("protection_enabled", false)
            .putStringSet("apps", new HashSet<String>()).apply();
        stopFiltering();
        filter();
        toast("已关闭全部应用防护，兼容设置已保留");
    }
    private void filter() {
        if (adapter == null) return; String q = search.getText().toString().trim().toLowerCase(Locale.ROOT); Set<String> selected = Prefs.selected(this);
        visible.clear(); for (AppEntry a : apps) {
            if (!showSystem && (a.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && !selected.contains(a.info.packageName)) continue;
            if (onlySelected && !selected.contains(a.info.packageName)) continue;
            if (q.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(q) || a.info.packageName.toLowerCase(Locale.ROOT).contains(q)) visible.add(a);
        }
        countText.setText(getString(R.string.application_count, visible.size(), selected.size()));
        if (empty != null) empty.setText(scanning ? "正在读取…" : "没有匹配的应用\n试试其他关键词，或显示系统应用");
        adapter.notifyDataSetChanged(); updateStatus();
    }
    private void updateStatus() {
        if (statusText == null) return;
        boolean active = Prefs.enabled(this);
        ProtectionStatus snapshot = new ProtectionStatus(active, Prefs.selected(this), Prefs.networkSelected(this),
            FilterVpnService.filteredApps, FilterVpnService.running, UpdateVpnPause.isPaused(this),
            Prefs.get(this).getBoolean("skip_consent", false) && SplashSkipService.connected);
        statusText.setText(snapshot.title()); statusText.setTextColor(active ? GREEN : INK);
        scopeText.setText(snapshot.scope());
        String skip = !active ? "已暂停" : !Prefs.get(this).getBoolean("skip_consent", false) ? "未授权" : !SplashSkipService.connected ? "未连接，请设置" : snapshot.selectedCount == 0 ? "未选择应用" : "已就绪";
        skipStatus.setText(getString(R.string.splash_status, skip));
        networkStatus.setText(!active ? "网络过滤：已暂停" : snapshot.networkCount == 0 ? "网络过滤：未配置，所有应用网络直连"
            : snapshot.updating ? "网络过滤：更新期间暂停，开屏跳过独立运行"
            : "网络过滤：已连接 " + snapshot.networkActiveCount + " / 配置 " + snapshot.networkCount + " 个");
        statsText.setText(getString(R.string.runtime_details, getString(R.string.dns_stats, FilterVpnService.queries.get(), FilterVpnService.blocked.get(), FilterVpnService.failed.get()),
            Prefs.get(this).getLong("skip_clicks", 0), FilterVpnService.status)
            + (Prefs.sniFiltering(this) ? "\nSNI 拦截 " + FilterVpnService.sniBlocked.get() + " · UDP/443 阻止 " + FilterVpnService.quicBlocked.get()
                + " · HTTP Host 拦截 " + FilterVpnService.httpBlocked.get() + " · 目标主机拦截 " + FilterVpnService.hostBlocked.get() : "")
            + (Prefs.get(this).getBoolean("dns_bypass_off", false) ? "" : "\n自带解析器拦截 " + FilterVpnService.bypassBlocked.get())
            + "\n别名（CNAME）伪装拦截 " + FilterVpnService.aliased.get());
        power.setText(snapshot.action());
    }
    private void requestStart() {
        if (Prefs.selected(this).isEmpty()) { toast("先打开至少一个应用右侧的开关"); return; }
        if (Prefs.networkSelected(this).isEmpty()) { Prefs.enabled(this, true); startFiltering(); return; }
        if (!Prefs.get(this).getBoolean("consent", false)) {
            new AlertDialog.Builder(this).setTitle("启用本地广告过滤").setMessage(
                "净屏使用 Android 本地 VPN，为选中应用过滤已知广告域名。应用列表保存在手机中，不上传；不解密 HTTPS。\n\n未拦截的域名会发送给设置中选定的公共 DNS，传统 DNS 传输未加密。当前 DNS：" + Prefs.get(this).getString("resolver", "223.5.5.5") +
                "\n\n会占用系统 VPN 通道，与其他 VPN 互斥。系统严格私人 DNS、应用内加密 DNS、缓存和同域广告可能绕过过滤。首次连接需系统授权。")
                .setNegativeButton("暂不开启", null).setPositiveButton("同意并继续", (d,w) -> { Prefs.get(this).edit().putBoolean("consent", true).apply(); requestStart(); }).show(); return;
        }
        Prefs.enabled(this, true); updateStatus();
        if (UpdateVpnPause.isPaused(this)) {
            UpdateVpnPause.networkRequested(this);
            toast("开屏跳过已按开关恢复；网络过滤将在更新结束后恢复"); return;
        }
        Intent permission = VpnService.prepare(this);
        if (permission != null) {
            if (pendingVpnRevision >= 0) { toast("请先完成系统 VPN 授权"); return; }
            pendingVpnRevision = Prefs.control(this).revision;
            startActivityForResult(permission, 31);
        } else startFiltering();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 31) return;
        long token = pendingVpnRevision; pendingVpnRevision = -1;
        if (!Prefs.control(this).acceptsPermission(token, !Prefs.selected(this).isEmpty())) { updateStatus(); return; }
        if (result == RESULT_OK) startFiltering();
        else { FilterVpnService.status = "VPN 未授权，网络过滤未运行"; updateStatus();
            new AlertDialog.Builder(this).setTitle("网络过滤未启动").setMessage("系统 VPN 授权未完成。开屏跳过需单独授权，网络目前直连。可在运行详情中重新连接网络过滤。").setPositiveButton("知道了", null).show(); }
    }
    private void startFiltering() {
        // A late VPN permission result must not reactivate an empty selection after bulk disable.
        if (!Prefs.enabled(this) || Prefs.selected(this).isEmpty()) { stopFiltering(); return; }
        if (Prefs.networkSelected(this).isEmpty()) {
            UpdateVpnPause.userDisabled(this); FilterVpnService.stopFiltering(this, "仅开屏跳过，未启用网络过滤");
            updateStatus(); handler.postDelayed(this::offerSkipSetup, 500); return;
        }
        try { startForegroundService(new Intent(this, FilterVpnService.class));
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 32);
        } catch (Exception e) { toast("无法启动：" + e.getMessage()); }
        handler.postDelayed(this::updateStatus, 400);
        handler.postDelayed(this::offerSkipSetup, 900);
    }
    private void stopFiltering() { Prefs.enabled(this, false); UpdateVpnPause.userDisabled(this); FilterVpnService.stopFiltering(this, "防护已暂停"); updateStatus(); handler.postDelayed(this::updateStatus, 250); }
    private void applyChanges() { if (Prefs.selected(this).isEmpty()) stopFiltering(); else if (Prefs.enabled(this)) requestStart(); }
    private void offerSkipSetup() {
        if (destroyed || !resumed || isFinishing() || !Prefs.enabled(this) || Prefs.selected(this).isEmpty()) return;
        if (Prefs.get(this).getBoolean("skip_consent", false) && !Prefs.screenshotOcrConsent(this)
                && !Prefs.get(this).getBoolean("splash_ocr_prompted", false)) { showOcrConsent(); return; }
        if (!Prefs.get(this).getBoolean("skip_prompted", false)) showSkipSetup();
    }
    private static final String ACCESSIBILITY_RISK = "无障碍权限可读取屏幕中可访问的界面内容并点击按钮。净屏只在已选应用进入前台后15秒内查找开屏跳过控件；系统授予的能力范围大于这一用途。\n\n净屏优先点击应用公开的系统点击动作。没有公开动作的自绘按钮，只能按核对过的跳过文字中心触摸：要求连续两轮读到同一位置、触摸前后核对窗口、每次进入只触摸一次，并带广告依据或倒计时。纯“跳过”兼容也可能误认教程入口；连续两次触摸界面没变化会自动停用该应用的坐标触摸。\n\n可选的本机截图 OCR 只用于控件树读不到跳过字的情况，仅处理已选择应用进入后的短时画面。截图与文字只在本机内存临时处理，不保存、不上传；本机仅保存可清除的包名、时间、次数和固定诊断原因。暂停防护保留权限但停止处理；撤销权限请进入系统无障碍设置。";
    private void showSkipSetup() {
        Prefs.get(this).edit().putBoolean("skip_prompted", true).apply();
        if (skipDialog != null && skipDialog.isShowing()) return;
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(8), dp(24), dp(8));
        panel.addView(text("服务：" + (SplashSkipService.connected ? "已连接" : "未连接") + "\n防护开关：" + (Prefs.enabled(this) ? "已开启" : "已暂停，设置权限不会自动恢复防护"),14,INK));
        panel.addView(text("无障碍权限可读取屏幕控件并执行点击，可能误触。开启前会展示完整用途与风险说明；你可随时在系统设置撤销权限。",12,MUTED));
        panel.addView(text(AccessibilitySetup.authorized(this) ? "已具备快捷开启授权，可由净屏提交连接请求。" : "由系统设置确认权限，无需 Root 或连接电脑。",12,MUTED));
        Button enable = button(SplashSkipService.connected ? "管理系统无障碍权限" : "开启开屏跳过",false);
        enable.setOnClickListener(v -> { if (SplashSkipService.connected) openAccessibilitySettings(); else requestSkipConsent(); }); panel.addView(enable);
        Button ocrSetting = button(Build.VERSION.SDK_INT < 30 ? "本机截图 OCR · 需要 Android 11+"
            : Prefs.screenshotOcrConsent(this) ? "关闭本机截图 OCR" : "开启本机截图 OCR",false);
        ocrSetting.setEnabled(Build.VERSION.SDK_INT >= 30);
        ocrSetting.setOnClickListener(v -> {
            if (skipDialog != null) skipDialog.dismiss();
            if (!Prefs.screenshotOcrConsent(this)) showOcrConsent();
            else new AlertDialog.Builder(this).setTitle("关闭本机截图 OCR")
                .setMessage("关闭后仍会使用无障碍控件树识别可读取的跳过按钮。")
                .setNegativeButton("取消",null).setPositiveButton("关闭 OCR",(d,w) -> {
                    Prefs.screenshotOcrConsent(this,false); Prefs.get(this).edit().putBoolean("splash_ocr_prompted",true).apply();
                    toast("本机截图 OCR 已关闭"); updateStatus();
                }).show();
        }); panel.addView(ocrSetting);
        Button diagnostic = button("查看最近跳过诊断",false); diagnostic.setOnClickListener(v -> showSkipDiagnostic()); panel.addView(diagnostic);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        skipDialog = new AlertDialog.Builder(this).setTitle("开屏跳过设置").setView(scroll).setPositiveButton("关闭",null).create(); skipDialog.show();
    }
    private void requestSkipConsent() {
        new AlertDialog.Builder(this).setTitle("授权前请了解屏幕读取与点击风险")
            .setMessage(ACCESSIBILITY_RISK)
            .setNegativeButton("暂不开启",null).setPositiveButton("了解风险并继续",(d,w) -> {
                Prefs.get(this).edit().putBoolean("skip_consent",true).putBoolean("splash_ocr_consent",false)
                    .putBoolean("splash_ocr_prompted",false).putInt("skip_disclosure_version",3).apply();
                enableSkip();
            }).show();
    }
    private void showOcrConsent() {
        if (destroyed || !resumed || isFinishing() || Build.VERSION.SDK_INT < 30) return;
        new AlertDialog.Builder(this).setTitle("启用本机截图 OCR？")
            .setMessage("只在控件树读不到跳过文字时使用；限已选择应用进入前台后的15秒内。截图与识别文字仅在手机内存中短暂处理，不保存、不上传。识别到明确跳过按钮、两帧位置一致且前台窗口未变时，才最多触摸一次；识别不清或截图失败就不点击。开启会增加无障碍服务的屏幕读取能力。")
            .setNegativeButton("暂不开启",(d,w) -> Prefs.get(this).edit().putBoolean("splash_ocr_prompted",true).apply())
            .setPositiveButton("同意并开启",(d,w) -> {
                Prefs.get(this).edit().putBoolean("splash_ocr_consent",true).putBoolean("splash_ocr_prompted",true).apply();
                toast("本机截图 OCR 已开启"); updateStatus();
            }).setOnCancelListener(d -> Prefs.get(this).edit().putBoolean("splash_ocr_prompted",true).apply()).show();
    }
    private void openAccessibilitySettings() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (ActivityNotFoundException e) { new AlertDialog.Builder(this).setTitle("请手动开启系统设置").setMessage("在系统设置中搜索“无障碍”，找到“净屏 · 开屏广告跳过”。也可在此撤销权限。").setPositiveButton("知道了",null).show(); }
    }
    private void enableSkip() {
        if (SplashSkipService.connected) {
            updateStatus(); new AlertDialog.Builder(this).setTitle("无障碍服务已连接")
                .setMessage(Prefs.enabled(this) ? "开屏跳过已可用，请确认目标应用开关已打开。" : "防护仍保持暂停。回到首页点击“恢复防护”后生效。")
                .setPositiveButton("知道了",null).show(); return;
        }
        if (AccessibilitySetup.authorized(this)) {
            try {
                if (AccessibilitySetup.enable(this)) {
                    new AlertDialog.Builder(this).setTitle("已提交开启请求")
                        .setMessage("正在等待系统连接无障碍服务。连接状态显示在首页。若仍未连接，请进入系统无障碍设置检查。防护开关不会因设置权限而自动改变。")
                        .setPositiveButton("知道了",null).setNeutralButton("系统设置",(d,w) -> openAccessibilitySettings()).show();
                    handler.postDelayed(this::updateStatus,1500); return;
                }
            } catch (SecurityException | IllegalArgumentException e) { /* Fall back to the visible system authorization page. */ }
        }
        openAccessibilitySettings();
    }
    private void updateFeedback(String value) {
        if (destroyed) return;
        updateFeedback.setText(value); updateFeedback.setVisibility(View.VISIBLE);
        if (updateButton != null) { updateButton.setEnabled(!checkingUpdate); updateButton.setText(checkingUpdate ? "正在检查更新…" : "检查更新 · 当前 v" + BuildConfig.VERSION_NAME); }
    }
    private void showUpdateCheckProgress() {
        if (updateCheckDialog != null && updateCheckDialog.isShowing()) return;
        LinearLayout panel = row(); panel.setPadding(dp(24),dp(16),dp(24),dp(16));
        ProgressBar spinner = new ProgressBar(this); panel.addView(spinner,new LinearLayout.LayoutParams(dp(32),dp(32)));
        TextView text = text("  正在连接 GitHub 检查版本…\n  网络过滤暂时断开，开屏跳过独立运行",13,INK); panel.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        updateCheckDialog = new AlertDialog.Builder(this).setTitle("检查更新").setView(panel).setNegativeButton("后台检查",null).create(); updateCheckDialog.show();
    }
    private void checkUpdate(boolean manual) {
        if (destroyed) return;
        if (checkingUpdate) { if (manual) showUpdateCheckProgress(); return; }
        if (updater.hasPending()) { if (manual) updater.showExisting(); return; }
        if (!manual && !Prefs.get(this).getBoolean("auto_update", false)) return;
        if (!manual && System.currentTimeMillis() - Prefs.get(this).getLong("update_success", 0) < 86400000L) return;
        if (!Prefs.get(this).getBoolean("update_consent", false)) {
            if (!manual) return;
            new AlertDialog.Builder(this).setTitle("检查 GitHub 新版本")
                .setMessage("净屏会访问公开 GitHub 发布记录，GitHub 会收到常规网络连接信息；不上传手机应用列表。本次同意只授权检查与下载，不自动开启后台检查；自动检查需单独打开。")
                .setNegativeButton("取消", null).setPositiveButton("同意并检查", (d,w) -> { Prefs.get(this).edit().putBoolean("update_consent", true).apply(); checkUpdate(true); }).show(); return;
        }
        final long vpnToken;
        try { vpnToken = UpdateVpnPause.beginCheck(this); }
        catch (RuntimeException e) { if (manual) { updateFeedback("检查未开始：" + e.getMessage()); new AlertDialog.Builder(this).setTitle("检查未开始").setMessage(e.getMessage()).setPositiveButton("知道了",null).show(); } return; }
        checkingUpdate = true; updateFeedback("正在检查更新…"); if (manual) showUpdateCheckProgress();
        updates.execute(() -> {
            UpdateChecker.Release release = null; String problem = null;
            try { release = UpdateChecker.check(); Prefs.get(this).edit().putLong("update_success", System.currentTimeMillis()).apply(); }
            catch (Exception e) { problem = e.getMessage(); }
            // Use a process handler so rotation/destruction cannot discard the restoration callback.
            new Handler(Looper.getMainLooper()).post(() -> UpdateVpnPause.endCheck(getApplicationContext(), vpnToken));
            final UpdateChecker.Release found = release; final String error = problem;
            handler.post(() -> {
                checkingUpdate = false; if (destroyed || isFinishing()) return;
                if (updateCheckDialog != null) { updateCheckDialog.dismiss(); updateCheckDialog = null; }
                updateFeedback(error != null ? "检查失败：" + error : found == null ? "当前已是最新发布版本 v" + BuildConfig.VERSION_NAME : "发现新版本 v" + found.version);
                if (error != null || found == null) {
                    if (manual) new AlertDialog.Builder(this).setTitle(error != null ? "检查失败" : "检查完成")
                        .setMessage(error != null ? error : "当前已是净屏最新发布版本 v" + BuildConfig.VERSION_NAME).setPositiveButton("知道了",null).show();
                    return;
                }
                new AlertDialog.Builder(this).setTitle("发现净屏 v" + found.version)
                    .setMessage(found.notes + "\n\n将在净屏内使用当前网络下载，显示进度并校验文件；下载完成后点击安装，由系统确认。")
                    .setNegativeButton("稍后", null).setPositiveButton("立即更新", (d,w) -> updater.start(found)).show();
            });
        });
    }
    private void showSettings() {
        String[] items = {"开屏跳过与权限", "应用豁免", "防护方式与网络", "版本更新", "帮助与隐私"};
        new AlertDialog.Builder(this).setTitle("设置").setItems(items,(d,which) -> {
            switch (which) {
                case 0: showSkipSetup(); break;
                case 1: showExemptPicker(); break;
                case 2: showNetworkSettings(); break;
                case 3: showUpdateSettings(); break;
                case 4: showHelp(false); break;
                default: break;
            }
        }).setNegativeButton("关闭",null).show();
    }
    private void showUpdateSettings() {
        LinearLayout panel = column(); panel.setPadding(dp(24),dp(8),dp(24),dp(8));
        if (updateFeedback.getParent() instanceof ViewGroup) ((ViewGroup)updateFeedback.getParent()).removeView(updateFeedback);
        panel.addView(updateFeedback);
        updateButton = button(checkingUpdate ? "正在检查更新…" : "检查更新 · 当前 v" + BuildConfig.VERSION_NAME, false);
        updateButton.setEnabled(!checkingUpdate); updateButton.setOnClickListener(v -> checkUpdate(true)); panel.addView(updateButton);
        TextView updatePrivacy = text("检查和下载前自动暂时断开净屏 VPN，结束、失败或取消后按原状态恢复；安装后再检查恢复。更新中手动暂停防护会保留你的选择。APP 内显示下载进度，安装由系统确认；仅访问 GitHub，不上传应用列表。", 12, MUTED);
        panel.addView(updatePrivacy);
        Switch autoUpdate = new Switch(this); autoUpdate.setText("自动检查新版本"); autoUpdate.setTextSize(14);
        autoUpdate.setChecked(Prefs.get(this).getBoolean("auto_update", false));
        autoUpdate.setOnCheckedChangeListener((b,on) -> { Prefs.get(this).edit().putBoolean("auto_update", on).apply(); if (on) checkUpdate(true); });
        panel.addView(autoUpdate);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle("版本更新").setView(scroll).setPositiveButton("关闭",null).show();
    }
    private void showNetworkSettings() {
        LinearLayout panel = column(); panel.setPadding(dp(24),dp(8),dp(24),dp(8));
        Switch conservative = new Switch(this); conservative.setText("保守模式：新开启应用默认仅开屏跳过"); conservative.setTextSize(13); conservative.setChecked(Prefs.conservative(this));
        conservative.setOnCheckedChangeListener((b,on) -> Prefs.get(this).edit().putBoolean("conservative_new_apps",on).apply()); panel.addView(conservative);
        panel.addView(text("不自动更改已有应用的防护方式。长按应用可主动开启网络过滤；新安装不会默认选中任何应用。",12,MUTED));
        Button retry = button("重新连接网络过滤",false); retry.setOnClickListener(v -> {
            if (!Prefs.enabled(this)) {
                new AlertDialog.Builder(this).setTitle("防护已暂停").setMessage("回到首页点击“恢复防护”后，再重新连接网络过滤。").setPositiveButton("知道了",null).show();
            } else requestStart();
        }); panel.addView(retry);
        TextView info = text("DNS 上游", 15, INK); panel.addView(info);
        Spinner dns = new Spinner(this); String[] names = {"阿里公共 DNS · 223.5.5.5", "腾讯公共 DNS · 119.29.29.29", "Cloudflare · 1.1.1.1"};
        String[] addresses = {"223.5.5.5", "119.29.29.29", "1.1.1.1"};
        dns.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String saved = Prefs.get(this).getString("resolver", addresses[0]); for (int i = 0; i < addresses.length; i++) if (saved.equals(addresses[i])) dns.setSelection(i);
        panel.addView(dns, new LinearLayout.LayoutParams(-1, dp(48)));
        CheckBox sni = new CheckBox(this); sni.setText("SNI 增强过滤（实验）"); sni.setTextSize(14); sni.setChecked(Prefs.sniFiltering(this));
        sni.setEnabled(Build.VERSION.SDK_INT >= 29);
        if (Build.VERSION.SDK_INT < 29) sni.setChecked(false);
        panel.addView(sni);
        TextView sniInfo = text(Build.VERSION.SDK_INT < 29 ? "SNI 增强模式需要 Android 10 及以上；此设备仍可使用基础 DNS 过滤和开屏跳过。" : "开启后，所选应用的完整 IPv4/IPv6 流量经本机隧道转发；检查 TCP/443 的可见 SNI 与 TCP/80 的 Host，不解密 HTTPS。UDP/443 会被阻止以尝试触发 HTTP/3 回退，可能造成部分应用变慢或无法联网；ECH、DoH、直连 IP 和缓存内容仍可能绕过。",12,MUTED); panel.addView(sniInfo);
        CheckBox bypass = new CheckBox(this); bypass.setText("阻断应用自带加密 DNS（推荐）"); bypass.setTextSize(14);
        bypass.setChecked(!Prefs.get(this).getBoolean("dns_bypass_off", false)); panel.addView(bypass);
        panel.addView(text("应用把广告域名交给自己的 DoH/HTTPDNS 解析时，这台手机的 DNS 过滤就看不到请求，规则再好也拦不住。开启后分两层处理：一是这些解析接口的域名被按广告同样处理（返回 NXDOMAIN），解析退回本机过滤；二是公共解析器的地址（如 1.1.1.1、8.8.8.8、223.5.5.5）被单独路由进隧道，明文 UDP/53 照常按规则过滤，加密的 DoH/DoT/DoQ 因内容不可见而直接丢弃。基础模式同样生效，不需要开 SNI 增强。副作用：某些应用若写死了自己的解析器，首次解析可能变慢或失败；若某个应用因此无法联网，先关掉这一项再试。",12,MUTED));
        TextView label = text("例外域名（每行一个，包含其子域名）", 14, INK); label.setPadding(0, dp(16), 0, dp(8)); panel.addView(label);
        EditText allow = new EditText(this); allow.setTextSize(14); allow.setHint("example.com"); allow.setMinLines(3); allow.setMaxLines(6);
        allow.setGravity(Gravity.TOP); allow.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        allow.setText(Prefs.get(this).getString("exceptions", "")); panel.addView(allow);
        int ruleCount = builtInRuleCount();
        TextView details = text((ruleCount < 0 ? "内置规则读取失败" : "内置 " + ruleCount + " 条域名拦截规则 · " + builtInRuleExceptionCount() + " 条上游例外") + " · v" + BuildConfig.VERSION_NAME + "\n\n规则来自四份 DNS 专用广告过滤列表（AdGuard DNS、OISD、anti-AD、AdRules）与净屏自建清单，只保留能表达为域名的部分，按 DNS 域名匹配（含子域名）。另外会检查 DNS 应答里的 CNAME 别名，若最终别名落在拦截清单内同样按 NXDOMAIN 处理，用于对付用自有域名做 CNAME 伪装、把请求指到广告域的 SDK。带路径、通配符、站点或应用条件的规则无法映射到 DNS，会被跳过。\n\n若应用功能异常，可添加例外域名或关闭该应用的开关。\n\n系统“私人 DNS”建议设为关闭；应用自带加密 DNS 可能绕过过滤。基础模式处理 UDP DNS；可选 SNI 增强模式会接管所选应用的 IPv4/IPv6 流量，检查 TCP/443 可见主机名但不解密 HTTPS，并阻止 UDP/443以尝试触发 HTTP/3 回退。\n\nDNS 规则命中会重复计数，包含追踪请求；转发失败不代表广告漏拦。跳过点击次数仅表示系统接受了点击请求，无法证明广告消失。DNS 计数从启动或重新配置时开始，跳过点击次数在本机累计。\n\n暂停防护将同时停止过滤和自动跳过，保留应用选择和权限。手动暂停后不会由更新回调恢复；点击“恢复防护”才重新启用。无障碍服务连接与防护开关是两个独立状态。", 12, MUTED);
        details.setPadding(0, dp(16), 0, 0); panel.addView(details);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("防护方式与网络").setView(scroll).setNegativeButton("取消", null).setNeutralButton("网络设置", (d,w) -> startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = allow.getText().toString().trim(); StringBuilder normalized = new StringBuilder();
            try {
                for (String domain : value.split("[\\s,，]+")) if (!domain.trim().isEmpty()) normalized.append(RuleSet.normalize(domain)).append('\n');
            } catch (IllegalArgumentException e) { allow.setError("仅填写域名，例如 example.com；不含 https:// 或路径"); return; }
            Runnable save = () -> {
                Prefs.get(this).edit().putString("exceptions", normalized.toString()).putString("resolver", addresses[dns.getSelectedItemPosition()])
                    .putBoolean("sni_filtering", sni.isChecked()).putBoolean("dns_bypass_off", !bypass.isChecked()).apply();
                if (Prefs.enabled(this)) applyChanges(); dialog.dismiss(); toast("设置已保存");
            };
            if (sni.isChecked() && !Prefs.sniFiltering(this)) {
                new AlertDialog.Builder(this).setTitle("开启实验性 SNI 过滤")
                    .setMessage("所选应用会切换到完整本地隧道；UDP/443 被阻止后，部分应用可能无法回退到 TCP。若联网异常，请回到这里关闭 SNI 增强过滤或豁免应用。")
                    .setNegativeButton("取消",null).setPositiveButton("确认开启",(confirm,w) -> save.run()).show();
            } else save.run();
        })); dialog.show();
    }
    private int builtInRuleCount() {
        return ruleStats().blocked;
    }
    private int builtInRuleExceptionCount() {
        return Math.max(0, ruleStats().exceptions);
    }
    private RuleStats ruleStats() {
        try { return RuleStats.parse(getAssets().open("ad-domains.counts")); }
        catch (IOException ignored) { return RuleStats.parse(null); }
    }
    private void showHelp(boolean first) {
        int ruleCount = builtInRuleCount();
        String coverage = ruleCount < 0 ? "当前内置规则读取失败。" : "当前内置 " + ruleCount + " 条域名拦截规则和 " + builtInRuleExceptionCount() + " 条上游例外，覆盖范围有限。";
        String description = "净屏 v" + BuildConfig.VERSION_NAME + "（构建 " + BuildConfig.VERSION_CODE + "）\n本机 DNS 域名过滤与开屏按钮辅助跳过工具，不保证消除所有广告。\n\n"
            + "开始使用\n1. 首次安装不自动选择应用。先搜索并开启少量常用应用。\n2. 保守模式默认对新开启应用只做开屏跳过，不干预网络；长按应用可主动改成网络过滤+跳过。\n3. 开屏跳过需要单独授权无障碍。网络过滤需要系统 VPN 授权，与其他 VPN 互斥。\n\n"
            + "暂停与豁免\n暂停保留应用开关，恢复由你手动执行；一键关闭需确认后才清空全部选择，兼容设置和豁免保留；重新一键开启不等于恢复原选择清单。应用登录、支付、下载异常时，进入“设置 → 应用豁免”选择应用即可豁免，之后一键开启也不会重新选中它。\n\n"
            + "权限与隐私\n应用列表、选择、兼容设置及固定诊断记录只保存在本机。不解密 HTTPS。经你同意后，截图 OCR 仅在已选应用启动阶段于本机内存短暂处理，不保存或上传截图、识别文字或坐标。无障碍授权具备较广的界面读取与点击能力，误触可能影响广告、教程或登录引导。可在净屏设置关闭 OCR，也可在系统无障碍设置撤销服务。\n\n"
            + "网络访问\n网络过滤会把未拦截域名发送给你选定的公共 DNS，DNS 传输未加密。检查更新与 APK 下载连接 GitHub，服务器会收到常规网络连接信息；不上传应用列表。自动检查默认关闭。\n\n"
            + "实际能做什么\n" + coverage + "规则取自四份 DNS 专用广告过滤列表可表达为域名的子集（约 28 万个域名，含 CNAME 别名检查）；带路径、通配符或站点条件的拦截规则不会直接套用。可在“设置 → 防护方式与网络”手动开启实验性 SNI 增强：它检查 TCP/443 可见主机名，不解密 HTTPS，并阻止 UDP/443尝试触发回退。ECH、应用自带 DoH、直连 IP、同域内容或缓存仍可能绕过；广告与业务内容混用同一域名、或直接由 IP 提供的广告也无法在网络层区分。DNS 过滤能拦住广告素材与接口请求，但不保证清除每一个广告位。\n\n"
            + "开屏广告\nDNS 过滤不能保证关闭开屏广告。开屏跳过是单独的无障碍辅助功能，只会尝试点击可识别的跳过按钮；无权限、无法识别或不满足安全条件时不会点击，可能仍需等待广告结束，也不保证零延迟。\n\n"
            + "怎样理解命中次数\n命中长期为 0～2 只表示本次命中规则的请求很少，不能证明没有广告。仅开屏跳过模式不做 DNS 过滤；命中请求也不等于成功去掉广告，点击请求不等于跳过成功。\n\n"
            + "数字说明\n列表显示数受搜索和筛选影响；已选是你的开关数量，分为仅跳过与网络+跳过。网络已连接数量来自实际建立的隧道；开屏已就绪表示服务可用，不代表每个应用都能识别成功。";
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(first ? "欢迎使用净屏 · 请先了解" : "使用帮助、隐私与关于").setMessage(description);
        if (first) builder.setNegativeButton("稍后", null).setPositiveButton("开始选择应用", (d,w) -> Prefs.get(this).edit().putBoolean("onboarding_v1", true).apply());
        else builder.setPositiveButton("关闭",null);
        builder.show();
    }
    private void showExemptPicker() {
        if (apps.isEmpty()) { new AlertDialog.Builder(this).setTitle("应用列表尚未就绪").setMessage("请等待应用扫描完成，或点击刷新后重试。").setPositiveButton("知道了",null).show(); return; }
        List<AppEntry> choices = new ArrayList<>(apps);
        Set<String> selected = Prefs.selected(this), exempt = Prefs.exemptions(this);
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        choices.sort((a,b) -> {
            int priority = Integer.compare(exemptionPriority(a,selected,exempt),exemptionPriority(b,selected,exempt));
            if (priority != 0) return priority;
            int name = collator.compare(a.label,b.label);
            return name != 0 ? name : a.info.packageName.compareTo(b.info.packageName);
        });
        List<String> labels = new ArrayList<>();
        for (AppEntry app : choices) {
            String pkg = app.info.packageName;
            boolean system = (app.info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            String state = exempt.contains(pkg) ? "已豁免，点击解除" : selected.contains(pkg) ? "已开启，点击豁免" : "未开启，点击豁免";
            labels.add(app.label + "\n" + pkg + (system ? " · 系统应用" : "") + " · " + state);
        }
        LinearLayout panel = column(); panel.setPadding(dp(20),0,dp(20),0);
        panel.addView(text("已开启的普通应用优先，其次已豁免和其他普通应用；系统应用放在最后。可按名称或包名搜索。\n豁免后停止该应用的防护，且一键开启不会重新选中它。",12,MUTED));
        EditText query = new EditText(this); query.setSingleLine(true); query.setHint("搜索应用名称或包名"); panel.addView(query);
        ArrayAdapter<String> items = new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels);
        ListView list = new ListView(this); list.setAdapter(items); panel.addView(list,new LinearLayout.LayoutParams(-1,dp(280)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("应用异常 / 豁免管理").setView(panel).setNegativeButton("关闭",null).create();
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { items.getFilter().filter(s); }
            public void afterTextChanged(Editable e) { }
        });
        list.setOnItemClickListener((parent,view,position,id) -> {
            String label = items.getItem(position); int original = labels.indexOf(label);
            if (original < 0) return;
            AppEntry app = choices.get(original); boolean on = !Prefs.exemptions(this).contains(app.info.packageName);
            Prefs.exempt(this,app.info.packageName,on); applyChanges(); filter(); dialog.dismiss();
            new AlertDialog.Builder(this).setTitle(on ? "已豁免 " + app.label : "已解除豁免")
                .setMessage(on ? "该应用的网络过滤和开屏点击均已停止，一键开启不会重新选中它。" : "该应用尚未开启防护，可手动打开开关或使用一键开启。")
                .setPositiveButton("知道了",null).show();
        });
        dialog.show();
    }
    private int exemptionPriority(AppEntry app, Set<String> selected, Set<String> exempt) {
        String pkg = app.info.packageName;
        int system = (app.info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0 ? 3 : 0;
        return system + (selected.contains(pkg) ? 0 : exempt.contains(pkg) ? 1 : 2);
    }
    private String appLabel(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }
    private void showPlainSkip(String pkg, String label) {

        LinearLayout panel = column(); panel.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView info = text("启动慢、开屏黑屏时，可尝试仅开屏跳过：保留此应用的网络请求，通过点击跳过处理开屏广告。广告可能短暂出现，页面内其他广告不再使用域名过滤。", 13, MUTED); panel.addView(info);
        RadioGroup modes = new RadioGroup(this);
        RadioButton both = new RadioButton(this); both.setId(View.generateViewId()); both.setText("网络过滤 + 开屏跳过"); modes.addView(both);
        RadioButton skipOnly = new RadioButton(this); skipOnly.setId(View.generateViewId()); skipOnly.setText("仅开屏跳过（启动兼容）"); modes.addView(skipOnly);
        modes.check(Prefs.skipOnly(this, pkg) ? skipOnly.getId() : both.getId()); panel.addView(modes);
        CheckBox plain = new CheckBox(this); plain.setText("允许纯“跳过”按钮（跳过兼容）"); plain.setChecked(Prefs.plainSkip(this, pkg)); panel.addView(plain);
        CheckBox tap = new CheckBox(this); tap.setText("允许坐标触摸（自绘跳过按钮）"); tap.setChecked(Prefs.textTapEnabled(this, pkg)); panel.addView(tap);
        TextView tapInfo = text("部分广告 SDK 的跳过按钮没有公开点击动作。开启本机截图 OCR 后，控件树读不到文字时会在本机临时识别画面；两帧确认同一处明确跳过文字后才触摸，前后复核窗口，每次进入只触摸一次。连续两次触摸界面都没变化会自动退回。触摸仍有误入广告页的可能，关闭后只使用公开点击动作。",12,MUTED); panel.addView(tapInfo);
        TextView warning = text("纯跳过兼容只对这个应用生效，也可能跳过教程或登录引导。防护只在进入应用后15秒内处理可读取或本机 OCR 识别的跳过按钮。", 12, MUTED); panel.addView(warning);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        new AlertDialog.Builder(this).setTitle(label + " · 防护方式").setView(scroll)
            .setNegativeButton("取消", null).setPositiveButton("保存", (d,w) -> {
                boolean only = modes.getCheckedRadioButtonId() == skipOnly.getId();
                boolean networkChanged = Prefs.skipOnly(this, pkg) != only;
                Prefs.skipOnly(this, pkg, only); Prefs.plainSkip(this, pkg, plain.isChecked());
                Prefs.textTapEnabled(this, pkg, tap.isChecked());
                if (networkChanged && Prefs.enabled(this)) applyChanges();
                filter(); toast("已保存，请退出并重新打开目标应用");
            }).show();
    }
    private void showSkipDiagnostic() {
        SharedPreferences record = getSharedPreferences("splash_diagnostic", MODE_PRIVATE);
        String pkg = record.getString("package", "");
        String detail;
        if (pkg.isEmpty()) detail = "还没有检测记录。\n\n确认目标应用开关已打开、开屏跳过已就绪，然后退出并重新打开目标应用，再回这里查看。";
        else detail = "应用：" + appLabel(pkg) + "\n包名：" + pkg
            + "\n时间：" + java.text.DateFormat.getDateTimeInstance().format(new Date(record.getLong("time", 0)))
            + "\n单次扫描最多读取节点：" + record.getInt("nodes", 0)
            + "\n单次读取最高耗时：" + record.getLong("max_scan_ms", 0) + "ms"
            + "\n防护方式：" + (Prefs.skipOnly(this, pkg) ? "仅开屏跳过" : "网络过滤 + 开屏跳过")
            + "\n曾读到跳过文字：" + (record.getBoolean("skip", false) ? "是" : "否")
            + "\n曾读到广告标识：" + (record.getBoolean("ad", false) ? "是" : "否")
            + "\n扫描曾达到上限：" + (record.getBoolean("limited", false) ? "是" : "否")
            + "\n纯跳过兼容：" + (Prefs.plainSkip(this, pkg) ? "开启" : "关闭")
            + "\n文字定位触摸：" + Prefs.textTapState(this, pkg)
            + "\n本机截图 OCR：" + record.getString("ocr_status", "未运行")
            + "（本次处理 " + record.getInt("ocr_frames", 0) + " 帧）"
            + "\n本次点击：" + record.getString("action", "旧版记录，请重新检测")
            + "\n点击目标：" + record.getString("target", "未发送点击")
            + "\n\n关键结果：" + record.getString("reason", "暂无结果")
            + "\n最近状态：" + record.getString("status", "旧版记录")
            + "\n\n截图和识别文字只在本机内存临时处理，不保存、不上传；诊断只存状态，不存 OCR 文字或坐标。控件消失和点击请求次数都不等于确认广告清除。";
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("最近跳过诊断").setMessage(detail)
            .setPositiveButton("关闭", null).setNegativeButton("清除记录", (d,w) -> record.edit().clear().apply());
        builder.show();
    }
    private final class AppAdapter extends BaseAdapter {
        public int getCount() { return visible.size(); } public AppEntry getItem(int i) { return visible.get(i); } public long getItemId(int i) { return i; }
        public View getView(int position, View reuse, android.view.ViewGroup parent) {
            Holder h;
            if (reuse == null) {
                LinearLayout outer = column(); outer.setPadding(0, 0, 0, dp(8)); LinearLayout cell = row();
                cell.setPadding(dp(12), dp(12), dp(10), dp(12)); cell.setBackground(shape(Color.WHITE, 15)); outer.addView(cell, new LinearLayout.LayoutParams(-1, -2));
                h = new Holder(); h.icon = new ImageView(MainActivity.this); cell.addView(h.icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
                LinearLayout labels = column(); labels.setPadding(dp(12), 0, dp(4), 0); cell.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                h.name = text("", 15, INK); h.name.setSingleLine(true); h.name.setEllipsize(TextUtils.TruncateAt.END); h.name.setTypeface(null, Typeface.BOLD); labels.addView(h.name);
                h.pkg = text("", 10, MUTED); h.pkg.setSingleLine(true); h.pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE); labels.addView(h.pkg);
                h.toggle = new Switch(MainActivity.this); h.toggle.setShowText(false); h.toggle.setMinimumWidth(dp(48)); h.toggle.setMinHeight(dp(48));
                cell.addView(h.toggle, new LinearLayout.LayoutParams(-2, dp(48))); outer.setTag(h); reuse = outer;
            } else h = (Holder)reuse.getTag();
            AppEntry a = getItem(position); h.name.setText(a.label);
            h.pkg.setText(getString(R.string.app_mode, a.info.packageName, Prefs.exemptions(MainActivity.this).contains(a.info.packageName) ? "已豁免" : Prefs.skipOnly(MainActivity.this,a.info.packageName) ? "仅开屏跳过" : "网络+跳过"));
            reuse.setOnLongClickListener(v -> { showPlainSkip(a.info.packageName, a.label); return true; });
            try { h.icon.setImageDrawable(a.info.loadIcon(getPackageManager())); } catch (Exception e) { h.icon.setImageResource(android.R.drawable.sym_def_app_icon); }
            h.toggle.setOnCheckedChangeListener(null); h.toggle.setChecked(Prefs.selected(MainActivity.this).contains(a.info.packageName));
            h.toggle.setContentDescription(a.label + (Prefs.skipOnly(MainActivity.this, a.info.packageName) ? " 开屏跳过防护" : " 网络过滤与开屏跳过防护"));
            h.toggle.setOnCheckedChangeListener((b,on) -> {
                String pkg = a.info.packageName;
                if (on && Prefs.exemptions(MainActivity.this).contains(pkg)) {
                    filter();
                    new AlertDialog.Builder(MainActivity.this).setTitle("此应用已豁免")
                        .setMessage("解除豁免并重新开启 " + a.label + "？")
                        .setNegativeButton("保持豁免",null).setPositiveButton("解除并开启",(d,w) -> { Prefs.exempt(MainActivity.this,pkg,false); Prefs.select(MainActivity.this,pkg,true); requestStart(); filter(); }).show();
                    return;
                }
                Prefs.select(MainActivity.this,pkg,on);
                if (on && !Prefs.enabled(MainActivity.this)) requestStart(); else applyChanges(); filter();
            }); return reuse;
        }
    }
    static final class AppEntry { final ApplicationInfo info; final String label; AppEntry(ApplicationInfo i, String l) { info = i; label = l; } }
    static final class Holder { ImageView icon; TextView name, pkg; Switch toggle; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private TextView text(String s, int size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); return t; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private Button button(String s, boolean filled) {
        Button b = new Button(this); b.setText(s); b.setTextSize(14); b.setAllCaps(false); b.setTextColor(filled ? Color.WHITE : GREEN);
        b.setBackground(shape(filled ? GREEN : BG, 12)); b.setPadding(dp(16), 0, dp(16), 0); b.setMinHeight(dp(44)); return b;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
