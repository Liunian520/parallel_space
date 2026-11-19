# 能力状态页（就地升级机型适配检查）

基准：`main` `e12eae873b7394c04a6e40dc19c8f8eebb1f9358`，已发布 1.3.38。本文只定义下一轮实现。本文件落地时不改业务源码、不升版本、不打包。

已批准方案：就地升级现有 `CompatScreen`，不新做第二套状态页。

## 目标

用户在现在的「机型适配检查」里能看懂三件事：

- 这台机器上，哪些项系统接口明确允许或未允许
- 哪些项没有可靠读数，必须自己到系统里看
- 宿主权限不能当成虚拟分身已经能扫码或录音

绿色只表示这一项的读数明确为允许。页面不得出现「全部就绪」「分身可以扫码」「自启动已打开」这类总判语。`startActivity` 成功只表示打开了某个页面。

## 不在本功能里改的东西

- 启动图标和紫圈
- 密码悬浮窗：`ToolboxOverlayService` 继续只有查看、复制、一键填入，不加输入框或编辑框
- 默认分身的 `launchKey`，以及点名打开不改去默认分身
- 配置备份、分身数据备份、通知隔离、分身列举与同步
- `RootShell` 现有的 `resumed` 行为：它今天会刷新分身名单，并在工具箱开关开着时调用 `setToolboxEnabled`。本功能不扩大、不改写这段
- `A11yKeeper.ensure` 的实现，以及已经在用它的常驻路径（见下文「常驻和自愈」）
- 设置里已有的主动入口：`requestIgnoreBattery`、`openAutoStart`、`openShortcutPermission`
- 打开分身时 `launchVirtualWithPerms` 的权限申请
- `CameraDiag.selfTest` 在用户点击「开始自检」且宿主还没有相机权限时弹出系统授权窗的现有流程
- Shizuku、Root、adb 三种常驻授权的优先级和按钮位置（仍在工具箱页）
- 纯血鸿蒙 NEXT 与 iOS：不新增适配。NEXT 继续用现有横幅说明不能运行 Android APK。iOS 不增加权限接口
- 版本号。实现回合再升到 1.3.39（versionCode 12339），并在打包机上出包。本规格不承诺 Android 8–16 各品牌都有真机记录

## 入口和外观

两处入口都保留，仍推入同一个 `CompatScreen`：

- `lib/screens/settings_screen.dart`：`CupertinoPageRoute`
- `lib/screens/toolbox_screen.dart`：`MaterialPageRoute`

可以改这两行的副标题，让它说明页面会区分「能读到」和「要自己确认」。不新增路由，不删除入口。

外观继续用 `BlobBackground` 和 `Glass`。列表卡片保持 `blur: false`。真实模糊仍只在 `Glass(blur: true)` 且用户没开「精简特效」时出现。状态页不改这套规则。

顶部保留返回和手动刷新。标题仍是「机型适配检查」。

## 页头

只读报告带上现有身份字段：品牌、ROM 名称与版本、机型、Android 版本号、API、ABI。展示为一行摘要。

摘要下面固定一句：

> 绿色只表示这一项刚才读到「允许」。打得开设置不等于已经允许，也不能说明分身里的相机或麦克风可用。

现有鸿蒙说明和品牌提示可以留在页头。NEXT 的红色说明保留。品牌提示只列出建议去系统里查看的项，不写成这些项已经打开。

## 三个分组

顺序固定。

1. **可检测权限**：系统有接口，或当前适配范围明确没有这项开关。
2. **需手动确认**：适配范围里存在这项，但没有可靠读数。
3. **分身相机诊断**：虚拟分身里的相机和麦克风。状态固定为未知。

一项在不同品牌上可以落在不同组。例如桌面快捷方式：小米在第 1 组，华为等在第 2 组，原生在第 1 组且为不适用。

## 状态词

每项同时有两个独立字段：

- `status`：读数含义
- `canOpen`：有没有语义相关的设置页可打开

`canOpen == true` 不得把 `status` 改成已允许。

