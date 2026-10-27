# 更新记录

版本号只递增最后一位。当前是 1.3.11（versionCode 12311），下一次是 1.3.12（12312）。1.3.x 使用 `versionCode = 12300 + 修订号`。每次改版本，同步更新 `pubspec.yaml`、`lib/config/app_version.dart`、`README.md` 顶部徽章和本文件。

## [1.3.11] - 2026-10-28

### 修复

- **输入密码时工具箱闪烁后消失（根因）**：诊断日志显示序列为 `ignore outside_tap reason=ime_or_edit` → `tick stop: enabled/overlay lost` → `overlay onDestroy`。**不是崩溃**，而是 `tick` 在 HyperOS（sdk 36）上读到 `enabled` 或 `Settings.canDrawOverlays` 瞬假后立刻 `stopSelf`。1.3.11：
  - 单次否定绝不停服；需连续 ≥3 次且跨度 ≥10 秒，且未打开编辑页/面板。
  - 悬浮窗权限用 Settings + `AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW` 交叉验证，两者都否才算真正丢失。
  - 每次否定写入诊断日志（`enabled=` / `settingsOverlay=` / `appOpsOverlay=`）。
  - 用户关闭开关：`SharedPreferences.commit()` + 显式 `ACTION_USER_STOP`，不再只靠 tick 轮询。

### 架构

- **悬浮窗永久 `FLAG_NOT_FOCUSABLE`**，不再承载任何 `EditText`（避免 IME/焦点切 flags）。
- **添加/编辑账号**改走独立 `ToolboxEditActivity`（`:toolbox_edit` 进程、毛玻璃底部卡片、密码可见切换）；保存后广播刷新悬浮窗列表；编辑页崩溃不会拖垮主进程悬浮窗服务。
- 密码本改为 `filesDir/vault_plain.json` 跨进程持久化（仍备份 SharedPreferences）。

### 其他

- 版本 `1.3.11+12311`。
- 保留 1.3.9/1.3.10 的崩溃日志、`reason=` 隐藏日志、START_STICKY、看门狗。

## [1.3.10] - 2026-10-27

### 修复

- **工具箱输入密码时闪烁后消失**：用户反馈在工具箱「添加/编辑账号」里敲密码时，悬浮窗会快速闪一下然后整颗小球没了（红米 K80 / HyperOS）。相对 1.3.9 的新线索是：崩溃发生在**工具箱自己的密码框**，不是分身应用的输入框。
- **根因（代码审计，防御性全修）**：
  1. `resolveAppInfoAsync` 等异步回调在 `expanded` 时无条件 `rebuild()`，编辑页 `removeAllViews()` 拆掉正在输入的 `EditText`，表现为闪烁；
  2. 打开系统键盘时，IME 区域会给悬浮窗发 `ACTION_OUTSIDE`；与焦点切换叠在一起时，偶发收起；
  3. `focusable(true)` / `applyVisibility` 里 `updateViewLayout` 一旦失败就 `removeView()`，HyperOS 在 IME 过渡期更容易踩中，直接把气泡摘掉。
- **1.3.10 对策**：
  - 进入编辑即「会话钉住」；输入框有焦点、IME 可见或 IME 守卫宽限期内，`tick` 绝不 `hideOverlay`，窗外触摸一律忽略；
  - 编辑页已挂着输入框时，跳过图标/列表等异步 `rebuild`（仅 `enter_edit` / `force_edit` 允许重建）；
  - 相同 window flags 不再重复 `updateViewLayout`；编辑中 `updateViewLayout` 失败只记日志、不拆窗；
  - `removeView` 前捕获草稿，看门狗重建编辑页时恢复已输入内容；
  - 每次主动隐藏写明 `reason=`（`tick_hide` / `outside_tap` / `ime_focus` / `apply_visibility_update_failed` 等），诊断日志可直接对照。

### 变更

- 版本 `1.3.10+12310`。

### 说明

- 保留 1.3.9 的崩溃日志、`START_STICKY`、看门狗与 `/proc` 分身识别。
- 若仍复现，请到「设置 → 工具箱诊断日志」复制全文反馈。

## [1.3.9] - 2026-10-27

### 修复

