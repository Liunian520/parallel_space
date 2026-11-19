# 能力状态页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 就地升级 `CompatScreen`，用只读快照区分已允许、未允许、未知、需手动确认、不适用和冲突。

**Architecture:** 纯 Java `PermissionStatusDecision` / `PermissionTargets` 决定状态、文案、分组和悬浮窗目标名单，JVM 测试不依赖 Android SDK。Kotlin `PermissionStatus` 只采集信号并调用 Java。Flutter 只渲染报告，用序号丢掉过期刷新。

**Tech Stack:** Java 21 `main()` 套件、Kotlin、Flutter。

用户已指定在本会话按测试驱动直接实现，不再选择执行方式。

## Global Constraints

- 基准 `e12eae873b7394c04a6e40dc19c8f8eebb1f9358`，实现版本 `1.3.39+12339`。不改 `update_manifest`，不打包，不上传。
- 就地升级 `CompatScreen`。设置与工具箱两个入口保留。列表卡片 `blur: false`。精简特效规则不改。
- 状态字符串只用 `granted` `denied` `unknown` `manual` `na` `conflict`。分组只用 `detectable` `manual` `clone`。
- `canOpen` 不改变 `status`。`na` / `unknown` / `manual` / `conflict` 不用绿色。
- 读失败是 `unknown`，不是 `denied` 或 `granted`。接口不存在是 `na`。
- 报告读取不调用 `A11yKeeper.ensure`、`accessibilityOn`、`requestPermissions`、`startActivity`、`canDrawOverlay`、`openCamera`。
- `MainActivity.onResume` 继续调用 `accessibilityOn`。`requestIgnoreBattery` 和设置页「忽略电池优化」保留。相机「开始自检」的授权窗保留。状态页不进入这些路径。
- 状态页电池按钮只导航，不发 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
- 悬浮窗链不得包含 `BatteryActivity`、`SmartManagerDashBoardActivity`、`ChainLaunchAppListActivity`、`NotificationManagmentActivity`、`SmartBGActivity`。
- 不改 `RootShell`、`launchKey`、备份、通知隔离、分身列举、密码悬浮窗、启动图标。
- 不把未做的真机矩阵写成已验证。没有 Android SDK 时不得声称 Kotlin 已编译。

## 文件

- 新建 `android/app/src/main/java/com/parallelspace/parallel_space/PermissionStatusDecision.java`：行决策。
- 新建 `android/app/src/main/java/com/parallelspace/parallel_space/PermissionTargets.java`：悬浮窗目标与电池导航顺序。
- 新建 `android/app/src/test/java/com/parallelspace/parallel_space/PermissionStatusDecisionTest.java`。
- 新建 `android/app/src/main/kotlin/com/parallelspace/parallel_space/PermissionStatus.kt`：只读采集。
- 修改 `OemAdapter.kt`：悬浮窗 Intent 改用 Java 名单；电池白名单与厂商省电分开导航；`openCheck("battery")` 不再请求授权。
- 修改 `ToolboxController.kt`：增加 `permissionStatus`。不改 `compatReport`（工具箱引导仍看旧的 `accessibility/ok`）。
- 修改 `lib/services/native_bridge.dart`、`lib/screens/compat_screen.dart`、`lib/screens/permission_status_style.dart`。
- 修改两处入口副标题。不改 `camera_diag_screen.dart` 的自检授权。
- 测试 `test/permission_status_test.dart`。
- 版本：`pubspec.yaml`、`lib/config/app_version.dart`、`README.md`、`CHANGELOG.md`。

## 接口

`PermissionStatusDecision.Inputs` 字段：`sdk`、`family`、`rom`、`overlaySettings`、`overlayAppOps`、`a11ySecure`、`a11yManager`、`a11yConnected`、`a11yRestricted`、`postNotifications`、`notificationsEnabled`、`toolboxChannel`、`mockChannel`、`ignoringBattery`、`backgroundRestricted`、`hostCamera`、`hostMic`、`xiaomiShortcut`。布尔用 `Boolean`，`null` 表示调用失败。渠道信号：`open` `blocked` `missing` `error`。

`rows(Inputs)` 返回 `List<Row>`。`Row` 字段：`id` `group` `title` `status` `badge` `detail` `canOpen` `openId`。