| status | 界面文字 | 颜色 | 含义 |
|---|---|---|---|
| `granted` | 已允许 | 绿 | 这一项的接口明确返回允许。连接类行写「已连接」，颜色与已允许相同 |
| `denied` | 未允许 | 红 | 接口明确返回拒绝或未启用。连接类行写「未连接」 |
| `unknown` | 未知 | 灰 | 接口存在，但调用失败、结果无法解释，或还没有可判定的材料 |
| `manual` | 需手动确认 | 橙 | 没有可靠读取接口，需要用户到系统里看 |
| `na` | 不适用 | 灰 | 这个 Android 版本没有该接口，或当前适配范围里没有这项开关 |
| `conflict` | 冲突 | 橙 | 两个都读成功的来源结果不一致 |

不适用、未知、需手动确认、冲突都不用绿色，也不用「正常」这个词。

读数失败时用未知。不得把异常、缺渠道、空返回写成未允许或已允许。

低版本分界：

- 这个系统版本根本没有该接口：不适用
- 接口存在但这次调用抛错或返回无法解释：未知

## 只读快照

新的报告函数只读。它不调用 `A11yKeeper.ensure`、`OemAdapter.accessibilityOn`、`accessibilityLikelyKilled`、`requestPermissions`、`startActivity`，也不使用把悬浮窗两个来源做「或」的 `canDrawOverlay`。

悬浮窗服务要不要拆掉，继续用现有的 `canDrawOverlay` / `overlayClearlyDenied`。状态页的严格规则不回写到保活判断。因此页面可以显示冲突，而悬浮窗服务仍按旧规则留着。这是有意分开的。

### 悬浮窗

两个来源都要读：

- `Settings.canDrawOverlays`
- AppOps `OPSTR_SYSTEM_ALERT_WINDOW`（API 29 起 `unsafeCheckOpNoThrow`，更早 `checkOpNoThrow`）

| 结果 | status |
|---|---|
| 两个都成功且都为允许 | `granted` |
| 两个都成功且都为拒绝 | `denied` |
| 两个都成功但不一致 | `conflict` |
| 任一调用抛错 | `unknown` |

冲突时的说明写明哪边允许、哪边不允许。HyperOS 在输入法或焦点切换时可能短暂把 `Settings.canDrawOverlays` 读成 false。这种不一致显示为冲突，页面不把它收成已允许。保活路径仍可按旧的「或」规则避免误拆服务。

`canOpen` 为 true。

### 无障碍：启用列表和实时连接分成两行

两行都不调用 `ensure`。一行已允许时，不得把另一行涂绿。

**启用列表**

