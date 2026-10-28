package com.rddmwmddr.adcleaner;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Fail-closed channel-page switcher. It only acts after an explicit app-side confirmation. */
public final class ChannelAccessibilityService extends AccessibilityService {
    private static volatile ChannelAccessibilityService instance;
    private static volatile String expectedName;
    private static volatile boolean desiredEnabled;
    private static volatile int result; // 0 pending, 1 done, -1 ambiguous/failed
    private static volatile boolean clicked;
    private static volatile String diagnostic = "";

    public static boolean enabled() { return instance != null; }
    public static synchronized void begin(String name, boolean enable) {
        expectedName = name; desiredEnabled = enable; result = 0; clicked = false; diagnostic = "";
    }
    public static int result() { return result; }
    public static String diagnostic() { return diagnostic; }
    public static synchronized void clear() { expectedName = null; result = 0; clicked = false; diagnostic = ""; }

    @Override public void onServiceConnected() { instance = this; }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        final String expected = expectedName;
        if (expected == null || result != 0) return;
        if (((KeyguardManager) getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) { diagnostic = "设备处于锁屏状态"; result = -1; return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (!validSettingsHost(String.valueOf(root.getPackageName()))) { root.recycle(); return; }
        boolean headerMatches = false;
        ArrayList<AccessibilityNodeInfo> all = new ArrayList<>(); collect(root, all);
        try {
            for (AccessibilityNodeInfo n : all) {
                CharSequence text = n.getText();
                CharSequence description = n.getContentDescription();
                if ((text != null && expected.contentEquals(text.toString().trim()))
                        || (description != null && expected.contentEquals(description.toString().trim()))) { headerMatches = true; break; }
            }
            if (!headerMatches) { diagnostic = summarizePage(root, all, "等待目标渠道标题"); return; }
            ArrayList<AccessibilityNodeInfo> switches = new ArrayList<>();
            for (AccessibilityNodeInfo n : all) {
                if (!n.isCheckable()) continue;
                if (labeledAllowSwitch(n) || isSwitchControl(n)) switches.add(n);
            }
            if (switches.size() != 1) { diagnostic = summarizePage(root, all, "符合通知开关条件的控件数量=" + switches.size()); return; }
            AccessibilityNodeInfo sw = switches.get(0);
            if (sw.isChecked() == desiredEnabled) { result = 1; closeSettingsPage(); return; }
            if (clicked) return;
            clicked = true;
            boolean acted = sw.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo clickable = sw;
            for (int depth = 0; !acted && clickable != null && depth < 2; depth++) {
                clickable = clickable.getParent();
                if (clickable != null && clickable.isClickable()) acted = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            if (!acted) { diagnostic = "系统拒绝了开关及其所在行的点击动作"; result = -1; return; }
            // Wait for a subsequent accessibility event and verify the same channel's explicit switch state.
        } finally {
            for (AccessibilityNodeInfo node : all) node.recycle();
        }
    }
    private boolean labeledAllowSwitch(AccessibilityNodeInfo node) {
        if (allowLabel(node)) return true;
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return false;
        ArrayList<AccessibilityNodeInfo> descendants = new ArrayList<>(); collect(parent, descendants);
        try {
            boolean label = false; int checks = 0;
            for (AccessibilityNodeInfo child : descendants) {
                if (allowLabel(child)) label = true;
                if (child.isCheckable()) checks++;
            }
            return label && checks == 1;
        } finally {
            for (AccessibilityNodeInfo child : descendants) child.recycle();
        }
    }
    private boolean allowLabel(AccessibilityNodeInfo node) {
        CharSequence t = node.getText(), d = node.getContentDescription();
        return isAllow(t) || isAllow(d);
    }
    private String summarizePage(AccessibilityNodeInfo root, List<AccessibilityNodeInfo> nodes, String reason) {
        StringBuilder out = new StringBuilder(reason).append("；页面=").append(root.getPackageName()).append("；控件=");
        for (AccessibilityNodeInfo node : nodes) {
            CharSequence text = node.getText(), desc = node.getContentDescription(), cls = node.getClassName();
            boolean useful = node.isCheckable() || (text != null && !text.toString().trim().isEmpty());
            if (!useful) continue;
            String value = text != null ? text.toString().trim() : desc != null ? desc.toString().trim() : "";
            if (value.length() > 60) value = value.substring(0, 60);
            if (out.length() > 700) { out.append("…"); break; }
            out.append('[').append(cls).append(value.isEmpty() ? "" : ":" + value);
            if (node.isCheckable()) out.append(",checkable,checked=").append(node.isChecked());
            out.append(']');
        }
        return out.toString();
    }
    private boolean isAllow(CharSequence value) {
        if (value == null) return false;
        String s = value.toString().trim();
        return s.equals("允许通知") || s.equals("显示通知") || s.equals("通知") || s.equals("通知开关")
            || s.equalsIgnoreCase("Allow notifications") || s.equalsIgnoreCase("Show notifications") || s.equalsIgnoreCase("Notifications");
    }
    private boolean isSwitchControl(AccessibilityNodeInfo node) {
        CharSequence cls = node.getClassName();
        String className = cls == null ? "" : cls.toString();
        String viewId = node.getViewIdResourceName();
        String lowerClass = className.toLowerCase(java.util.Locale.ROOT);
        return lowerClass.endsWith("switch") || lowerClass.endsWith("switchcompat")
            || lowerClass.endsWith("slidingbutton") || lowerClass.endsWith("compoundbutton")
            || (viewId != null && (viewId.toLowerCase(java.util.Locale.ROOT).contains("switch_widget")
                || viewId.toLowerCase(java.util.Locale.ROOT).contains("notification_switch")));
    }
    private boolean validSettingsHost(String pkg) {
        return pkg.equals("com.android.settings") || pkg.equals("com.oplus.notificationmanager")
            || pkg.equals("com.hihonor.systemmanager") || pkg.equals("com.huawei.systemmanager")
            || pkg.equals("com.vivo.systemuiplugin") || pkg.equals("com.miui.notification")
            || pkg.equals("com.miui.notificationmanager") || pkg.equals("com.miui.securitycenter");
    }
    private void closeSettingsPage() { performGlobalAction(GLOBAL_ACTION_BACK); }
    private boolean isExpectedChannelPage() {
        String expected = expectedName;
        if (expected == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        if (!validSettingsHost(String.valueOf(root.getPackageName()))) { root.recycle(); return false; }
        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>(); collect(root,nodes);
        Rect rect = new Rect(); int height=getResources().getDisplayMetrics().heightPixels; boolean found=false;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence text=n.getText();
                if (text != null && expected.contentEquals(text.toString().trim())) {
                    n.getBoundsInScreen(rect);
                    if (rect.top>=0 && rect.top<height*.25f) { found=true; break; }
                }
            }
        } finally { for (AccessibilityNodeInfo n : nodes) n.recycle(); }
        return found;
    }
    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i=0; i<node.getChildCount(); i++) collect(node.getChild(i), out);
    }
}