- **工具箱小球在分身里又消失**：HyperOS（红米 K80 等）上 `getRunningAppProcesses` 经常看不到虚拟引擎的 `:pN` 进程，旧逻辑会在约 20 秒后误判「分身已退出」并 `removeView`。1.3.9 增加 `/proc/<pid>/cmdline` 兜底识别同包 `:p*`，并在打开分身后 `STICKY_MS`（15 分钟）内即使进程列表为空也保持气泡；隐藏/停止阈值大幅放宽。
- **悬浮窗操作全面防御**：`addView` / `updateViewLayout` / `removeView` / `rebuild` / 展开收起动画全部 try/catch；动画带视图代数，避免旧 `withEndAction` 打到已拆除的窗口；半隐贴边只藏 1/3，不再看起来像消失。
- **无障碍填入更稳**：树遍历限深 48、限节点 250；节点 recycle 更保守；`SET_TEXT`/粘贴时的短 sleep 永不在主线程；主线程调用会转到后台执行，降低 ANR / 服务被杀概率。

### 新增

- **工具箱诊断日志**：崩溃与看门狗事件写入应用私有目录 `toolbox_crash.log`；设置 / 工具箱页可查看、复制、分享、清空，方便无 logcat 时定位。
- **未捕获异常自动拉回**：工具箱进程装了专用 UncaughtExceptionHandler，FATAL 落盘后用 AlarmManager 约 800ms 再启悬浮窗服务；配合 `START_STICKY` 双保险。

### 变更

- 版本 `1.3.9+12309`。

### 说明

- 仍需悬浮窗权限；一键填入仍建议开启无障碍，否则退回复制粘贴。
- 虚拟引擎、`blackreflection` / `black.**` keep、进程名补丁、直链更新均保留。

## [1.3.8] - 2026-10-27

### 修复

- **悬浮窗权限不跳转**：根因主要是从 Activity 启动时一律加了 `FLAG_ACTIVITY_NEW_TASK`，在 HyperOS 上常把设置页丢到后台，看起来像「没跳转」；再叠加对话框关掉后立刻 `startActivity`、以及厂商页 `resolveActivity` 在 Android 11+ 包可见性下的误判。1.3.8 改为 Activity 优先不加 NEW_TASK、失败再带旗标重试；不预检 resolveActivity；对话框关闭后延迟一帧再跳；失败时给出该品牌的手动路径。
- 同一套稳健跳转也用于无障碍、电池、自启动、快捷方式、通知。

### 新增

- **多品牌权限链**：小米 / Redmi / POCO（MIUI、HyperOS）、华为（EMUI、HarmonyOS）、荣耀 MagicOS、OPPO / 一加 / realme、vivo / iQOO、三星 One UI、魅族 Flyme、Pixel / 原生，覆盖 Android 8–16。`OemAdapter` 集中维护有序 Intent 表，末端必有标准页与应用详情。
- **机型适配检查**展示品牌、ROM 与版本；从权限页返回后自动刷新，悬浮窗授权后自动拉起工具箱。
- **一键填入增强**：收起悬浮窗后再填；识别密码框与账号框（含 学号 / 邮箱等）；`SET_TEXT` 失败则焦点 + 粘贴；搜索 WebView 节点并校验是否写入。
- **复制账号 / 复制密码**：悬浮窗与密码本均提供独立按钮，长按也可复制；填入失败或未开无障碍时自动复制并提示「长按输入框粘贴」，不自动清空剪贴板。
- **无障碍一次性引导**：说明用途与限制（游戏、银行、加固登录页可能填不进）。

### 变更

- **工具箱悬浮窗 UI**：48dp 圆角半透明气泡，空闲半隐贴边并变淡，拖动松手有回弹吸附；面板 20dp 圆角半透明描边卡片（不做 blur-behind），带平行空间图标、标题和当前分身；账号行含掩码密码、显示切换、一键填入与复制按钮；空状态与添加/编辑表单更接近 Material You。支持动态取色与深色模式。
- 版本 `1.3.8+12308`。

### 说明

- 一键填入对使用私有控件、WebView 加固、或禁止无障碍的应用可能无效，请用复制粘贴。
- 虚拟引擎、`blackreflection` / `black.**` keep、进程名补丁、直链更新均保留。

## [1.3.7] - 2026-10-27

### 修复