- 来源 A：`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 含本服务的 flatten 名，且 `ACCESSIBILITY_ENABLED == 1`
- 来源 B：`AccessibilityManager.getEnabledAccessibilityServiceList` 含 `ToolboxAccessibilityService`

两个都成功且一致为在列表中：`granted`，说明写「已在系统的启用列表里」。两个都成功且一致为不在：`denied`。两个都成功但不一致：`conflict`。任一抛错：`unknown`。

API 33 及以上，且这一行不是已允许时，可以附带 `A11yJump.restricted` 的说明：true 时写侧载开关可能是灰的，需要先在应用详情允许受限制的设置；null 时不补充结论。API 32 及以下不提这项。这段说明不改变徽章。

**实时连接**

`ToolboxAccessibilityService.instance != null` 是一次成功观察：非空为已连接（`granted` 的颜色，文字用「已连接」），空为未连接（`denied` 的颜色，文字用「未连接」）。读取实例抛错时为 `unknown`，不写成未连接。

`canOpen` 两行都为 true。两行都沿用 `A11yJump.open(..., forceServicePage = true)`，这样服务已经连上时仍打开设置页，而不是直接返回 `on`。返回 `on`、`restricted`、`details`、`list` 时，snack 只说明打开了哪一页或需要先允许受限制的设置，不说已允许。

### 通知

四行，彼此独立：通知运行时权限、通知总开关、工具箱渠道、虚拟定位渠道。

**通知运行时权限**

- API 33 及以上：`POST_NOTIFICATIONS` 已授予为 `granted`，未授予为 `denied`，调用抛错为 `unknown`。`canOpen` 为 true，打开应用通知设置
- API 32 及以下：`na`。说明写「这个系统版本没有单独的通知运行时权限」。不得因为版本低就标已允许。`canOpen` 为 false。总开关那一行负责打开通知设置

**通知总开关**

- API 24 及以上：`NotificationManager.areNotificationsEnabled()` 为 true 则 `granted`，false 则 `denied`，抛错则 `unknown`
- API 23 及以下：`na`。当前 `minSdk` 至少是 24，这行是防御分支
- `canOpen`：API 26 及以上为 true，打开 `ACTION_APP_NOTIFICATION_SETTINGS`。API 24 和 25 没有这个专项页，`canOpen` 为 true，唯一目标是应用详情，返回码用 `details`，说明里写已打开应用详情

**两条已有渠道**

只看这两个 id，各占一行：

- `toolbox_overlay`（工具箱）
- `mock_location`（虚拟定位）

`toolbox_perm` 不进入本页。

- API 25 及以下：`na`（还没有通知渠道），`canOpen` 为 false
- API 26 及以上且渠道不存在：`unknown`。说明写「还没有创建这条渠道，不表示被关掉」。`canOpen` 为 true
- 渠道存在且 importance 为 `IMPORTANCE_NONE`：`denied`，说明写「这条渠道已被关闭」。`canOpen` 为 true
- 渠道存在且 importance 更高：`granted`，只表示这条渠道没被关掉。`canOpen` 为 true
- 调用抛错：`unknown`。`canOpen` 仍为 true（API 26 及以上），方便用户自己去通知页看

渠道行打开的是同一应用通知设置，不是某一条渠道的专用 Activity。snack 说明打开的是应用通知设置。

### 系统电池白名单

`PowerManager.isIgnoringBatteryOptimizations`：

- true：`granted`。说明只写「已忽略系统电池优化」
- false：`denied`
- 抛错：`unknown`

国产机的厂商省电策略不写进这行的徽章。这行已允许时，说明里指向下一组的「厂商省电策略」，并写明还要自己看。

`canOpen` 为 true。这一行的按钮只做导航，顺序见「电池按钮」里的白名单行。

### 后台限制

- API 28 及以上：`ActivityManager.isBackgroundRestricted` 为 true 时 `denied`，说明写「系统报告正在限制后台」；为 false 时 `granted`，说明必须写「系统没有报告后台限制。这不是自启动已经打开」；抛错为 `unknown`。这里的绿色只代表这一个布尔值是 false
- API 27 及以下（含 Android 8.0 / 8.1）：`na`。说明写「这个系统版本没有后台限制这项读取接口」。不得把缺接口写成「没有被限制」

这一行没有单独的开关页，`canOpen` 为 false。用户要处理后台时走自启动行或厂商省电行。

### 宿主相机和宿主麦克风

只调用 `checkSelfPermission`：

- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`

已授予为 `granted`，未授予为 `denied`，抛错为 `unknown`。不调用 `requestPermissions`，不打开相机，不录音。

行标题写明是 Alter Ego 自己的权限。说明写：这不代表虚拟分身里可以扫码或录音。

`canOpen` 为 false。本页不提供会弹出运行时授权窗的按钮。用户若要自检宿主相机，使用第三组里的诊断页，规则见「分身相机诊断」。

### 桌面快捷方式

以 `OemAdapter` 在 1.3.38 的适配范围为界，不把「打得开权限页」算成已允许。

| 范围 | 依据 | 分组与 status |
|---|---|---|
| 小米 / HyperOS / MIUI | 只有这里实现了读取：AppOps 10017 | 可检测。`MODE_ALLOWED` 为 `granted`，其他模式为 `denied`，抛错为 `unknown`。`canOpen` true |
| 华为、荣耀、OPPO、一加、realme、vivo、iQOO、魅族 | `shortcutNeedsPermission` 包含这些家族，且没有稳定读数。一加和 realme 属于 OPPO 家族，iQOO 属于 VIVO 家族 | 需手动确认，`manual`。`canOpen` true |
| 三星 | 现有 `manualHint` 写了「其他权限 / 主屏幕快捷方式」，但没有单独的读取接口，也不在 `shortcutNeedsPermission` 的可判定集合里 | 需手动确认，`manual`。不得标已允许。没有专用 Activity 时打开应用详情，返回码按真实回退显示 |
| Pixel 与其余原生 | 家族为 STOCK，适配范围里没有常驻的「桌面快捷方式」权限项，也没有厂商快捷方式页 | 可检测，`na`。说明写「原生没有这项常驻开关。添加快捷方式时启动器仍可能再问一次，这里读不到已允许」。`canOpen` false |