`PermissionTargets.overlayOem(family)` 返回 `List<Target>`。`Target` 字段：`pkg` `cls` `action` `extraKey`。`overlayLabels(family)` 以 `APPLICATION_DETAILS`、`SETTINGS` 结尾。`batteryWhitelistLabels()` 与 `oemBatteryLabels()` 不含 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。

通道方法名：`permissionStatus`。打开 id：`overlay` `accessibility` `notification` `battery-whitelist` `autostart` `popup` `shortcut` `oem-battery`。`openCheck("battery")` 与 `battery-whitelist` 相同，只导航。

---

### Task 1: 状态决策

**Files:**
- Test: `android/app/src/test/java/com/parallelspace/parallel_space/PermissionStatusDecisionTest.java`
- Create: `android/app/src/main/java/com/parallelspace/parallel_space/PermissionStatusDecision.java`

- [x] **Step 1: 写失败测试并运行**

Run: `javac -d /tmp/ps-status android/app/src/test/java/com/parallelspace/parallel_space/PermissionStatusDecisionTest.java`

Expected: 编译失败，找不到 `PermissionStatusDecision`。

- [x] **Step 2: 实现决策并复跑测试**

覆盖：悬浮窗一致、冲突、单边 null；无障碍两行独立；通知 API 32/33、总开关异常、渠道缺失与 `blocked`；后台限制 API 27 为 `na`、API 28 的 false 文案含「不是自启动已经打开」；快捷方式小米/华为/三星/STOCK；自启动、后台弹出、厂商省电永不为 `granted`；宿主相机已允许时分身行仍为 `unknown`。

Run: `java -cp /tmp/ps-status:android/app/src/main/java com.parallelspace.parallel_space.PermissionStatusDecisionTest`

Expected: `PermissionStatusDecisionTest OK`

- [x] **Step 3: Commit**

### Task 2: Intent 名单与只读采集边界

**Files:**
- Create: `PermissionTargets.java`
- Create: `PermissionStatus.kt`
- Modify: `OemAdapter.kt`、`ToolboxController.kt`

- [x] **Step 1: 测试禁止的悬浮窗组件、电池导航顺序，以及 Kotlin 源码不含 `A11yKeeper` / `requestPermissions` / `startActivity` / `canDrawOverlay`**
- [x] **Step 2: 先看测试失败，再改名单和 `openBatteryWhitelist` / `openOemBattery`**
- [x] **Step 3: 源码测试通过后提交。没有 Android SDK 就不跑 Kotlin 编译。**

### Task 3: Flutter 页面

**Files:**
- Create: `lib/screens/permission_status_style.dart`
- Modify: `lib/screens/compat_screen.dart`、`lib/services/native_bridge.dart`
- Test: `test/permission_status_test.dart`
- Modify: `settings_screen.dart`、`toolbox_screen.dart` 副标题

- [x] **Step 1: 测试六状态颜色、三组标题、序号、dispose、空通道、进入相机页不调用 `cameraSelfTest`、状态页源码不引用 `CloneStore` / `setToolboxEnabled` / `requestIgnoreBattery`**
- [x] **Step 2: 看失败后实现页面。`resumed` 才刷新，打开设置后不立刻读。**
- [x] **Step 3: Commit**

### Task 4: 1.3.39 文档

- [x] 同步 `pubspec.yaml`、`app_version.dart`、README 徽章、CHANGELOG。写明未打包、未上传、未改更新清单、真机未验证。
- [x] `flutter analyze` 与全量 `flutter test`。Java `main()` 再跑一遍。
- [x] Commit，推送到 PR 5。不 merge，不 force push。

## 规格对照

| 规格 | 任务 |
|---|---|
| 三分组、六状态、四条通知 | Task 1、Task 3 |
| 悬浮窗冲突、无障碍拆行、API 边界、失败不伪关 | Task 1 |
| 快捷方式小米/三星/原生 | Task 1 |
| 分身相机固定未知，自检授权保留 | Task 1、Task 3 |
| 只读不写设置、不申请权限 | Task 2 |
| 常驻 `onResume` 与设置页电池授权保留 | Task 2 源码锁定 |
| 状态页电池只导航 | Task 2 |
| 三星悬浮窗不落电池页 | Task 2 |
| 返回序号、卸载/空通道 | Task 3 |
| 不改 RootShell、launchKey、备份、悬浮窗、图标 | 不修改这些文件 |