- **工具箱卡顿后消失**：根因有三处——① 编辑账号时悬浮窗抢焦点，分身进程重要性短暂降到 VISIBLE 以下，旧版 `tick` 每秒走 `else { removeView() }` 直接拆掉气泡；② 打开键盘时系统会给悬浮窗发 `ACTION_OUTSIDE`，旧版一律收起面板；③ 服务用 `START_NOT_STICKY`，被 HyperOS 杀掉后不会自启。
- **1.3.7 对策**：有分身进程就一直保留气泡；编辑中 / 输入框有焦点时忽略窗外触摸；`START_STICKY` + `onTaskRemoved` 再拉起；看门狗发现气泡被系统摘掉会重新 `addView`；`addView` / `updateViewLayout` 失败只记日志，下一拍恢复；JSON / 图标解析挪到后台线程，主线程更顺。

### 变更

- **添加账号界面**：包名不再手填。改为只读芯片——当前分身的图标 + 应用名，自动绑定，界面更干净。
- 键盘用 `SOFT_INPUT_ADJUST_PAN`，面板打开时可正常打字，点「收起」或取消才关闭。
- 版本 `1.3.7+12307`。

### 说明

- 密码本仍是方便优先（无生物识别门禁）。
- 虚拟引擎、`blackreflection` / `black.**` keep、进程名补丁、直链更新均保留。

## [1.3.6] - 2026-10-27

### 修复

- **工具箱不再糊屏**：去掉悬浮窗的 `FLAG_BLUR_BEHIND` / `blurBehindRadius` / dim。气泡与展开面板都是 `WRAP_CONTENT` 小窗；展开时 `FLAG_NOT_TOUCH_MODAL` + `FLAG_WATCH_OUTSIDE_TOUCH`，点窗外收起，触摸穿透到下层分身应用。面板只保留半透明背景的玻璃感。
- **覆盖安装失败**：早期 1.3.1/1.3.2 的 versionCode 是 12301/12302，1.3.3–1.3.5 误用了更小的 10303–10305，系统会当成降级而要求卸载。1.3.6 起改为 `12300 + 修订号`（本版 **12306**），高于所有已发版，可覆盖安装。签名仍用同一把 `~/.android/debug.keystore`。
- 应用内更新按 versionCode 数值比较：12306 会正确提示已装 10305 / 12302 的用户升级。

### 变更

- **密码本改为方便优先**：去掉生物识别 / 锁屏门禁、「无锁屏不能保存」、收起自动锁定、剪贴板 30 秒清空。条目保存在本机 SharedPreferences 明文 JSON；若还能解开旧的 `vault.enc` 会自动迁移。一点即可填入；无障碍未开时复制到剪贴板（不自动清除）。
- 版本 `1.3.6+12306`。

### 说明

- 私密空间的 PIN / 生物识别未改。
- 虚拟引擎、`blackreflection` / `black.**` keep、进程名补丁、直链更新均保留。

## [1.3.5] - 2026-10-27

### 新增

- 应用内更新改为「直链优先」：清单从 GitHub raw 的固定地址读取（`update_manifest.txt`），APK 使用网盘永久直链入口 `https://link.liunianai.cn/f/{objectKey}`（服务端 302 现签 CDN，绕过 Link 盾）。
- 检查更新或下载失败时，可一键用浏览器打开分享页作为回退。

### 变更

- 版本 `1.3.5+10305`。
- 上传脚本在发版时把 APK 的 `publicUrl` 写入清单，并同步覆盖仓库内的 `update_manifest.txt`；App 与仓库均不包含网盘 API 密钥。
- 设置页「检查更新」在清单直连失败时弹出对话框，可选择浏览器打开。

### 说明

- 分享短链（如 `n8afhu`）仍可能被地区 / Link 盾拦截，因此不再作为 App 内主路径。
- 1.3.4 的工具箱、密码本、机型适配与开源署名均保留。

## [1.3.4] - 2026-10-27

### 新增