三星不标不适用，因为现有文案已经承认有「主屏幕快捷方式」这项。原生标不适用，因为适配范围里没有对应开关，而不是因为某台 Pixel 曾经添加成功。

## 需手动确认的三项

这三行的 `status` 固定为 `manual` 或 `na`，永远不因 `isBackgroundRestricted == false`、电池白名单已允许、或 Intent 打开成功而变成 `granted`。

**自启动与后台活动**

- 小米、华为、荣耀、OPPO（含一加、realme）、vivo（含 iQOO）、三星、魅族：`manual`。说明使用现有 `backgroundCheck` 里对应品牌的手动路径，并写明读不到开关。`canOpen` true，沿用现有自启动 Intent 链
- Pixel 与其余 STOCK：`na`。说明写「没有单独的自启动开关」。`canOpen` false。是否被后台限制只看上面的「后台限制」行

**后台弹出界面**

- 上述国产家族：`manual`，`canOpen` true，沿用现有弹出 Intent 链
- STOCK：`na`。说明写「原生没有『后台弹出界面』这项开关」。`canOpen` false。`manualHint` 在家族对不上时的通用句子只是打不开页面后的兜底文案，不作为「这项开关存在」的依据

**厂商省电策略**

- 非 STOCK：`manual`。说明写明小米「无限制」、华为 / 荣耀手动管理、OPPO / vivo 后台耗电、三星「永不休眠的应用」、魅族后台管理这些都读不到。`canOpen` true
- STOCK：`na`。系统电池优化只由「系统电池白名单」那一行表示，这里不重复。`canOpen` false

## 分身相机诊断

这一组固定一行：「分身里的相机和麦克风」，`status` 为 `unknown`，`canOpen` 为 false。

说明写明：

- 宿主相机或麦克风已允许，只说明 Alter Ego 自己被系统允许
- 分身进程的真实 uid 是宿主，包名和 Binder 身份是虚拟的。`CameraOpDecision` 只有在宿主相机明确已允许时才短路相机 AppOps；拒绝或读不到时不改系统结果
- 分身第一次进入前台只记录相机 id，不打开相机
- 能不能扫码、能不能录音，要用户自己打开那个分身再用过之后，到诊断日志里看。本行不解析 `camera_diag.log`，也不根据日志改成已允许

按钮「查看诊断日志」推入现有 `CameraDiagScreen`。推入这个页面，以及状态页的 `resumed`，都不调用 `cameraSelfTest`。

`CameraDiagScreen` 保持现状：进入页面只加载已有日志；只有用户点击「开始自检」才自检；宿主还没有相机权限时，仍按现在的 `MainActivity` 路径弹出系统相机授权窗。本功能不取消这条主动流程。只读状态页不进入这条路径。

## 去设置

每一项的按钮只在 `canOpen` 时出现。按钮文字用「去设置」。分身组的「查看诊断日志」不是去设置。

打开结果只说明落到了哪一层，snack 使用这些意思：

- 打开了该行允许的厂商页或系统专项页：已打开对应设置，请在里面找到这一项。打开不等于已允许
- 落到应用详情：厂商页没打开，已打开应用详情
- 落到系统设置首页：已打开系统设置
- 全部失败：展示现有 `manualHint`，不把失败说成已打开

不得出现「已授权」「已允许」「已开启」这种 snack。现有 `ok` / `requested` 若表示授权对话框的结果，状态页的按钮不使用那条返回。

### 语义

一条链按这个顺序：

1. 这项专用的系统页或厂商页
2. 现有文案已经说明该项开关就在其中的「本应用权限页」
3. 应用详情
4. 系统设置首页

第 1、2 步成功的返回码保持 `rom`（无障碍现有的 `details` / `list` / `restricted` 可以保留，snack 按实际页面说明）。第 3 步是 `details`。第 4 步是 `settings`。不预检 `resolveActivity`。每个 Intent 仍先不加 `NEW_TASK`，失败再加一次。

不属于该行的 Activity，即使 `startActivity` 成功，也不得当作第 1 步成功。实现时从悬浮窗链去掉这些无关目标：

