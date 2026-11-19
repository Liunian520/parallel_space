package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 悬浮窗与电池导航的目标名单。Kotlin 按这份名单生成 Intent，避免两套名字。 */
public final class PermissionTargets {
    private PermissionTargets() {}

    public static final class Target {
        public final String pkg;
        public final String cls;
        public final String action;
        public final String extraKey;

        public Target(String pkg, String cls, String action, String extraKey) {
            this.pkg = pkg;
            this.cls = cls;
            this.action = action;
            this.extraKey = extraKey;
        }

        public String label() {
            if (cls != null && !cls.isEmpty()) return cls;
            return action == null ? "" : action;
        }
    }

    public static List<Target> overlayOem(String family) {
        if ("XIAOMI".equals(family)) {
            return Arrays.asList(
                    component("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity", "extra_pkgname"),
                    component("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity", "extra_pkgname"),
                    action("miui.intent.action.APP_PERM_EDITOR", "extra_pkgname"),
                    both("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity",
                            "miui.intent.action.APP_PERM_EDITOR", "extra_pkgname"),
                    component("com.miui.securitycenter", "com.miui.permcenter.permissions.AddViewWindowActivity", "extra_pkgname"));
        }
        if ("HUAWEI".equals(family)) {
            return Arrays.asList(
                    component("com.huawei.systemmanager", "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity", null),
                    component("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.SingleAppActivity", "packageName"),
                    component("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity", null),
                    component("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.SingleAppActivity", "package_name"));
        }
        if ("HONOR".equals(family)) {
            return Arrays.asList(
                    component("com.hihonor.systemmanager", "com.hihonor.systemmanager.addviewmonitor.AddViewMonitorActivity", null),
                    component("com.hihonor.systemmanager", "com.hihonor.permissionmanager.ui.SingleAppActivity", "packageName"),
                    component("com.hihonor.systemmanager", "com.hihonor.permissionmanager.ui.MainActivity", null),
                    component("com.huawei.systemmanager", "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity", null),
                    component("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.SingleAppActivity", "packageName"));
        }
        if ("OPPO".equals(family)) {
            return Arrays.asList(
                    component("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity", null),
                    component("com.oplus.safecenter", "com.oplus.safecenter.sysfloatwindow.FloatWindowListActivity", null),
                    component("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity", null),
                    component("com.oppo.safe", "com.oppo.safe.permission.floatwindow.FloatWindowListActivity", null),
                    component("com.oplus.safecenter", "com.oplus.safecenter.permission.PermissionTopActivity", null),
                    component("com.coloros.safecenter", "com.coloros.safecenter.permission.PermissionManagerActivity", "packageName"));
        }
        if ("VIVO".equals(family)) {
            return Arrays.asList(
                    component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity", "packagename"),
                    component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.FloatWindowManager", null),
                    component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity", null),
                    component("com.iqoo.secure", "com.iqoo.secure.safeguard.SoftPermissionDetailActivity", "packagename"));
        }
        if ("MEIZU".equals(family)) {
            return Arrays.asList(
                    component("com.meizu.safe", "com.meizu.safe.security.AppSecActivity", "packageName"),
                    component("com.meizu.safe", "com.meizu.safe.permission.PermissionActivity", null));
        }
        return Collections.emptyList();
    }

    public static List<String> overlayLabels(String family) {
        List<String> labels = new ArrayList<String>();
        for (Target target : overlayOem(family)) labels.add(target.label());
        labels.add("ACTION_MANAGE_OVERLAY_PERMISSION:package");
        labels.add("ACTION_MANAGE_OVERLAY_PERMISSION");
        labels.add("APPLICATION_DETAILS");
        labels.add("SETTINGS");
        return labels;
    }

    public static List<String> batteryWhitelistLabels() {
        return Arrays.asList(
                "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
                "APPLICATION_DETAILS",
                "SETTINGS");
    }

    public static List<String> oemBatteryLabels() {
        return Arrays.asList(
                "OEM_BATTERY",
                "ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
                "APPLICATION_DETAILS",
                "SETTINGS");
    }

    /** 模拟逐个尝试。失败集合里的步骤跳过。结果只有 rom / details / settings / error。 */
    public static String firstOpen(List<String> steps, Set<String> failing) {
        for (String step : steps) {
            if (failing != null && failing.contains(step)) continue;
            if ("APPLICATION_DETAILS".equals(step)) return "details";
            if ("SETTINGS".equals(step)) return "settings";
            return "rom";
        }
        return "error";
    }

    private static Target component(String pkg, String cls, String extraKey) {
        return new Target(pkg, cls, null, extraKey);
    }

    private static Target action(String action, String extraKey) {
        return new Target(null, null, action, extraKey);
    }

    private static Target both(String pkg, String cls, String action, String extraKey) {
        return new Target(pkg, cls, action, extraKey);
    }
}