- 分身运行时的工具箱悬浮小窗：可拖动、可收起，毛玻璃和 Material You，文案为简体中文。工具列表来自 `ToolboxCatalog`，之后可以继续注册工具。
- 账号密码本：用 Android Keystore 的 AES-GCM 加密后只保存在本机，按分身 uid 或包名分组，可增删改查。打开前通过生物识别或设备锁验证。点「填入」时，无障碍服务把用户名和密码写入当前焦点或识别到的密码框；没开无障碍，或找不到输入框时，复制到剪贴板并在 30 秒后清除。
- 设置中的「工具箱」入口，可开关小窗，并引导悬浮窗权限和无障碍服务。各厂商的悬浮窗页面在 `OemAdapter` 里逐个 try，失败则回到应用详情和系统设置。
- 「机型适配检查」页：列出悬浮窗、无障碍、通知（Android 13+）、电池优化白名单、自启动与后台、桌面快捷方式（小米可读 AppOps 10017，vivo / OPPO 等提供权限页）、前台服务类型（Android 14+ 的 specialUse）、虚拟引擎进程名、64 位、Android 15/16 行为。能跳转的项都有一键入口。
- 无障碍服务若曾开启后又被系统关掉，会在通知里提醒一次（需要通知权限）。
- 设置页底部署名：本项目开源，作者流年似水，可点开 `https://github.com/Liunian520/parallel_space`。
- 仓库根目录增加 MIT `LICENSE`，版权人流年似水。

### 变更

- 版本 `1.3.4+10304`。
- 悬浮窗由前台服务承载，`foregroundServiceType="specialUse"`，并声明用途说明，避免 Android 14 及以上启动失败。
- 小米 HyperOS / MIUI、华为 HarmonyOS / EMUI、荣耀 MagicOS、OPPO ColorOS、vivo OriginOS、三星 One UI、Pixel 的跳转都集中在 `OemAdapter`。`tryStart` 不再因为 `resolveActivity` 为空就放弃，包可见性拦截时仍会直接 `startActivity`，异常再试下一个页面。

### 未改动

- 进程名补丁、`blackreflection` / `black.**` / `me.weishu` 的 ProGuard keep、DaemonService 的 specialUse 都还在。
- 没有加入指纹伪装、计算器伪装或隐藏最近任务，也没有加入网盘 API 密钥。

## [1.3.3]

### 修复

- 引擎已就绪时，设置不再把「工作资料 / 尚未创建」放在分身空间第一项。系统里如果还留着早期工作资料，提供「删除残留工作资料」。
- 虚拟引擎可用时可以固定桌面快捷方式，不再要求工作资料。点击快捷方式走 `VirtualEngine.launch`。HyperOS 的权限页增加一条不带 action 的权限编辑器。
- 分身图标默认走个人空间的原图标。没有工作资料时不再停在彩色首字占位。

### 说明

1.3.1 的进程名补丁、1.3.2 的 `blackreflection` / `black.**` keep、错误页和 DaemonService specialUse 都保留。发布包 `classes.dex` 仍包含全部 21 个 `blackreflection` 类和 964 个 `black.**`。

## [1.3.2]

### 修复

1.3.1 在 Redmi K80 上首页变成「虚拟分身引擎未就绪」，错误是 `NoClassDefFoundError: top.niunaijun.blackbox.BlackBoxCore`。

- `black-reflection.jar` 的 21 个类没有 consumer ProGuard，R8 把它们收掉了。
- AAR 里约 964 个 `black.**` 镜像接口也被收成空接口并改名，`@BClassName` 丢失，`BlackReflection.create` 失败。

`proguard-rules.pro` 增加 keep：`top.niunaijun.blackreflection.**`、`black.**`、`me.weishu.reflection.**`，以及 AAR 里的 `a.a` / `b.a` / `b.b` / `c.a` / `d.a`，并保留运行时注解。没有关掉 R8。`VirtualEngine` 的错误文本带上 cause 链。

## [1.3.1]

### 修复

Redmi K80（HyperOS / Android 15）安装 1.3.0 后，首页停在「正在准备虚拟分身」。

`BlackBoxCore.getProcessName` 只查 `ActivityManager.getRunningAppProcesses()`。在 `Application.attachBaseContext` 阶段，HyperOS 经常返回 null，于是抛出 `RuntimeException("processName = null")`，引擎一直 `ready = false`。

- 只改 Bcore 的 `getProcessName`，改为调用 `ProcessNameCompat.get`。优先 `Application.getProcessName()`（API 28+），其次 `/proc/self/cmdline`，最后才是 ActivityManager。补丁脚本：`android/tools/PatchBlackBoxProcessName.java`。说明：`android/app/libs/BCORE_PATCH.txt`。
- `VirtualEngine.retry`：首页「重新初始化」会再试一次。引擎没起来时，首页显示具体错误。
- 给 DaemonService 补上 specialUse 子类型，避免 Android 14+ 杀掉 `:black`。

工作资料仍然只是回退。没有加入指纹伪装、计算器伪装或隐藏最近任务。