- 三星电池页和智能管理器首页（`BatteryActivity`、`SmartManagerDashBoardActivity`）。悬浮窗失败后不得打开电池页并显示已打开厂商设置
- OPPO 链里的一加 `ChainLaunchAppListActivity`（关联启动，不是悬浮窗）
- 华为悬浮窗链里的 `NotificationManagmentActivity`
- 魅族悬浮窗链里的 `SmartBGActivity`

悬浮窗在去掉这些项之后，仍保留各品牌真正的悬浮窗页或本应用权限编辑页，然后是 `ACTION_MANAGE_OVERLAY_PERMISSION`，再是应用详情和系统设置。

自启动、后台弹出、厂商省电继续用现有对应链。三星的自启动目标就是电池页，那一行可以打开电池页，snack 说明打开的是电池相关设置，并且不把徽章改成已允许。

本功能不新增品牌 Activity，不扩大 Android 8–16 的机型承诺。组件名收到一份 JVM 可测的列表里，Kotlin 按这份列表生成 Intent，避免两套名字。

### 电池按钮

两条行都只导航，都不发送 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，都不调用 `requestIgnoreBattery`。

系统电池白名单：

1. `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
2. 应用详情
3. 系统设置

厂商省电策略（仅非 STOCK，且该行是需手动确认）：

1. 现有 `batteryOemIntents`
2. `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
3. 应用详情
4. 系统设置

厂商页打不开时 snack 按真实落点说应用详情或系统设置，不把电池白名单说成厂商策略已打开。

设置页「忽略电池优化」那一行继续调用 `requestIgnoreBattery`。用户点那一行时，现有的系统授权窗可以出现。状态页不调用它。

实现前核对调用点。当前界面里 `openCheck("battery")` 只有状态页在用。把它改成上述导航。若核对时发现另一个已经明确要弹授权窗的调用，那个调用保持 `requestIgnoreBattery`，不要改掉。

## 返回后刷新

只改 `CompatScreen` 自己的加载。

- 每次加载使用递增序号。返回后若序号已过期，丢弃结果，不 `setState`
- 页面 `resumed` 时加载一次
- 点「去设置」之后，不在 `startActivity` 刚返回时立刻加载。等 `resumed` 再读，避免把用户还没改的旧状态写回界面
- 打开失败时只显示手动路径，不额外报一次成功刷新
- 这次加载不调用 `CloneStore.refresh`，不调用 `setToolboxEnabled`，不启动悬浮窗

`RootShell` 自己的 `resumed` 仍会刷新分身名单，并可能按现有逻辑启动工具箱。那是页外的既有行为，本功能不把它说成「整个应用从设置返回时不会保活」。

手动刷新按钮与 `resumed` 共用同一套序号。

## 常驻和自愈

状态报告这条调用链独立，并且只读。

以下路径保持，本功能不删除、不改条件：

- `A11yKeeper.ensure` 在已有 `WRITE_SECURE_SETTINGS` 且服务不在启用列表时写回 `Settings.Secure`
- 开机接收器、授权成功后的 `afterGrant`
- `OemAdapter.accessibilityOn` 内部继续可以调用 `ensure`。工具箱页的 `a11yStatus`、保活判断继续走这条现有函数
- `MainActivity.onResume` 继续调用 `accessibilityOn`，并发送 `permPossiblyChanged`。因此从系统设置返回时，若用户已经开了常驻，活动恢复仍可能先把无障碍写回去，然后状态页才读到「已连接」或「已在列表里」。这是既有自愈，不是报告函数新写的
- Shizuku、Root、adb 命令与工具箱页上的按钮保持原优先级。状态页不放这些授权按钮，也不显示会发起授权的操作

不把 `onResume` 改成调用只读报告来代替 `accessibilityOn`。那样会关掉用户要求保留的自愈。

## 测试

沿用仓库里 Java `main()` 套件的做法，不把「JUnit 任务找到 0 个测试」当成通过。Flutter 测试走 `flutter test`。

**只读**

- 组装报告所调用的读取步骤不包含 `A11yKeeper.ensure`、`requestPermissions`、`startActivity`
- 给定宿主相机未授予，报告里的宿主相机是未允许，分身行仍是未知
- 报告函数不打开相机、不录音

**状态决策**

