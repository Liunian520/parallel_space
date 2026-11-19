package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力状态页的纯决策。null 表示这次读取失败，不能当成拒绝或允许。
 * 不读系统，不写设置，不申请权限。
 */
public final class PermissionStatusDecision {
    private PermissionStatusDecision() {}

    public static final class Inputs {
        public int sdk;
        public String family = "";
        public String rom = "";
        public Boolean overlaySettings;
        public Boolean overlayAppOps;
        public Boolean a11ySecure;
        public Boolean a11yManager;
        public Boolean a11yConnected;
        public Boolean a11yRestricted;
        public Boolean postNotifications;
        public Boolean notificationsEnabled;
        public String toolboxChannel;
        public String mockChannel;
        public Boolean ignoringBattery;
        public Boolean backgroundRestricted;
        public Boolean hostCamera;
        public Boolean hostMic;
        public Boolean xiaomiShortcut;
    }

    public static final class Row {
        public final String id;
        public final String group;
        public final String title;
        public final String status;
        public final String badge;
        public final String detail;
        public final boolean canOpen;
        public final String openId;

        public Row(String id, String group, String title, String status, String badge, String detail,
                   boolean canOpen, String openId) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.status = status;
            this.badge = badge;
            this.detail = detail;
            this.canOpen = canOpen;
            this.openId = openId;
        }
    }

    public static List<Row> rows(Inputs in) {
        List<Row> detectable = new ArrayList<Row>();
        List<Row> manual = new ArrayList<Row>();
        detectable.add(overlay(in));
        detectable.add(a11yListed(in));
        detectable.add(a11yLive(in));
        detectable.add(notificationRuntime(in));
        detectable.add(notificationMaster(in));
        detectable.add(channel(in, "noti-toolbox", "工具箱通知渠道", in.toolboxChannel));
        detectable.add(channel(in, "noti-mock", "虚拟定位通知渠道", in.mockChannel));
        detectable.add(batteryWhitelist(in));
        detectable.add(background(in));
        detectable.add(hostPerm(in, "host-camera", "本机相机权限", in.hostCamera,
                "这是 Alter Ego 自己的相机权限，不代表虚拟分身里可以扫码或录音。"));
        detectable.add(hostPerm(in, "host-mic", "本机麦克风权限", in.hostMic,
                "这是 Alter Ego 自己的麦克风权限，不代表虚拟分身里可以录音。"));
        place(detectable, manual, shortcut(in));
        place(detectable, manual, autostart(in));
        place(detectable, manual, popup(in));
        place(detectable, manual, oemBattery(in));
        List<Row> all = new ArrayList<Row>();
        all.addAll(detectable);
        all.addAll(manual);
        all.add(cloneCam());
        return all;
    }

    private static void place(List<Row> detectable, List<Row> manual, Row row) {
        if ("manual".equals(row.group)) manual.add(row);
        else detectable.add(row);
    }

    private static Row overlay(Inputs in) {
        String status = agree(in.overlaySettings, in.overlayAppOps);
        String detail;
        if ("unknown".equals(status)) detail = "悬浮窗状态这次没有读成，不表示已经关闭。";
        else if ("conflict".equals(status)) {
            detail = "系统设置和悬浮窗操作记录不一致。设置：" + yn(in.overlaySettings)
                    + "。操作记录：" + yn(in.overlayAppOps) + "。";
        } else if ("granted".equals(status)) detail = "系统设置和悬浮窗操作记录都是允许。";
        else detail = "系统设置和悬浮窗操作记录都是未允许。工具箱小窗不会出现。";
        return row("overlay", "detectable", "悬浮窗", status, badge(status), detail, true, "overlay");
    }

    private static Row a11yListed(Inputs in) {
        String status = agree(in.a11ySecure, in.a11yManager);
        String detail;
        if ("granted".equals(status)) detail = "已在系统的启用列表里。";
        else if ("denied".equals(status)) detail = "不在系统的启用列表里。";
        else if ("conflict".equals(status)) detail = "启用列表的两个来源不一致。";
        else detail = "启用列表这次没有读成，不表示已经关闭。";
        if (in.sdk >= 33 && !"granted".equals(status) && Boolean.TRUE.equals(in.a11yRestricted)) {
            detail = detail + " 侧载应用的开关可能是灰的，需要先在应用详情允许受限制的设置。";
        }
        return row("a11y-listed", "detectable", "无障碍启用列表", status, badge(status), detail, true, "accessibility");
    }

    private static Row a11yLive(Inputs in) {
        String status = one(in.a11yConnected);
        String badge = "granted".equals(status) ? "已连接" : "denied".equals(status) ? "未连接" : badge(status);
        String detail = "granted".equals(status) ? "服务当前已连接。"
                : "denied".equals(status) ? "服务当前没有连上。"
                : "连接状态这次没有读成，不表示已经关闭。";
        return row("a11y-live", "detectable", "无障碍实时连接", status, badge, detail, true, "accessibility");
    }

    private static Row notificationRuntime(Inputs in) {
        if (in.sdk < 33) {
            return row("noti-runtime", "detectable", "通知运行时权限", "na", "不适用",
                    "这个系统版本没有单独的通知运行时权限。", false, "notification");
        }
        String status = one(in.postNotifications);
        String detail = "granted".equals(status) ? "已允许通知。"
                : "denied".equals(status) ? "未允许通知。"
                : "通知权限这次没有读成，不表示已经关闭。";
        return row("noti-runtime", "detectable", "通知运行时权限", status, badge(status), detail, true, "notification");
    }

    private static Row notificationMaster(Inputs in) {
        if (in.sdk < 24) {
            return row("noti-master", "detectable", "通知总开关", "na", "不适用",
                    "这个系统版本没有通知总开关接口。", false, "notification");
        }
        String status = one(in.notificationsEnabled);
        String detail = "granted".equals(status) ? "通知总开关是开的。"
                : "denied".equals(status) ? "通知总开关是关的。"
                : "通知总开关这次没有读成，不表示已经关闭。";
        return row("noti-master", "detectable", "通知总开关", status, badge(status), detail, true, "notification");
    }

    private static Row channel(Inputs in, String id, String title, String signal) {
        if (in.sdk < 26) {
            return row(id, "detectable", title, "na", "不适用", "这个系统版本没有通知渠道。", false, "notification");
        }
        String status = channelStatus(signal);
        String detail;
        if ("blocked".equals(signal)) detail = "这条渠道已被关闭。";
        else if ("open".equals(signal)) detail = "这条渠道没有被关闭。";
        else if ("missing".equals(signal)) detail = "还没有创建这条渠道，不表示被关掉。";
        else detail = "这条渠道这次没有读成，不表示被关掉。";
        return row(id, "detectable", title, status, badge(status), detail, true, "notification");
    }

    private static Row batteryWhitelist(Inputs in) {
        String status = one(in.ignoringBattery);
        String detail;
        if ("granted".equals(status)) {
            detail = "已忽略系统电池优化。";
            if (!stock(in)) detail = detail + " 厂商省电策略需要在下一组里自己确认。";
        } else if ("denied".equals(status)) detail = "尚未忽略系统电池优化。";
        else detail = "系统电池白名单这次没有读成，不表示已经关闭。";
        return row("battery-whitelist", "detectable", "系统电池白名单", status, badge(status), detail, true, "battery-whitelist");
    }

    private static Row background(Inputs in) {
        if (in.sdk < 28) {
            return row("background", "detectable", "后台限制", "na", "不适用",
                    "这个系统版本没有后台限制这项读取接口。", false, "");
        }
        String status;
        String detail;
        if (in.backgroundRestricted == null) {
            status = "unknown";
            detail = "后台限制这次没有读成，不表示没有被限制。";
        } else if (in.backgroundRestricted) {
            status = "denied";
            detail = "系统报告正在限制后台。";
        } else {
            status = "granted";
            detail = "系统没有报告后台限制。这不是自启动已经打开。";
        }
        return row("background", "detectable", "后台限制", status, badge(status), detail, false, "");
    }

    private static Row hostPerm(Inputs in, String id, String title, Boolean granted, String caveat) {
        String status = one(granted);
        String lead = "granted".equals(status) ? "系统显示已允许。"
                : "denied".equals(status) ? "系统显示未允许。"
                : "这次没有读成，不表示已经关闭。";
        return row(id, "detectable", title, status, badge(status), lead + caveat, false, "");
    }

    private static Row shortcut(Inputs in) {
        if ("XIAOMI".equals(in.family)) {
            String status = one(in.xiaomiShortcut);
            String detail = "granted".equals(status) ? "桌面快捷方式权限已允许。"
                    : "denied".equals(status) ? "桌面快捷方式权限未允许。未允许时，添加分身到桌面会失败。"
                    : "桌面快捷方式这次没有读成，不表示未允许。";
            return row("shortcut", "detectable", "桌面快捷方式", status, badge(status), detail, true, "shortcut");
        }
        if ("SAMSUNG".equals(in.family)) {
            return row("shortcut", "manual", "桌面快捷方式", "manual", "需手动确认",
                    "三星没有单独的读取接口。请到应用详情的其他权限里查看主屏幕快捷方式。这里不能算已允许。",
                    true, "shortcut");
        }
        if (stock(in)) {
            return row("shortcut", "detectable", "桌面快捷方式", "na", "不适用",
                    "原生没有这项常驻开关。添加快捷方式时启动器仍可能再问一次，这里读不到已允许。",
                    false, "shortcut");
        }
        return row("shortcut", "manual", "桌面快捷方式", "manual", "需手动确认",
                "这个品牌的桌面快捷方式没有稳定读取接口。请到权限页里自己允许。读不到开关。",
                true, "shortcut");
    }

    private static Row autostart(Inputs in) {
        if (stock(in)) {
            return row("autostart", "detectable", "自启动与后台活动", "na", "不适用",
                    "没有单独的自启动开关。", false, "autostart");
        }
        return row("autostart", "manual", "自启动与后台活动", "manual", "需手动确认",
                autostartDetail(in.family), true, "autostart");
    }

    private static Row popup(Inputs in) {
        if (stock(in)) {
            return row("popup", "detectable", "后台弹出界面", "na", "不适用",
                    "原生没有「后台弹出界面」这项开关。", false, "popup");
        }
        return row("popup", "manual", "后台弹出界面", "manual", "需手动确认",
                "读不到后台弹出开关。请到本应用的权限页里自己允许。", true, "popup");
    }

    private static Row oemBattery(Inputs in) {
        if (stock(in)) {
            return row("oem-battery", "detectable", "厂商省电策略", "na", "不适用",
                    "原生没有单独的厂商省电策略。系统电池优化看上面的白名单。", false, "oem-battery");
        }
        return row("oem-battery", "manual", "厂商省电策略", "manual", "需手动确认",
                oemDetail(in.family), true, "oem-battery");
    }

    private static Row cloneCam() {
        return row("clone-cam", "clone", "分身里的相机和麦克风", "unknown", "未知",
                "宿主相机或麦克风是否允许，只说明 Alter Ego 自己。分身里能不能扫码、能不能录音，要打开那个分身实际用过之后，到诊断日志里看。这里不根据日志判断，也不表示可用。",
                false, "camera-diag");
    }

    private static String autostartDetail(String family) {
        if ("HUAWEI".equals(family)) return "读不到自启动开关。请在应用启动管理里改为手动管理，并允许后台活动。";
        if ("HONOR".equals(family)) return "读不到自启动开关。请在应用启动管理里允许自启动和后台活动。";
        if ("OPPO".equals(family)) return "读不到自启动开关。请在自启动管理和后台耗电里允许 Alter Ego。";
        if ("VIVO".equals(family)) return "读不到自启动开关。请在后台弹出和自启动里允许 Alter Ego。";
        if ("SAMSUNG".equals(family)) return "读不到自启动开关。请在电池里把 Alter Ego 设为不受限。";
        if ("MEIZU".equals(family)) return "读不到自启动开关。请在后台管理里允许 Alter Ego。";
        return "读不到自启动开关。请在 HyperOS / MIUI 的自启动管理里允许 Alter Ego。";
    }

    private static String oemDetail(String family) {
        if ("HUAWEI".equals(family) || "HONOR".equals(family)) {
            return "读不到厂商省电策略。请改为手动管理并允许后台活动。";
        }
        if ("OPPO".equals(family)) return "读不到厂商省电策略。请允许后台耗电。";
        if ("VIVO".equals(family)) return "读不到厂商省电策略。请允许高耗电和后台运行。";
        if ("SAMSUNG".equals(family)) return "读不到厂商省电策略。请加入「永不休眠的应用」。";
        if ("MEIZU".equals(family)) return "读不到厂商省电策略。请允许后台管理。";
        return "读不到厂商省电策略。请把电池策略设为「无限制」。这和系统电池白名单不是同一项。";
    }

    private static boolean stock(Inputs in) {
        return "STOCK".equals(in.family);
    }

    static String agree(Boolean a, Boolean b) {
        if (a == null || b == null) return "unknown";
        if (a.booleanValue() == b.booleanValue()) return a ? "granted" : "denied";
        return "conflict";
    }

    static String one(Boolean value) {
        if (value == null) return "unknown";
        return value ? "granted" : "denied";
    }

    static String channelStatus(String signal) {
        if ("open".equals(signal)) return "granted";
        if ("blocked".equals(signal)) return "denied";
        return "unknown";
    }

    private static String badge(String status) {
        if ("granted".equals(status)) return "已允许";
        if ("denied".equals(status)) return "未允许";
        if ("manual".equals(status)) return "需手动确认";
        if ("na".equals(status)) return "不适用";
        if ("conflict".equals(status)) return "冲突";
        return "未知";
    }

    private static String yn(Boolean value) {
        return Boolean.TRUE.equals(value) ? "允许" : "不允许";
    }

    private static Row row(String id, String group, String title, String status, String badge, String detail,
                           boolean canOpen, String openId) {
        return new Row(id, group, title, status, badge, detail, canOpen, openId);
    }
}
