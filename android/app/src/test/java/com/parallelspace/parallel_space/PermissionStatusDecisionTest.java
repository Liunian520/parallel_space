package com.parallelspace.parallel_space;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * 能力状态决策与跳转名单。不需要 Android SDK。
 *
 * <p>在仓库根目录：
 * javac -d /tmp/ps-status android/app/src/main/java/com/parallelspace/parallel_space/PermissionStatusDecision.java android/app/src/main/java/com/parallelspace/parallel_space/PermissionTargets.java android/app/src/test/java/com/parallelspace/parallel_space/PermissionStatusDecisionTest.java
 * java -cp /tmp/ps-status com.parallelspace.parallel_space.PermissionStatusDecisionTest
 */
public final class PermissionStatusDecisionTest {
    private static int failed;

    public static void main(String[] args) throws Exception {
        overlayAgreesConflictsAndDoesNotTreatFailureAsDenied();
        accessibilityRowsStayIndependent();
        notificationBoundaries();
        backgroundRestrictionDoesNotMeanAutostart();
        shortcutScopes();
        manualRowsNeverGranted();
        hostCameraDoesNotMakeCloneUsable();
        canOpenDoesNotChangeStatus();
        overlayTargetsExcludeUnrelatedPages();
        batteryNavigationDoesNotRequestTheGrant();
        readPathDoesNotWriteOrRequest();
        keepAlivePathsRemain();
        if (failed > 0) {
            System.err.println(failed + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("PermissionStatusDecisionTest OK");
    }

    private static void overlayAgreesConflictsAndDoesNotTreatFailureAsDenied() {
        PermissionStatusDecision.Inputs in = base();
        in.overlaySettings = true;
        in.overlayAppOps = true;
        PermissionStatusDecision.Row row = find(in, "overlay");
        check("granted".equals(row.status), "both true is granted");
        check("detectable".equals(row.group), "overlay stays detectable");

        in.overlaySettings = false;
        in.overlayAppOps = false;
        check("denied".equals(find(in, "overlay").status), "both false is denied");

        in.overlaySettings = true;
        in.overlayAppOps = false;
        PermissionStatusDecision.Row conflict = find(in, "overlay");
        check("conflict".equals(conflict.status), "disagreement is conflict");
        check(!"granted".equals(conflict.status), "conflict is not granted");
        check(conflict.detail.contains("不一致"), "conflict explains both sources");
        check(conflict.canOpen, "overlay can still open settings");

        in.overlaySettings = null;
        in.overlayAppOps = false;
        check("unknown".equals(find(in, "overlay").status), "one failed read is unknown, not denied");
    }

    private static void accessibilityRowsStayIndependent() {
        PermissionStatusDecision.Inputs in = base();
        in.a11ySecure = true;
        in.a11yManager = true;
        in.a11yConnected = false;
        PermissionStatusDecision.Row listed = find(in, "a11y-listed");
        PermissionStatusDecision.Row live = find(in, "a11y-live");
        check("granted".equals(listed.status) && "已允许".equals(listed.badge), "listed granted");
        check("denied".equals(live.status) && "未连接".equals(live.badge), "live disconnected");
        check(!"granted".equals(live.status), "empty instance is not granted");

        in.a11yConnected = true;
        check("已连接".equals(find(in, "a11y-live").badge), "connected badge");

        in.a11ySecure = true;
        in.a11yManager = false;
        check("conflict".equals(find(in, "a11y-listed").status), "list sources disagree");

        in.a11ySecure = null;
        in.a11yManager = false;
        check("unknown".equals(find(in, "a11y-listed").status), "list failure is unknown");

        in.a11yConnected = null;
        check("unknown".equals(find(in, "a11y-live").status), "connection failure is not 未连接");

        in.sdk = 33;
        in.a11ySecure = false;
        in.a11yManager = false;
        in.a11yConnected = false;
        in.a11yRestricted = true;
        check(find(in, "a11y-listed").detail.contains("受限制的设置"), "API 33 restricted hint");
        in.sdk = 32;
        check(!find(in, "a11y-listed").detail.contains("受限制的设置"), "API 32 has no restricted hint");
    }

    private static void notificationBoundaries() {
        PermissionStatusDecision.Inputs in = base();
        in.sdk = 32;
        in.postNotifications = false;
        PermissionStatusDecision.Row runtime = find(in, "noti-runtime");
        check("na".equals(runtime.status), "API 32 runtime permission is na");
        check(!runtime.canOpen, "na runtime permission has no button");
        check(runtime.detail.contains("没有单独的通知运行时权限"), "na copy");

        in.sdk = 33;
        in.postNotifications = false;
        check("denied".equals(find(in, "noti-runtime").status), "API 33 denied");
        in.postNotifications = null;
        check("unknown".equals(find(in, "noti-runtime").status), "API 33 failure is unknown");

        in.sdk = 24;
        in.notificationsEnabled = null;
        check("unknown".equals(find(in, "noti-master").status), "master switch failure is unknown");
        in.notificationsEnabled = false;
        check("denied".equals(find(in, "noti-master").status), "master off is denied");
        check(find(in, "noti-master").canOpen, "API 24 master opens app details");

        in.sdk = 23;
        check("na".equals(find(in, "noti-master").status), "API 23 master is na");

        in.sdk = 25;
        check("na".equals(find(in, "noti-toolbox").status), "API 25 channel is na");
        check(!find(in, "noti-toolbox").canOpen, "missing channel api cannot open");

        in.sdk = 26;
        in.toolboxChannel = "missing";
        PermissionStatusDecision.Row missing = find(in, "noti-toolbox");
        check("unknown".equals(missing.status), "missing channel is unknown");
        check(missing.detail.contains("不表示被关掉"), "missing channel copy");
        in.toolboxChannel = "error";
        check("unknown".equals(find(in, "noti-toolbox").status), "channel error is unknown");
        in.toolboxChannel = "blocked";
        check("denied".equals(find(in, "noti-toolbox").status), "IMPORTANCE_NONE is denied");
        check(find(in, "noti-toolbox").detail.contains("已被关闭"), "blocked copy");
        in.mockChannel = "open";
        check("granted".equals(find(in, "noti-mock").status), "open channel is granted for that channel only");

        List<PermissionStatusDecision.Row> rows = PermissionStatusDecision.rows(in);
        int n = 0;
        for (PermissionStatusDecision.Row row : rows) {
            if (row.id.startsWith("noti-")) n++;
        }
        check(n == 4, "four notification rows, got " + n);
    }

    private static void backgroundRestrictionDoesNotMeanAutostart() {
        PermissionStatusDecision.Inputs in = base();
        in.sdk = 27;
        in.backgroundRestricted = false;
        PermissionStatusDecision.Row old = find(in, "background");
        check("na".equals(old.status), "API 27 background api is na, not granted");
        check(old.detail.contains("没有后台限制这项读取接口"), "API 27 copy");

        in.sdk = 28;
        in.backgroundRestricted = null;
        check("unknown".equals(find(in, "background").status), "API 28 failure is unknown");
        in.backgroundRestricted = false;
        PermissionStatusDecision.Row clear = find(in, "background");
        check("granted".equals(clear.status), "false means the boolean is clear");
        check(clear.detail.contains("不是自启动已经打开"), "clear text is not autostart");
        in.backgroundRestricted = true;
        check("denied".equals(find(in, "background").status), "restricted is denied");

        for (String family : families()) {
            in.family = family;
            in.backgroundRestricted = false;
            in.ignoringBattery = true;
            PermissionStatusDecision.Row auto = find(in, "autostart");
            check(!"granted".equals(auto.status), family + " autostart must not be granted");
            if ("STOCK".equals(family)) {
                check("na".equals(auto.status), "stock autostart is na");
                check("detectable".equals(auto.group), "stock na stays in detectable");
                check(!auto.canOpen, "stock autostart has no page");
            } else {
                check("manual".equals(auto.status), family + " autostart is manual");
                check(auto.detail.contains("读不到"), family + " says unreadable");
            }
        }
    }

    private static void shortcutScopes() {
        PermissionStatusDecision.Inputs in = base();
        in.family = "XIAOMI";
        in.xiaomiShortcut = true;
        PermissionStatusDecision.Row ok = find(in, "shortcut");
        check("granted".equals(ok.status) && "detectable".equals(ok.group), "xiaomi allowed");
        in.xiaomiShortcut = false;
        check("denied".equals(find(in, "shortcut").status), "xiaomi denied");
        in.xiaomiShortcut = null;
        check("unknown".equals(find(in, "shortcut").status), "xiaomi read failure is unknown");

        for (String family : new String[] {"HUAWEI", "HONOR", "OPPO", "VIVO", "MEIZU"}) {
            in.family = family;
            in.xiaomiShortcut = true;
            PermissionStatusDecision.Row row = find(in, "shortcut");
            check("manual".equals(row.status), family + " shortcut is manual");
            check("manual".equals(row.group), family + " shortcut group");
            check(!"granted".equals(row.status), family + " not granted");
        }
        in.family = "SAMSUNG";
        PermissionStatusDecision.Row samsung = find(in, "shortcut");
        check("manual".equals(samsung.status), "samsung shortcut is manual");
        check(samsung.canOpen, "samsung opens app details");
        check(samsung.detail.contains("主屏幕快捷方式"), "samsung hint");

        in.family = "STOCK";
        PermissionStatusDecision.Row stock = find(in, "shortcut");
        check("na".equals(stock.status), "stock shortcut is na");
        check("detectable".equals(stock.group), "stock shortcut group");
        check(!stock.canOpen, "stock shortcut has no dedicated page");
        check(stock.detail.contains("没有这项常驻开关"), "stock copy");
    }

    private static void manualRowsNeverGranted() {
        for (String family : families()) {
            for (Boolean restricted : new Boolean[] {true, false, null}) {
                for (Boolean battery : new Boolean[] {true, false, null}) {
                    PermissionStatusDecision.Inputs in = base();
                    in.family = family;
                    in.backgroundRestricted = restricted;
                    in.ignoringBattery = battery;
                    for (String id : new String[] {"autostart", "popup", "oem-battery"}) {
                        String status = find(in, id).status;
                        check(!"granted".equals(status), family + " " + id + " became granted");
                    }
                }
            }
        }
        PermissionStatusDecision.Inputs stock = base();
        stock.family = "STOCK";
        check("na".equals(find(stock, "popup").status), "stock popup is na");
        check("na".equals(find(stock, "oem-battery").status), "stock oem battery is na");
        check("manual".equals(find(base(), "oem-battery").status), "xiaomi oem battery is manual");
        check(find(base(), "oem-battery").detail.contains("无限制"), "xiaomi unlimited copy");
    }

    private static void hostCameraDoesNotMakeCloneUsable() {
        PermissionStatusDecision.Inputs in = base();
        in.hostCamera = true;
        in.hostMic = true;
        check("granted".equals(find(in, "host-camera").status), "host camera granted");
        check(find(in, "host-camera").detail.contains("不代表虚拟分身"), "host camera caveat");
        check(!find(in, "host-camera").canOpen, "status page does not request camera");
        PermissionStatusDecision.Row clone = find(in, "clone-cam");
        check("unknown".equals(clone.status), "clone stays unknown");
        check("clone".equals(clone.group), "clone group");
        check(!clone.canOpen, "clone row is not a settings jump");
        check("camera-diag".equals(clone.openId), "diag action id");
        in.hostCamera = null;
        check("unknown".equals(find(in, "host-camera").status), "camera read failure is unknown");
        check("unknown".equals(find(in, "clone-cam").status), "clone still unknown");
    }

    private static void canOpenDoesNotChangeStatus() {
        PermissionStatusDecision.Inputs in = base();
        in.family = "HUAWEI";
        PermissionStatusDecision.Row row = find(in, "autostart");
        check(row.canOpen && "manual".equals(row.status), "openable manual stays manual");
    }

    private static void overlayTargetsExcludeUnrelatedPages() {
        String[] forbidden = {
            "BatteryActivity",
            "SmartManagerDashBoardActivity",
            "ChainLaunchAppListActivity",
            "NotificationManagmentActivity",
            "SmartBGActivity",
        };
        for (String family : families()) {
            List<String> labels = PermissionTargets.overlayLabels(family);
            String joined = String.join("\n", labels);
            for (String bad : forbidden) {
                check(!joined.contains(bad), family + " overlay contains " + bad);
            }
            check("APPLICATION_DETAILS".equals(labels.get(labels.size() - 2)), family + " details");
            check("SETTINGS".equals(labels.get(labels.size() - 1)), family + " settings");
            String opened = PermissionTargets.firstOpen(labels, new HashSet<String>(labels.subList(0, labels.size() - 2)));
            check("details".equals(opened), family + " falls through to details");
            HashSet<String> allButSettings = new HashSet<String>(labels.subList(0, labels.size() - 1));
            check("settings".equals(PermissionTargets.firstOpen(labels, allButSettings)), family + " then settings");
            check(!"granted".equals(opened), "open result is not granted");
        }
        List<String> white = PermissionTargets.batteryWhitelistLabels();
        check(white.get(0).equals("ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"), "whitelist starts at the list");
        check(!white.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"), "whitelist does not request");
        check(!white.contains("OEM_BATTERY"), "whitelist is not the oem page");
        List<String> oem = PermissionTargets.oemBatteryLabels();
        check("OEM_BATTERY".equals(oem.get(0)), "oem battery starts at oem intents");
        check(!oem.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"), "oem battery does not request");
    }

    private static void batteryNavigationDoesNotRequestTheGrant() throws Exception {
        String oem = Files.readString(Path.of("android/app/src/main/kotlin/com/parallelspace/parallel_space/OemAdapter.kt"));
        String white = functionBody(oem, "fun openBatteryWhitelist");
        String oemFn = functionBody(oem, "fun openOemBattery");
        String request = functionBody(oem, "fun requestIgnoreBattery");
        check(!white.isEmpty(), "openBatteryWhitelist exists");
        check(white.contains("ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"), "whitelist action");
        check(!white.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"), "whitelist has no request");
        check(!white.contains("requestIgnoreBattery"), "whitelist does not call the dialog helper");
        check(oemFn.contains("batteryOemIntents"), "oem battery uses oem intents");
        check(!oemFn.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"), "oem battery has no request");
        check(request.contains("ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"), "settings entry still requests");
        String overlay = functionBody(oem, "fun overlayIntents");
        for (String bad : new String[] {
            "BatteryActivity", "SmartManagerDashBoardActivity", "ChainLaunchAppListActivity",
            "NotificationManagmentActivity", "SmartBGActivity"
        }) {
            check(!overlay.contains(bad), "overlayIntents contains " + bad);
        }
        check(overlay.contains("overlayOem"), "overlay intents come from the shared list");
    }

    private static void readPathDoesNotWriteOrRequest() throws Exception {
        String src = Files.readString(Path.of(
                "android/app/src/main/kotlin/com/parallelspace/parallel_space/PermissionStatus.kt"));
        for (String banned : new String[] {
            "A11yKeeper", "accessibilityOn", "accessibilityLikelyKilled", "requestPermissions",
            "startActivity", "canDrawOverlay", "openCamera", "cameraSelfTest", "REQUEST_IGNORE"
        }) {
            check(!calls(src, banned), "PermissionStatus.kt contains " + banned);
        }
        check(src.contains("canDrawOverlays"), "reads settings overlay");
        check(src.contains("areNotificationsEnabled"), "reads master switch");
        check(src.contains("getNotificationChannel"), "reads channels");
        check(src.contains("isIgnoringBatteryOptimizations"), "reads whitelist");
        check(src.contains("isBackgroundRestricted"), "reads background restriction");
        check(src.contains("checkSelfPermission"), "reads host permissions");
        check(src.contains("toolbox_overlay") && src.contains("mock_location"), "two channel ids");
        check(!src.contains("toolbox_perm"), "permission channel stays off this page");
    }

    private static void keepAlivePathsRemain() throws Exception {
        String main = Files.readString(Path.of(
                "android/app/src/main/kotlin/com/parallelspace/parallel_space/MainActivity.kt"));
        check(main.contains("OemAdapter.accessibilityOn"), "resume still self-heals via accessibilityOn");
        check(main.contains("requestPermissions"), "camera self-test and clone launch can still request");
        String settings = Files.readString(Path.of("lib/screens/settings_screen.dart"));
        check(settings.contains("requestIgnoreBattery"), "settings battery tile remains");
        String shell = Files.readString(Path.of("lib/screens/root_shell.dart"));
        check(shell.contains("CloneStore") && shell.contains("setToolboxEnabled"), "RootShell behavior stays");
        String compat = Files.readString(Path.of("lib/screens/compat_screen.dart"));
        for (String banned : new String[] {
            "cameraSelfTest", "setToolboxEnabled", "CloneStore", "requestIgnoreBattery", "A11yKeeper"
        }) {
            check(!compat.contains(banned), "compat screen contains " + banned);
        }
        check(compat.contains("permissionStatus"), "page uses the read-only channel");
    }

    private static PermissionStatusDecision.Inputs base() {
        PermissionStatusDecision.Inputs in = new PermissionStatusDecision.Inputs();
        in.sdk = 35;
        in.family = "XIAOMI";
        in.rom = "hyperos";
        in.overlaySettings = true;
        in.overlayAppOps = true;
        in.a11ySecure = true;
        in.a11yManager = true;
        in.a11yConnected = true;
        in.a11yRestricted = false;
        in.postNotifications = true;
        in.notificationsEnabled = true;
        in.toolboxChannel = "open";
        in.mockChannel = "open";
        in.ignoringBattery = false;
        in.backgroundRestricted = false;
        in.hostCamera = false;
        in.hostMic = false;
        in.xiaomiShortcut = false;
        return in;
    }

    private static String[] families() {
        return new String[] {"XIAOMI", "HUAWEI", "HONOR", "OPPO", "VIVO", "SAMSUNG", "MEIZU", "STOCK"};
    }

    private static PermissionStatusDecision.Row find(PermissionStatusDecision.Inputs in, String id) {
        return find(PermissionStatusDecision.rows(in), id);
    }

    private static PermissionStatusDecision.Row find(List<PermissionStatusDecision.Row> rows, String id) {
        for (PermissionStatusDecision.Row row : rows) {
            if (id.equals(row.id)) return row;
        }
        check(false, "missing row " + id);
        return rows.get(0);
    }

    private static String functionBody(String src, String signature) {
        int i = src.indexOf(signature);
        if (i < 0) return "";
        int next = src.indexOf("\n    fun ", i + signature.length());
        if (next < 0) next = src.length();
        return src.substring(i, next);
    }

    /** 名字后面还有字母时不算，避免 canDrawOverlays 被当成 canDrawOverlay。 */
    private static boolean calls(String src, String name) {
        int from = 0;
        while (true) {
            int i = src.indexOf(name, from);
            if (i < 0) return false;
            int end = i + name.length();
            char after = end < src.length() ? src.charAt(end) : ' ';
            if (!Character.isLetterOrDigit(after) && after != '_') return true;
            from = end;
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            failed++;
            System.err.println("FAIL " + message);
        }
    }

    static {
        // Keep Arrays referenced so a future table stays import-clean if trimmed.
        if (Arrays.asList("ok").isEmpty()) failed++;
    }
}
