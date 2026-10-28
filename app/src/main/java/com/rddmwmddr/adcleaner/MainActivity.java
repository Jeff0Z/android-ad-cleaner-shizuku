package com.rddmwmddr.adcleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.util.Base64;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.*;
import rikka.shizuku.Shizuku;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Date;

/** Local, user-confirmed ad component suspension through Shizuku. */
public final class MainActivity extends Activity {
    private static final int REQUEST_CODE = 42;
    private static final String VIVO_QUICKAPP_KEY = "setting:switch_quick_app_service";
    private static final String[] CANDIDATES = {"com.opos.ads", "com.heytap.pictorial", "com.miui.systemAdSolution",
        "com.nearme.instant.platform", "com.oppo.instant.local.service", "com.nearme.instant.card",
        "com.hihonor.quickengine", "com.hihonor.quickgame", "com.honor.quickgamecenter", "com.vivo.hybrid",
        "com.huawei.fastapp", "com.miui.hybrid", "com.miui.hybrid.accessory"};
    private static final Map<String,String> COMPONENT_LABELS = new LinkedHashMap<>();
    static {
        COMPONENT_LABELS.put("com.opos.ads", "系统广告组件"); COMPONENT_LABELS.put("com.heytap.pictorial", "锁屏画报");
        COMPONENT_LABELS.put("com.miui.systemAdSolution", "小米系统广告（msa）");
        COMPONENT_LABELS.put("com.nearme.instant.platform", "快应用平台（OPPO 系，已实测）");
        COMPONENT_LABELS.put("com.oppo.instant.local.service", "快应用本地服务（已实测）");
        COMPONENT_LABELS.put("com.nearme.instant.card", "快应用卡片（已实测）");
        COMPONENT_LABELS.put("com.hihonor.quickengine", "荣耀快应用引擎（已实测）");
        COMPONENT_LABELS.put("com.hihonor.quickgame", "荣耀快游戏（已实测）");
        COMPONENT_LABELS.put("com.honor.quickgamecenter", "荣耀快游戏中心（已实测）");
        COMPONENT_LABELS.put("com.huawei.fastapp", "华为快应用中心（挂起效果未实测）");
        COMPONENT_LABELS.put("com.miui.hybrid", "小米快应用框架（挂起效果未实测）");
        COMPONENT_LABELS.put("com.miui.hybrid.accessory", "小米快应用框架辅件（挂起效果未实测）");
        COMPONENT_LABELS.put("com.vivo.hybrid", "vivo / iQOO 快应用（系统开关与挂起效果依机型）");
    }
    private static final Pattern APP_LINE = Pattern.compile("AppSettings: (\\S+)(?: \\((\\d+)\\))?.*");
    private static final Pattern CHANNEL_LINE = Pattern.compile("NotificationChannel\\{mId='(.*?)', mName=(.*?), mDescription=.*?mImportance=(-?\\d+).*?mDeleted=(\\w+).*");
    private static final Pattern IMP_LINE = Pattern.compile("mImportance=(-?\\d+)");
    private static final String CHANNEL_DUMP = "dumpsys notification --noredact | awk '"
        + "/^[[:space:]]*AppSettings:/ {print; next} "
        + "/^[[:space:]]*NotificationChannel\\{/ {"
        + "sub(/, mBypassDnd=.*mDeleted=/, \", mDeleted=\"); "
        + "sub(/, mDeletedTimeMs=.*mBlockableSystem=/, \", mBlockableSystem=\"); "
        + "sub(/, mAllowBubbles=.*/, \"\"); print}'";
    private static final Pattern MARKETING = Pattern.compile("营销|活动|推荐|推广|热点|热门|资讯|新闻|福利|优惠|特惠|精选|精彩|运营|促销|红包|直播|种草|内容|头条|好物|权益|会员|签到|广告|recommend|promo|market|deal|news|offer", Pattern.CASE_INSENSITIVE);
    private static final Pattern SERVICE_CHANNEL = Pattern.compile("下载|更新|安装|上传|订单|物流|快递|交易|支付|付款|账号|安全|验证|通话|来电|消息|聊天|私信|评论|互动|提醒|闹钟|日程|天气预警|系统|前台|后台|服务运行|播放|媒体|热点和网络共享|智能热点|红包助手|盗窃|驾驶状态|权限建议|运营商|警告|告警|实时活动|赛事|护眼|上层显示", Pattern.CASE_INSENSITIVE);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, CheckBox> boxes = new LinkedHashMap<>();
    private final Map<String,String> componentInfo = new HashMap<>();
    private LinearLayout root, list, channelList, restoreChannelList;
    private final Map<String, Channel> channelItems = new LinkedHashMap<>();
    private final Map<String, CheckBox> channelBoxes = new LinkedHashMap<>(), restoreChannelBoxes = new LinkedHashMap<>();
    private TextView status;
    private Button clean;
    private boolean refreshChannelsOnResume;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (code, result) -> runOnUiThread(() -> {
        if (code == REQUEST_CODE && result == PackageManager.PERMISSION_GRANTED) {
            String error = ShizukuShell.connect(this);
            status.setText(error.isEmpty() ? "Shizuku 已授权并正在连接特权服务。连接完成后可扫描设备上的广告组件。" : error);
        }
        else status.setText("Shizuku 授权未通过。请重新授权后使用。");
    });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        buildUi(); updatePermissionState();
    }
    @Override protected void onResume() {
        super.onResume();
        if (refreshChannelsOnResume) {
            refreshChannelsOnResume = false;
            scanChannels();
        }
    }
    private void buildUi() {
        ScrollView scroll = new ScrollView(this); root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,28,28,28); scroll.addView(root);
        TextView title = new TextView(this); title.setText("手机去广告"); title.setTextSize(25); title.setTextColor(Color.rgb(20,60,100)); root.addView(title);
        TextView intro = new TextView(this); intro.setText("使用 Shizuku 在手机本机检查广告与快应用组件。挂起前会再次确认，不卸载、不停用，已确认的改动可恢复。\n"); intro.setTextSize(15); root.addView(intro);
        Button authorize = new Button(this); authorize.setText("连接 / 授权 Shizuku"); authorize.setOnClickListener(v -> requestShizuku()); root.addView(authorize, fullWidth());
        Button scan = new Button(this); scan.setText("扫描已安装组件"); scan.setOnClickListener(v -> scan()); root.addView(scan, fullWidth());
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); root.addView(list, fullWidth());
        clean = new Button(this); clean.setText("挂起所选组件"); clean.setOnClickListener(v -> confirmSuspend()); root.addView(clean, fullWidth());
        Button restore = new Button(this); restore.setText("恢复本应用挂起的组件"); restore.setOnClickListener(v -> restore()); root.addView(restore, fullWidth());
        Button snapshot = new Button(this); snapshot.setText("生成 / 查看本地改动前快照"); snapshot.setOnClickListener(v -> showSnapshot()); root.addView(snapshot, fullWidth());
        Button accessibility = new Button(this); accessibility.setText("启用通知渠道辅助操作"); accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))); root.addView(accessibility, fullWidth());
        Button channelScan = new Button(this); channelScan.setText("扫描营销通知渠道"); channelScan.setOnClickListener(v -> scanChannels()); root.addView(channelScan, fullWidth());
        channelList = new LinearLayout(this); channelList.setOrientation(LinearLayout.VERTICAL); root.addView(channelList, fullWidth());
        Button channelOff = new Button(this); channelOff.setText("关闭所选单个营销渠道"); channelOff.setOnClickListener(v -> confirmChannel(false)); root.addView(channelOff, fullWidth());
        restoreChannelList = new LinearLayout(this); restoreChannelList.setOrientation(LinearLayout.VERTICAL); root.addView(restoreChannelList, fullWidth());
        Button channelOn = new Button(this); channelOn.setText("恢复所选通知渠道"); channelOn.setOnClickListener(v -> confirmChannel(true)); root.addView(channelOn, fullWidth());
        status = new TextView(this); status.setTextSize(14); status.setTextColor(Color.DKGRAY); status.setPadding(0,20,0,20); root.addView(status, fullWidth()); setContentView(scroll);
        refreshChannelRestoreList();
    }
    private LinearLayout.LayoutParams fullWidth() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private void requestShizuku() {
        if (!Shizuku.pingBinder()) { status.setText("未检测到 Shizuku 服务。请安装并启动 Shizuku；Android 11+ 可用无线调试在手机本地启动。"); return; }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            String error = ShizukuShell.connect(this);
            status.setText(error.isEmpty() ? "Shizuku 已授权并正在连接特权服务。" : error);
        }
        else Shizuku.requestPermission(REQUEST_CODE);
    }
    private void updatePermissionState() {
        if (!Shizuku.pingBinder()) status.setText("请先启动 Shizuku。Android 11 及以上可用无线调试，无需电脑或 USB。");
        else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) status.setText("请点击“连接 / 授权 Shizuku”并允许本应用。");
        else { status.setText("Shizuku 已授权。请扫描已安装组件。"); ShizukuShell.connect(this); }
    }
    private void scan() {
        status.setText("正在扫描…");
        worker.execute(() -> {
            ArrayList<String> found = new ArrayList<>(); HashSet<String> suspendedAlready = new HashSet<>(); String failure = "";
            for (String pkg : CANDIDATES) {
                String out = ShizukuShell.run(this, "pm path " + pkg + "; dumpsys package " + pkg + " | grep -m1 -o 'suspended=[a-z]*'");
                if (out.contains("package:")) { found.add(pkg); if (out.contains("suspended=true")) suspendedAlready.add(pkg); }
                else if (out.startsWith("错误") || out.startsWith("Shizuku")) { failure = out; break; }
            }
            HashSet<String> quickPackages = new HashSet<>();
            for (String pkg : found) if (isQuickApp(pkg)) quickPackages.add(pkg);
            HashMap<String,Integer> recentUses = new HashMap<>(); HashSet<String> running = new HashSet<>(); boolean usageReadable=true;
            for (String pkg : quickPackages) recentUses.put(pkg,0);
            if (!quickPackages.isEmpty()) {
                String useDump = ShizukuShell.run(this, "dumpsys usagestats | grep -E 'type=ACTIVITY_RESUMED package=(com.nearme.instant.platform|com.oppo.instant.local.service|com.nearme.instant.card|com.hihonor.quickengine|com.hihonor.quickgame|com.honor.quickgamecenter|com.huawei.fastapp|com.miui.hybrid|com.miui.hybrid.accessory)'");
                usageReadable = !useDump.contains("[output truncated]") && !Pattern.compile("(?i)error|exception|permission denial|not allowed").matcher(useDump).find();
                Pattern resumed = Pattern.compile("type=ACTIVITY_RESUMED package=(\\S+)");
                for (String line : useDump.split("\\r?\\n")) { Matcher m=resumed.matcher(line); if (m.find() && recentUses.containsKey(m.group(1))) recentUses.put(m.group(1),recentUses.get(m.group(1))+1); }
                for (String pkg : quickPackages) {
                    String pids=ShizukuShell.run(this,"pidof " + pkg + "; pidof " + pkg + ":provider");
                    if (pids.matches("(?s).*\\n[^\\n]*\\d+.*")) running.add(pkg);
                }
            }
            String brand = commandOutput(ShizukuShell.run(this, "getprop ro.product.brand")).trim().toLowerCase(Locale.ROOT);
            String vivoSwitch = commandOutput(ShizukuShell.run(this, "settings --user 0 get secure switch_quick_app_service")).trim();
            boolean showVivoSwitch = (brand.equals("vivo") || brand.equals("iqoo")) && (vivoSwitch.equals("0") || vivoSwitch.equals("1"));
            final String scanFailure = failure;
            final boolean usageReadOk = usageReadable;
            runOnUiThread(() -> {
                boxes.clear(); componentInfo.clear(); list.removeAllViews();
                for (String pkg : found) {
                    String info = isQuickApp(pkg) ? "\n近期打开记录：" + (usageReadOk ? recentUses.getOrDefault(pkg,0) + " 次" : "系统未提供") + "；" + (running.contains(pkg) ? "后台运行中" : "未检测到后台进程") : "";
                    if (isQuickApp(pkg)) componentInfo.put(pkg,info);
                    CheckBox cb = new CheckBox(this); cb.setText(COMPONENT_LABELS.get(pkg) + (suspendedAlready.contains(pkg) ? "（当前已挂起，未重复处理）" : "") + info + "\n" + pkg); cb.setTag(pkg);
                    if (suspendedAlready.contains(pkg)) cb.setEnabled(false);
                    list.addView(cb); boxes.put(pkg, cb);
                }
                if (showVivoSwitch) {
                    CheckBox cb = new CheckBox(this); cb.setText("vivo / iQOO 快应用服务开关（系统设置，原状态 " + vivoSwitch + "）\nswitch_quick_app_service");
                    cb.setTag(VIVO_QUICKAPP_KEY); cb.setEnabled(vivoSwitch.equals("1")); list.addView(cb); boxes.put(VIVO_QUICKAPP_KEY,cb);
                }
                status.setText(!scanFailure.isEmpty() ? "扫描失败：" + scanFailure : found.isEmpty() && !showVivoSwitch ? "未发现上游清单中的已知广告或快应用组件。" : "发现 " + (found.size() + (showVivoSwitch ? 1 : 0)) + " 个候选。未实测的快应用框架会明确标记；请核对后再选择。");
            });
        });
    }
    private void confirmSuspend() {
        ArrayList<String> selected = selected();
        if (selected.isEmpty()) { status.setText("请先扫描并勾选要处理的组件。"); return; }
        ArrayList<String> details = new ArrayList<>();
        for (String key : selected) details.add((COMPONENT_LABELS.getOrDefault(key,key)) + componentInfo.getOrDefault(key,"") + "\n" + key);
        new AlertDialog.Builder(this).setTitle("确认挂起")
            .setMessage("将使用 Shizuku 关闭以下所选组件或系统开关，不会卸载或停用应用：\n\n" + android.text.TextUtils.join("\n\n", details) + "\n\n部分厂商功能可能受影响。是否继续？")
            .setNegativeButton("取消", null).setPositiveButton("挂起", (d,w) -> doSuspend(selected)).show();
    }
    private ArrayList<String> selected() { ArrayList<String> result = new ArrayList<>(); for (Map.Entry<String,CheckBox> e : boxes.entrySet()) if (e.getValue().isChecked()) result.add(e.getKey()); return result; }
    private boolean isQuickApp(String pkg) { String label=COMPONENT_LABELS.get(pkg); return label!=null && (label.startsWith("快应用") || label.startsWith("荣耀快") || label.startsWith("华为快") || label.startsWith("小米快")); }
    private void doSuspend(ArrayList<String> selected) {
        status.setText("正在挂起并回读状态…"); clean.setEnabled(false);
        worker.execute(() -> {
            File backup = createSnapshot(null);
            if (backup == null) { runOnUiThread(() -> { status.setText("改动前快照未能保存，已取消挂起。请检查 Shizuku 授权和设备空间后重试。"); clean.setEnabled(true); }); return; }
            StringBuilder report = new StringBuilder(); Set<String> recorded = new HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("suspended", Collections.emptySet()));
            report.append("本地改动前快照：").append(backup.getName()).append('\n');
            for (String pkg : selected) {
                if (pkg.equals(VIVO_QUICKAPP_KEY)) {
                    String note = disableVivoQuickApp(); report.append(note).append('\n'); continue;
                }
                String out = ShizukuShell.run(this, "pm suspend --user 0 " + pkg);
                String state = ShizukuShell.run(this, "dumpsys package " + pkg + " | grep -m1 -o 'suspended=[a-z]*'");
                if (state.contains("suspended=true")) {
                    String networkNote = pkg.equals("com.miui.systemAdSolution") ? disableMsaNetwork() : "";
                    recorded.add(pkg); getPreferences(MODE_PRIVATE).edit().putStringSet("suspended", recorded).commit(); report.append("已挂起：").append(pkg);
                    if (!networkNote.isEmpty()) report.append("\n  网络处理：").append(networkNote);
                    report.append('\n');
                    if (pkg.startsWith("com.nearme.instant.") || pkg.startsWith("com.oppo.instant.") || pkg.startsWith("com.hihonor.quick") || pkg.equals("com.honor.quickgamecenter") || pkg.equals("com.huawei.fastapp") || pkg.startsWith("com.miui.hybrid")) ShizukuShell.run(this, "am force-stop --user 0 " + pkg);
                } else report.append("失败：").append(pkg).append("\n").append(out).append('\n');
            }
            runOnUiThread(() -> { status.setText(report.toString()); clean.setEnabled(true); });
        });
    }
    private void restore() {
        Set<String> saved = getPreferences(MODE_PRIVATE).getStringSet("suspended", Collections.emptySet());
        ArrayList<String> packages = new ArrayList<>(saved);
        boolean hasVivo = getPreferences(MODE_PRIVATE).contains("vivoQuickAppOriginal");
        if (packages.isEmpty() && !hasVivo) { status.setText("本应用没有记录待恢复的组件。"); return; }
        String summary = (packages.isEmpty() ? "" : "解除以下组件的挂起状态？\n\n" + android.text.TextUtils.join("\n", packages))
            + (hasVivo ? "\n\n恢复 vivo / iQOO 快应用服务开关的原值。" : "");
        new AlertDialog.Builder(this).setTitle("恢复组件").setMessage(summary)
            .setNegativeButton("取消", null).setPositiveButton("恢复", (d,w) -> worker.execute(() -> {
                StringBuilder report = new StringBuilder(); Set<String> remaining = new HashSet<>();
                for (String pkg : packages) {
                    String result = ShizukuShell.run(this, "pm unsuspend --user 0 " + pkg);
                    String state = ShizukuShell.run(this, "dumpsys package " + pkg + " | grep -m1 -o 'suspended=[a-z]*'");
                    boolean restored = state.contains("suspended=false") || state.contains("not installed");
                    if (restored && pkg.equals("com.miui.systemAdSolution")) {
                        String network = restoreMsaNetwork();
                        if (!network.isEmpty()) { restored = false; result += "\n网络恢复未确认：" + network; }
                    }
                    report.append(pkg).append(restored ? "：已恢复\n" : "：恢复未确认\n" + result + "\n");
                    if (!restored) remaining.add(pkg);
                }
                if (hasVivo) {
                    String result = restoreVivoQuickApp(); report.append(result).append('\n');
                    if (!result.contains("已恢复")) getPreferences(MODE_PRIVATE).edit().putBoolean("vivoQuickAppRestorePending", true).apply();
                }
                getPreferences(MODE_PRIVATE).edit().putStringSet("suspended", remaining).apply(); runOnUiThread(() -> status.setText(report.toString()));
            })).show();
    }
    private String disableVivoQuickApp() {
        String current = commandOutput(ShizukuShell.run(this, "settings --user 0 get secure switch_quick_app_service")).trim();
        if (!current.equals("1")) return "vivo 快应用服务原值不是 1，未更改：" + current;
        if (!getPreferences(MODE_PRIVATE).contains("vivoQuickAppOriginal")) getPreferences(MODE_PRIVATE).edit().putBoolean("vivoQuickAppOriginal", true).commit();
        ShizukuShell.run(this, "settings --user 0 put secure switch_quick_app_service 0");
        String verify = commandOutput(ShizukuShell.run(this, "settings --user 0 get secure switch_quick_app_service")).trim();
        if (verify.equals("0")) { ShizukuShell.run(this, "am force-stop --user 0 com.vivo.hybrid"); return "vivo 快应用服务已关闭并回读确认；原值已备份。"; }
        return "vivo 快应用服务关闭未确认；原值备份已保留。回读：" + verify;
    }
    private String restoreVivoQuickApp() {
        if (!getPreferences(MODE_PRIVATE).contains("vivoQuickAppOriginal")) return "没有 vivo 快应用开关原值备份，未写默认值。";
        boolean original = getPreferences(MODE_PRIVATE).getBoolean("vivoQuickAppOriginal", true);
        ShizukuShell.run(this, "settings --user 0 put secure switch_quick_app_service " + (original ? "1" : "0"));
        String verify = commandOutput(ShizukuShell.run(this, "settings --user 0 get secure switch_quick_app_service")).trim();
        if (verify.equals(original ? "1" : "0")) { getPreferences(MODE_PRIVATE).edit().remove("vivoQuickAppOriginal").remove("vivoQuickAppRestorePending").commit(); return "vivo 快应用服务开关已恢复原值并确认。"; }
        return "vivo 快应用服务开关恢复未确认，原值记录仍保留；回读：" + verify;
    }
    private static final class Channel {
        final String pkg, id, name; final int importance;
        Channel(String p, String i, String n, int x) { pkg=p; id=i; name=n; importance=x; }
        String key() { return pkg + "\n" + id; }
    }
    private void scanChannels() {
        status.setText("正在读取通知渠道（只读）…");
        worker.execute(() -> {
            String dump = ShizukuShell.run(this, CHANNEL_DUMP);
            LinkedHashMap<String, Channel> found = parseCandidates(dump);
            boolean incomplete = !dump.startsWith("exit=0") || dump.contains("[output truncated]");
            if (incomplete) found.clear();
            runOnUiThread(() -> {
                channelItems.clear(); channelBoxes.clear(); channelList.removeAllViews();
                for (Channel c : found.values()) {
                    channelItems.put(c.key(), c);
                    CheckBox cb = new CheckBox(this); cb.setText(c.name + "\n" + c.pkg + "  ·  " + c.id); channelList.addView(cb); channelBoxes.put(c.key(), cb);
                }
                status.setText(!dump.startsWith("exit=0") ? "读取通知渠道失败：" + dump : dump.contains("[output truncated]") ? "通知记录超出安全读取上限，结果不完整；已取消列表，请缩小范围后重试。" : found.isEmpty() ? "没有找到当前开启且名称匹配营销关键词的渠道。" : "找到 " + found.size() + " 个候选。按上游策略排除了服务通知、系统锁定项和已关闭渠道；请逐条核对。每次只处理一个渠道。");
            });
        });
    }
    private LinkedHashMap<String, Channel> parseCandidates(String dump) {
        LinkedHashMap<String, Channel> out = new LinkedHashMap<>(); String pkg = null; boolean appOff = false, fixed = false;
        for (String line : dump.split("\\r?\\n")) {
            Matcher a = APP_LINE.matcher(line);
            if (a.find()) {
                String uid = a.group(2); if (uid != null && Integer.parseInt(uid) >= 100000) { pkg = null; continue; }
                pkg = a.group(1); appOff = line.contains("importance=NONE"); fixed = line.contains("fixedImportance=true"); continue;
            }
            if (pkg == null || appOff) continue;
            Matcher m = CHANNEL_LINE.matcher(line); if (!m.find()) continue;
            String id=m.group(1), name=m.group(2).trim(); int importance;
            try { importance=Integer.parseInt(m.group(3)); } catch (NumberFormatException e) { continue; }
            if (importance <= 0 || "true".equals(m.group(4)) || id.endsWith("...") || id.endsWith("…")) continue;
            if (fixed && line.contains("mBlockableSystem=false")) continue;
            if (!MARKETING.matcher(name + " " + id).find() || SERVICE_CHANNEL.matcher(name).find()) continue;
            String protectedText = (name + id).replaceAll("[\\s\\u200b\\ufeff]+", "");
            if (Pattern.compile("找[机機]位|[机機]位推[荐薦]|推[荐薦][机機]位").matcher(protectedText).find()) continue;
            Channel c = new Channel(pkg,id,name,importance); out.put(c.key(),c);
        }
        return out;
    }
    private void confirmChannel(boolean enable) {
        Map<String,CheckBox> source = enable ? restoreChannelBoxes : channelBoxes;
        Channel selected = null; int count = 0;
        for (Map.Entry<String,CheckBox> entry : source.entrySet()) if (entry.getValue().isChecked()) {
            count++; selected = enable ? decodeChannel(entry.getKey()) : channelItems.get(entry.getKey());
        }
        if (count != 1 || selected == null) { status.setText("每次请选择且只选择一个渠道。"); return; }
        if (!ChannelAccessibilityService.enabled()) {
            status.setText("请先在系统无障碍设置中手动开启本应用的“通知渠道辅助操作”，再返回扫描。服务只在确认的渠道页切换明确的通知开关。");
            return;
        }
        final Channel target = selected;
        new AlertDialog.Builder(this).setTitle(enable ? "恢复渠道" : "关闭营销渠道")
            .setMessage((enable ? "恢复" : "关闭") + "“" + target.name + "”\n应用：" + target.pkg + "\n渠道 ID：" + target.id
                + (enable ? "" : "\n\n只处理此渠道，不会关闭该应用的全部通知。若页面标题或开关无法明确匹配，操作会跳过。"))
            .setNegativeButton("取消", null).setPositiveButton(enable ? "恢复" : "关闭", (d,w) -> operateChannel(target, enable)).show();
    }
    private void operateChannel(Channel c, boolean enable) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { status.setText("需要运行中的 Shizuku 和已授予的权限。"); return; }
        status.setText("正在确认渠道当前状态…");
        worker.execute(() -> {
            Integer before = readChannelImportance(c);
            if (before == null) { runOnUiThread(() -> status.setText("无法从系统记录中唯一找到该应用的渠道，未操作。")); return; }
            if ((!enable && before <= 0) || (enable && before > 0)) {
                if (enable) removeSavedChannel(c);
                runOnUiThread(() -> { status.setText(enable ? "渠道已是开启状态；无需改动。" : "渠道已是关闭状态；无需改动。未覆盖原来的关闭状态。"); refreshChannelRestoreList(); }); return;
            }
            File backup = createSnapshot(c);
            if (backup == null) { runOnUiThread(() -> status.setText("改动前快照未能保存，未打开渠道设置页。请检查 Shizuku 授权和设备空间后重试。")); return; }
            runOnUiThread(() -> { status.setText("已保存本地快照：" + backup.getName()); startChannelSettings(c, enable); });
        });
    }

    /** Capture relevant device state locally before any write. This is a review record, while the
     * app's separate change ledger remains the authoritative list of values it can restore. */
    private File createSnapshot(Channel channel) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null;
        StringBuilder out = new StringBuilder();
        out.append("Android Ad Cleaner 本地改动前快照\n时间：")
            .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()))
            .append("\n设备：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            .append("\nAndroid API：").append(android.os.Build.VERSION.SDK_INT).append('\n');
        String stay = commandOutput(ShizukuShell.run(this, "settings get global stay_on_while_plugged_in")).trim();
        String brand = commandOutput(ShizukuShell.run(this, "getprop ro.product.brand")).trim();
        out.append("插电常亮原值：").append(stay).append("\n品牌：").append(brand).append('\n');
        for (String pkg : CANDIDATES) {
            String path = ShizukuShell.run(this, "pm path " + pkg);
            if (!path.startsWith("exit=0") || !path.contains("package:")) continue;
            String state = ShizukuShell.run(this, "dumpsys package " + pkg + " | grep -m1 -o 'suspended=[a-z]*'");
            out.append("包：").append(pkg).append("；").append(commandOutput(state).trim()).append('\n');
        }
        String vivo = commandOutput(ShizukuShell.run(this, "settings --user 0 get secure switch_quick_app_service")).trim();
        if (vivo.equals("0") || vivo.equals("1")) out.append("vivo 快应用服务原值：").append(vivo).append('\n');
        String msa = commandOutput(ShizukuShell.run(this, "cmd connectivity get-package-networking-enabled com.miui.systemAdSolution")).trim();
        if (!msa.isEmpty() && !msa.toLowerCase(Locale.ROOT).contains("unknown command")) out.append("msa 网络许可原值：").append(msa).append('\n');
        if (channel != null) {
            Integer importance = readChannelImportance(channel);
            if (importance == null) return null;
            out.append("通知渠道：").append(channel.pkg).append(" / ").append(channel.name)
                .append(" / ID=").append(channel.id).append(" / importance=").append(importance).append('\n');
        }
        File dir = new File(getFilesDir(), "snapshots");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String name = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()) + ".txt";
        File file = new File(dir, name);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
            return file;
        } catch (IOException e) { return null; }
    }

    private void showSnapshot() {
        status.setText("正在生成设备当前状态快照…");
        worker.execute(() -> {
            File file = createSnapshot(null);
            runOnUiThread(() -> {
                if (file == null) { status.setText("快照生成失败；请确认 Shizuku 已连接并授权。"); return; }
                try {
                    byte[] bytes;
                    try (FileInputStream stream = new FileInputStream(file)) {
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[4096]; int count;
                        while ((count = stream.read(chunk)) != -1) buffer.write(chunk, 0, count);
                        bytes = buffer.toByteArray();
                    }
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    new AlertDialog.Builder(this).setTitle("本地状态快照 · " + file.getName()).setMessage(text)
                        .setPositiveButton("复制报告", (d,w) -> {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("设备状态快照", text));
                            status.setText("快照已保存在应用私有目录，并复制到剪贴板。");
                        }).setNegativeButton("关闭", null).show();
                } catch (IOException e) { status.setText("快照已保存，但无法读取报告：" + e.getMessage()); }
            });
        });
    }
    private String channelQuery(Channel c) {
        return "dumpsys notification --noredact | awk -v pkg=" + shellQuote(c.pkg) + " -v cid=" + shellQuote(c.id)
            + " '/AppSettings:/ {active=($2 == pkg)} active && index($0, \"mId=\" sprintf(\"%c\",39) cid sprintf(\"%c\",39)) {print; exit}'";
    }
    private Integer readChannelImportance(Channel c) {
        String state = ShizukuShell.run(this, channelQuery(c)); Matcher m = IMP_LINE.matcher(state);
        if (!state.startsWith("exit=0") || !m.find()) return null;
        try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return null; }
    }
    private void startChannelSettings(Channel c, boolean enable) {
        if (!ChannelAccessibilityService.enabled()) { status.setText("无障碍辅助服务已断开；没有操作通知渠道。"); return; }
        status.setText("正在打开并核对渠道设置页…"); ChannelAccessibilityService.begin(c.name, enable);
        refreshChannelsOnResume = true;
        Intent i = new Intent("android.settings.CHANNEL_NOTIFICATION_SETTINGS");
        i.putExtra("android.provider.extra.APP_PACKAGE", c.pkg); i.putExtra("android.provider.extra.CHANNEL_ID", c.id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) { ChannelAccessibilityService.clear(); status.setText("系统无法打开该渠道设置页：" + e.getClass().getSimpleName()); return; }
        worker.execute(() -> {
            long end = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < end && ChannelAccessibilityService.result() == 0) {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            int result = ChannelAccessibilityService.result(); String diagnostic = ChannelAccessibilityService.diagnostic(); ChannelAccessibilityService.clear();
            Integer after = readChannelImportance(c); boolean changed = after != null && (after > 0) == enable;
            boolean success = result == 1 && changed;
            if (success && !enable) saveChannel(c);
            if (success && enable) removeSavedChannel(c);
            runOnUiThread(() -> { status.setText(success ? (enable ? "已恢复并回读确认：" : "已关闭并回读确认：") + c.name : "未确认渠道状态，未记录成功。设置页已保留，请手动检查。\n辅助服务结果=" + result + (diagnostic.isEmpty() ? "" : "\n原因：" + diagnostic)); refreshChannelRestoreList(); });
        });
    }
    private String shellQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private String disableMsaNetwork() {
        String help = ShizukuShell.run(this, "cmd connectivity help");
        String[] required = {"set-package-networking-enabled", "get-package-networking-enabled", "set-chain3-enabled", "get-chain3-enabled"};
        for (String command : required) if (!help.contains(command)) return "系统不支持 msa 网络控制命令；只确认了挂起，仍可能出现广告。";
        Boolean original = msaNetworkState();
        if (original == null) return "读不到 msa 原网络状态；为保留可恢复性，未更改网络规则。";
        getPreferences(MODE_PRIVATE).edit().putBoolean("msaNetworkOriginal", original).commit();
        String deny = ShizukuShell.run(this, "cmd connectivity set-package-networking-enabled false com.miui.systemAdSolution");
        String firewall = ShizukuShell.run(this, "cmd connectivity set-chain3-enabled true");
        Boolean current = msaNetworkState();
        String chain = commandOutput(ShizukuShell.run(this, "cmd connectivity get-chain3-enabled")).toLowerCase(Locale.ROOT);
        if (current != null && !current && (chain.contains("enabled") || chain.equals("true"))) return "已按系统接口断网并回读确认";
        return "网络变更未确认；保留了原状态备份。" + commandOutput(deny + "\n" + firewall);
    }
    private Boolean msaNetworkState() {
        String value = commandOutput(ShizukuShell.run(this, "cmd connectivity get-package-networking-enabled com.miui.systemAdSolution")).trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.endsWith(":allow")) return true;
        if (value.equals("false") || value.endsWith(":deny")) return false;
        return null;
    }
    private String restoreMsaNetwork() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (!prefs.contains("msaNetworkOriginal")) return "";
        boolean original = prefs.getBoolean("msaNetworkOriginal", true);
        String out = ShizukuShell.run(this, "cmd connectivity set-package-networking-enabled " + original + " com.miui.systemAdSolution");
        Boolean current = msaNetworkState();
        if (current != null && current == original) { prefs.edit().remove("msaNetworkOriginal").commit(); return ""; }
        return commandOutput(out);
    }
    private String commandOutput(String result) { int n=result.indexOf('\n'); return (n<0 ? result : result.substring(n+1)).trim(); }
    private String encodeChannel(Channel c) { String raw=c.pkg+"\n"+c.id+"\n"+c.name+"\n"+c.importance; return Base64.encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),Base64.NO_WRAP); }
    private Channel decodeChannel(String encoded) {
        try { String[] p=new String(Base64.decode(encoded,Base64.NO_WRAP),java.nio.charset.StandardCharsets.UTF_8).split("\\n",4); return new Channel(p[0],p[1],p[2],Integer.parseInt(p[3])); }
        catch (Exception e) { return null; }
    }
    private Set<String> savedChannels() { return new HashSet<>(getPreferences(MODE_PRIVATE).getStringSet("channels",Collections.emptySet())); }
    private void saveChannel(Channel c) { Set<String>s=savedChannels(); s.add(encodeChannel(c)); getPreferences(MODE_PRIVATE).edit().putStringSet("channels",s).apply(); }
    private void removeSavedChannel(Channel c) { Set<String>s=savedChannels(); s.remove(encodeChannel(c)); getPreferences(MODE_PRIVATE).edit().putStringSet("channels",s).apply(); }
    private void refreshChannelRestoreList() {
        if (restoreChannelList == null) return; restoreChannelBoxes.clear(); restoreChannelList.removeAllViews();
        for (String encoded : savedChannels()) { Channel c=decodeChannel(encoded); if (c==null) continue; CheckBox cb=new CheckBox(this); cb.setText(c.name+"\n"+c.pkg+"  ·  "+c.id); restoreChannelList.addView(cb); restoreChannelBoxes.put(encoded,cb); }
    }
    @Override protected void onDestroy() { Shizuku.removeRequestPermissionResultListener(permissionListener); worker.shutdownNow(); super.onDestroy(); }
}
