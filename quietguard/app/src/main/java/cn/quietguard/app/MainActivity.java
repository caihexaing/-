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
import java.text.Collator;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int INK = 0xff192c32, MUTED = 0xff687a80, GREEN = 0xff12856f, BG = 0xfff5f7f8;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps = new ArrayList<>(), visible = new ArrayList<>();
    private AppAdapter adapter;
    private TextView statusText, countText, statsText, empty, skipStatus;
    private Button power, skipSetup;
    private EditText search;
    private boolean showSystem, onlySelected, destroyed, scanning, checkingUpdate, resumed;
    private final Runnable ticker = new Runnable() { @Override public void run() { updateStatus(); handler.postDelayed(this, 1000); } };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setPadding(dp(20), dp(8), dp(20), 0);
        root.setFitsSystemWindows(true); setContentView(root);
        LinearLayout top = row(); TextView brand = text("净屏", 29, INK); brand.setTypeface(null, Typeface.BOLD);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(56), 1));
        Button settings = button("设置", false); settings.setOnClickListener(v -> showSettings()); top.addView(settings); root.addView(top);
        TextView intro = text("给常用应用，少一点打扰。", 14, MUTED); intro.setPadding(0, 0, 0, dp(20)); root.addView(intro);

        LinearLayout card = column(); card.setPadding(dp(20), dp(18), dp(20), dp(16)); card.setBackground(shape(0xffe3f1ec, 22));
        TextView tag = text("LOCAL PROTECTION  /  本机过滤", 11, GREEN); tag.setLetterSpacing(.08f); card.addView(tag);
        statusText = text("过滤未运行", 22, INK); statusText.setTypeface(null, Typeface.BOLD); statusText.setPadding(0, dp(12), 0, dp(8)); card.addView(statusText);
        statsText = text("", 13, MUTED); card.addView(statsText);
        power = button("启动过滤", true); power.setOnClickListener(v -> {
            if (Prefs.enabled(this) || FilterVpnService.running) stopFiltering(); else requestStart();
        });
        skipStatus = text("", 12, MUTED); skipStatus.setPadding(0, dp(8), 0, 0); card.addView(skipStatus);
        LinearLayout.LayoutParams powerLp = new LinearLayout.LayoutParams(-1, dp(48)); powerLp.topMargin = dp(12); card.addView(power, powerLp); root.addView(card);
        skipSetup = button("开启开屏广告跳过", false); skipSetup.setOnClickListener(v -> showSkipSetup());
        root.addView(skipSetup, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView note = text("DNS 规则命中 ≠ 去除广告数；开屏跳过需无障碍授权", 11, MUTED); note.setPadding(0, dp(4), 0, dp(10)); root.addView(note);

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
        countText = text("正在读取已安装应用…", 12, MUTED); countText.setPadding(0, 0, 0, dp(8)); root.addView(countText);
        FrameLayout listArea = new FrameLayout(this); root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this); list.setDivider(null); list.setVerticalScrollBarEnabled(false); list.setClipToPadding(false); list.setPadding(0, 0, 0, dp(16));
        adapter = new AppAdapter(); list.setAdapter(adapter); listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text("正在读取…", 15, MUTED); empty.setGravity(Gravity.CENTER); listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        scan();
        handler.postDelayed(() -> checkUpdate(false), 1500);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; handler.removeCallbacks(ticker); handler.post(ticker);
        if (adapter != null) { adapter.notifyDataSetChanged(); updateStatus(); }
        handler.postDelayed(this::offerSkipSetup, 800);
    }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; handler.removeCallbacksAndMessages(null); scanner.shutdownNow(); super.onDestroy(); }
    private void scan() {
        if (scanning) return; scanning = true; countText.setText("正在读取已安装应用…");
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
                filter();
            });
        });
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
        statusText.setText(FilterVpnService.status);
        statsText.setText(getString(R.string.dns_stats, FilterVpnService.queries.get(), FilterVpnService.blocked.get(), FilterVpnService.failed.get()));
        boolean active = Prefs.enabled(this);
        String skip = !Prefs.get(this).getBoolean("skip_consent", false) ? "未授权" : !SplashSkipService.connected ? "未连接，请开启" : active ? "已就绪" : "已暂停";
        skipStatus.setText(getString(R.string.splash_status, skip, Prefs.get(this).getLong("skip_clicks", 0)));
        power.setText(active || FilterVpnService.running ? "暂停防护" : "启用防护");
        skipSetup.setText(SplashSkipService.connected ? "开屏跳过设置" : "开启开屏广告跳过");
    }
    private void requestStart() {
        if (Prefs.selected(this).isEmpty()) { toast("先打开至少一个应用右侧的开关"); return; }
        if (!Prefs.get(this).getBoolean("consent", false)) {
            new AlertDialog.Builder(this).setTitle("启用本地广告过滤").setMessage(
                "净屏使用 Android 本地 VPN，为选中应用过滤已知广告域名。应用列表保存在手机中，不上传；不解密 HTTPS。\n\n未拦截的域名会发送给设置中选定的公共 DNS，传统 DNS 传输未加密。当前 DNS：" + Prefs.get(this).getString("resolver", "223.5.5.5") +
                "\n\n会占用系统 VPN 通道，与其他 VPN 互斥。系统严格私人 DNS、应用内加密 DNS、缓存和同域广告可能绕过过滤。首次连接需系统授权。")
                .setNegativeButton("暂不开启", null).setPositiveButton("同意并继续", (d,w) -> { Prefs.get(this).edit().putBoolean("consent", true).apply(); requestStart(); }).show(); return;
        }
        Prefs.enabled(this, true);
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, 31); else startFiltering();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (request == 31) { if (result == RESULT_OK) startFiltering(); else { toast("未授权 VPN，网络过滤未启动；开屏跳过可单独授权使用"); handler.postDelayed(this::offerSkipSetup, 500); } }
    }
    private void startFiltering() {
        Prefs.enabled(this, true);
        try { startForegroundService(new Intent(this, FilterVpnService.class));
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 32);
        } catch (Exception e) { toast("无法启动：" + e.getMessage()); }
        handler.postDelayed(this::updateStatus, 400);
        handler.postDelayed(this::offerSkipSetup, 900);
    }
    private void stopFiltering() { Prefs.enabled(this, false); stopService(new Intent(this, FilterVpnService.class)); handler.postDelayed(this::updateStatus, 250); }
    private void applyChanges() { if (Prefs.selected(this).isEmpty()) stopFiltering(); else if (FilterVpnService.running) startFiltering(); }
    private void offerSkipSetup() {
        if (!destroyed && resumed && !isFinishing() && Prefs.enabled(this) && !Prefs.selected(this).isEmpty()
                && !Prefs.get(this).getBoolean("skip_prompted", false)) showSkipSetup();
    }
    private void showSkipSetup() {
        Prefs.get(this).edit().putBoolean("skip_prompted", true).apply();
        new AlertDialog.Builder(this).setTitle("启用开屏广告自动跳过")
            .setMessage("为处理你截图中的“4s｜跳过”，净屏需要 Android 无障碍授权。\n\n只在已开启的应用进入前台后 15 秒内，读取其界面控件，识别右上角带倒计时或广告标识的“跳过”按钮并尝试点击。不会点击广告主体或“启动”等推广按钮。\n\n界面内容只在本机临时处理，不保存、不上传。无障碍属于敏感权限，你可随时在系统设置中关闭，也可在净屏暂停防护。\n\n系统授权后回到净屏，确认“开屏跳过：已就绪”，再重新打开目标应用。广告可能短暂出现；无法保证跳过所有广告。")
            .setNegativeButton("暂不开启", null).setPositiveButton("同意并前往设置", (d,w) -> {
                Prefs.get(this).edit().putBoolean("skip_consent", true).apply();
                try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                catch (ActivityNotFoundException e) { toast("请在系统设置中搜索“无障碍”，开启“净屏 · 开屏广告跳过”"); }
            }).show();
    }
    private void checkUpdate(boolean manual) {
        if (destroyed || checkingUpdate) return;
        if (!manual && System.currentTimeMillis() - Prefs.get(this).getLong("update_success", 0) < 86400000L) return;
        if (!Prefs.get(this).getBoolean("update_consent", false)) {
            if (!manual) return;
            new AlertDialog.Builder(this).setTitle("检查 GitHub 新版本")
                .setMessage("净屏会访问公开 GitHub 发布记录，GitHub 会收到常规网络连接信息；不上传手机应用列表。开启后每天首次启动时自动检查，也可在设置中关闭。")
                .setNegativeButton("取消", null).setPositiveButton("同意并检查", (d,w) -> { Prefs.get(this).edit().putBoolean("update_consent", true).apply(); checkUpdate(true); }).show(); return;
        }
        checkingUpdate = true; if (manual) toast("正在检查新版本…");
        scanner.execute(() -> {
            UpdateChecker.Release release = null; String problem = null;
            try { release = UpdateChecker.check(); Prefs.get(this).edit().putLong("update_success", System.currentTimeMillis()).apply(); }
            catch (Exception e) { problem = e.getMessage(); }
            final UpdateChecker.Release found = release; final String error = problem;
            handler.post(() -> {
                checkingUpdate = false; if (destroyed || isFinishing()) return;
                if (error != null) { if (manual) toast("检查失败：" + error); return; }
                if (found == null) { if (manual) toast("当前已是净屏最新发布版本"); return; }
                new AlertDialog.Builder(this).setTitle("发现净屏 v" + found.version)
                    .setMessage(found.notes + "\n\n下载后请打开 APK 并确认安装。若 GitHub 连接不畅，可稍后重试。")
                    .setNegativeButton("稍后", null).setPositiveButton("下载新版", (d,w) -> {
                        try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(found.apkUrl))); }
                        catch (ActivityNotFoundException e) { toast("请安装浏览器以下载更新"); }
                    }).show();
            });
        });
    }
    private void showSettings() {
        LinearLayout panel = column(); panel.setPadding(dp(24), dp(8), dp(24), dp(8));
        Button skip = button("设置开屏广告跳过", false); skip.setOnClickListener(v -> showSkipSetup()); panel.addView(skip);
        TextView explanation = text("同一个应用开关控制 DNS 过滤和开屏跳过。跳过功能需要额外的系统无障碍授权。域名命中与跳过点击都不代表清除全部广告。", 12, MUTED);
        panel.addView(explanation);
        Button update = button("检查更新 · 当前 v" + BuildConfig.VERSION_NAME, false);
        update.setOnClickListener(v -> checkUpdate(true)); panel.addView(update);
        TextView updatePrivacy = text("启动时检查 GitHub 中净屏的新版本，仅发送网络请求，不上传应用列表。下载和安装由你确认。", 12, MUTED);
        panel.addView(updatePrivacy);
        Switch autoUpdate = new Switch(this); autoUpdate.setText("自动检查新版本"); autoUpdate.setTextSize(14);
        autoUpdate.setChecked(Prefs.get(this).getBoolean("update_consent", false));
        autoUpdate.setOnCheckedChangeListener((b,on) -> { Prefs.get(this).edit().putBoolean("update_consent", on).apply(); if (on) checkUpdate(true); });
        panel.addView(autoUpdate);
        TextView info = text("DNS 上游", 15, INK); panel.addView(info);
        Spinner dns = new Spinner(this); String[] names = {"阿里公共 DNS · 223.5.5.5", "腾讯公共 DNS · 119.29.29.29", "Cloudflare · 1.1.1.1"};
        String[] addresses = {"223.5.5.5", "119.29.29.29", "1.1.1.1"};
        dns.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String saved = Prefs.get(this).getString("resolver", addresses[0]); for (int i = 0; i < addresses.length; i++) if (saved.equals(addresses[i])) dns.setSelection(i);
        panel.addView(dns, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView label = text("例外域名（每行一个，包含其子域名）", 14, INK); label.setPadding(0, dp(16), 0, dp(8)); panel.addView(label);
        EditText allow = new EditText(this); allow.setTextSize(14); allow.setHint("example.com"); allow.setMinLines(3); allow.setMaxLines(6);
        allow.setGravity(Gravity.TOP); allow.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        allow.setText(Prefs.get(this).getString("exceptions", "")); panel.addView(allow);
        int ruleCount = 0; try { ruleCount = new RuleSet(getAssets().open("ad-domains.txt"), "").size(); } catch (Exception ignored) { }
        TextView details = text("内置 " + ruleCount + " 条精选域名规则 · v" + BuildConfig.VERSION_NAME + "\n\n若应用功能异常，可添加例外域名或关闭该应用的开关。\n\n系统“私人 DNS”建议设为关闭；应用自带加密 DNS 可能绕过过滤。本版处理 UDP DNS，不支持 TCP DNS 隧道。\n\nDNS 规则命中会重复计数，包含追踪请求；转发失败不代表广告漏拦。跳过点击次数仅表示系统接受了点击请求，无法证明广告消失。DNS 计数从启动或重新配置时开始，跳过点击次数在本机累计。\n\n暂停防护将同时停止过滤和自动跳过。无障碍服务由系统管理，进程被系统重启后可能恢复已授权的自动跳过；VPN 不会自动恢复，请查看首页两个真实状态。", 12, MUTED);
        details.setPadding(0, dp(16), 0, 0); panel.addView(details);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("过滤设置").setView(scroll).setNegativeButton("取消", null).setNeutralButton("网络设置", (d,w) -> startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = allow.getText().toString().trim(); StringBuilder normalized = new StringBuilder();
            try {
                for (String domain : value.split("[\\s,，]+")) if (!domain.trim().isEmpty()) normalized.append(RuleSet.normalize(domain)).append('\n');
            } catch (IllegalArgumentException e) { allow.setError("仅填写域名，例如 example.com；不含 https:// 或路径"); return; }
            Prefs.get(this).edit().putString("exceptions", normalized.toString()).putString("resolver", addresses[dns.getSelectedItemPosition()]).apply();
            if (FilterVpnService.running) startFiltering(); dialog.dismiss(); toast("设置已保存");
        })); dialog.show();
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
            AppEntry a = getItem(position); h.name.setText(a.label); h.pkg.setText(a.info.packageName);
            try { h.icon.setImageDrawable(a.info.loadIcon(getPackageManager())); } catch (Exception e) { h.icon.setImageResource(android.R.drawable.sym_def_app_icon); }
            h.toggle.setOnCheckedChangeListener(null); h.toggle.setChecked(Prefs.selected(MainActivity.this).contains(a.info.packageName));
            h.toggle.setContentDescription(a.label + " 广告防护，网络过滤与开屏跳过");
            h.toggle.setOnCheckedChangeListener((b,on) -> { Prefs.select(MainActivity.this, a.info.packageName, on); if (on && (!Prefs.enabled(MainActivity.this) || !FilterVpnService.running)) requestStart(); else applyChanges(); filter(); }); return reuse;
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
