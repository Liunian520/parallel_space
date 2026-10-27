# 更新记录

版本号只递增最后一位。当前是 1.3.4（versionCode 10304），下一次是 1.3.5（10305）。每次改版本，同步更新 `pubspec.yaml`、`lib/config/app_version.dart`、`README.md` 顶部徽章和本文件。

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