- 悬浮窗：一致允许、一致拒绝、冲突、任一异常。异常不是拒绝，冲突不是允许
- 无障碍两行独立。列表允许且实例为空时，连接行是未连接，列表行仍是已允许
- 通知：API 32 的运行时权限是不适用；API 33 未授予是未允许；总开关抛错是未知；渠道不存在是未知；`IMPORTANCE_NONE` 是未允许
- 后台限制：API 27 是不适用；API 28 抛错是未知；API 28 返回 false 时文案包含「不是自启动已经打开」，且自启动行在国产机上仍是需手动确认，在 STOCK 上是不适用
- 快捷方式：小米 10017 允许才是已允许；华为等是需手动确认；三星是需手动确认；STOCK 是不适用。这些都不会因为 `canOpen` 变成已允许
- 自启动、后台弹出、厂商省电在任何布尔组合下都不是已允许
- 宿主相机已允许时，分身行仍是未知

**版本和异常**

- 上表的 API 分界各有一条输入
- 决策函数收到 null 或异常标记时输出未知，不输出未允许

**Intent**

- 悬浮窗组件列表不含三星 `BatteryActivity`、`SmartManagerDashBoardActivity`、一加 `ChainLaunchAppListActivity`、华为 `NotificationManagmentActivity`、魅族 `SmartBGActivity`
- 每条仍被使用的链以应用详情和系统设置收尾
- 模拟厂商页全部失败时，结果先是应用详情，再失败才是系统设置；结果码不是已允许
- 白名单导航的目标列表以 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 开头，不含 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，也不含厂商省电 Activity
- 厂商省电导航以 `batteryOemIntents` 开头，同样不含 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

**Flutter**

- `unknown`、`manual`、`na`、`conflict` 的文字和颜色都不是绿色「正常」
- `canOpen` 为 false 时没有「去设置」；为 true 时按钮存在，且与已允许、需手动确认无关
- 两次加载里，先发出的请求后返回，界面保持后一次的结果
- 页面 dispose 之后返回的请求不 `setState`
- 通道抛错或返回空时，显示读不到状态的现有空态，不弹出权限窗
- 打开 `CameraDiagScreen` 的测试不调用 `cameraSelfTest`；现有「开始自检」按钮仍在

**卸载与失败安全**

- 不修改 `AndroidManifest` 的权限，不新增设备管理员
- 包信息或渠道读取失败时页面不崩溃，对应行是未知
- 不因本页读取去申请相机、麦克风、悬浮窗、通知或电池白名单

## 验收

实现同时满足下面各项才算完成：

- 设置和工具箱仍进入原来的 `CompatScreen`，毛玻璃和精简特效规则不变
- 页头有品牌和 Android 版本，下面是三个分组
- 状态六种的颜色符合上表，且 Intent 成功不改变状态
- 只读报告不写系统设置、不申请权限；`A11yKeeper.ensure` 和 `MainActivity.onResume` 的自愈仍在
- 状态页电池按钮只导航；设置页「忽略电池优化」仍走 `requestIgnoreBattery`
- 三星悬浮窗打不开时不会打开电池页并声称成功
- 从设置返回用序号防止旧结果覆盖新结果；状态页自己的刷新不刷新分身名单、不启动悬浮窗
- 分身相机行保持未知；诊断页不自动自检；用户点击「开始自检」时的原授权流程还在
- 上列测试通过。不把未做的真机品牌矩阵写成已验证

## 已接受的限制

这些限制写进实现，不留到以后再解释：

- Android 8–16 的小米 / HyperOS、华为（能安装本应用的 HarmonyOS / EMUI）、荣耀、OPPO / 一加 / realme、vivo / iQOO、三星、魅族、Pixel 的 Intent 仍是 1.3.38 这套链，只删除悬浮窗链上已列明的无关页。没有各版本真机通过的记录
- ROM 识别错误时，分组会跟着错。本功能不重写识别
- 从设置返回时，活动的 `onResume` 仍可能先自愈无障碍，只读报告随后读到的是自愈之后的结果
- 悬浮窗页面用严格的双来源规则，服务保活用旧的「或」规则。两者可以同时一个显示冲突、一个保持服务
- 原生快捷方式标不适用，依据是当前适配范围没有这项常驻开关，不是某台机器添加成功的证明
- 诊断页在用户点击「开始自检」且尚未授权时仍会弹出相机权限窗。状态页不会主动走进这个流程
