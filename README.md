# 平行空间

[![version](https://img.shields.io/badge/version-1.3.9-2F7CF6)](https://github.com/Liunian520/parallel_space/blob/main/CHANGELOG.md)
[![Flutter](https://img.shields.io/badge/Flutter-3.27%2B-02569B?logo=flutter&logoColor=white)](https://flutter.dev)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![license](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

在本机再开一份应用。界面是毛玻璃和 Material You，文字是简体中文。

平行空间用进程内虚拟引擎（NewBlackbox / Bcore）创建和启动分身。HyperOS 上工作资料经常不可用，所以虚拟引擎是唯一主路径，工作资料只作为遗留回退。

## 功能特性

| | 功能 | 说明 |
| --- | --- | --- |
| 📦 | 应用分身 | 把已安装的应用装进虚拟空间，打开、删除、排序 |
| ⚙️ | 虚拟引擎 | NewBlackbox / Bcore，进程内运行，不要求 Root |
| 🎨 | 真实图标 | 分身卡片使用个人空间里的原图标 |
| 📌 | 桌面快捷方式 | 固定到桌面后直接启动虚拟分身 |
| 📍 | 虚拟定位 | 系统级模拟位置，前台服务持续推送，对整机生效 |
| 🧰 | 工具箱 | 分身在前台时出现可拖动、可收起的悬浮小窗 |
| 🔑 | 账号密码本 | 本机保存，按分身或包名分组；一点即可填入（无障碍）或复制到剪贴板；不设生物识别门禁 |
| 🔄 | 应用内更新 | 设置里检查更新；清单走 GitHub 直链，APK 走网盘永久直链 `/f/...`（无 Link 盾）；失败时可浏览器打开分享页 |
| 📱 | 机型适配 | 小米 / Redmi / POCO、华为、荣耀、OPPO / 一加 / realme、vivo / iQOO、三星、魅族和 Pixel 的权限页集中处理，设置里可以逐项查看 |

另外还有：私密空间（PIN / 生物识别）、备份与迁移（不含 PIN，也不含密码本）、桌面小组件、主题（跟随系统 / 动态取色）。

不做指纹伪装、计算器伪装，也不会把应用从最近任务里隐藏。

## 支持的机型与系统

| 机型 | 系统 | 适配重点 |
| --- | --- | --- |
| 小米 / Redmi / POCO | MIUI 12–14、HyperOS 1–3 | 自启动、电池无限制、悬浮窗、桌面快捷方式（AppOps 10017）、进程名补丁 |
| 华为 | EMUI、HarmonyOS 2–4 | 应用启动管理、悬浮窗。HarmonyOS NEXT（纯血鸿蒙）不能安装 Android 应用 |
| 荣耀 | MagicOS | 启动管理、悬浮窗、快捷方式 |
| OPPO / 一加 / realme | ColorOS、OxygenOS、realme UI | 自启动、悬浮窗、桌面快捷方式权限页 |
| vivo / iQOO | OriginOS、Funtouch | 后台弹出、自启动、悬浮窗、快捷方式 |
| 三星 | One UI | 电池不受限、安全文件夹与工作资料冲突时的说明 |
| 魅族 | Flyme | 悬浮窗、后台与权限页回退 |
| Google Pixel 及原生 Android | Android 8.0–16 | 标准悬浮窗、通知、电池优化页面 |

应用 `minSdk` 为 24（Android 8.0）。发布包只包含 **arm64-v8a**，32 位系统无法安装。

Android 14 及以上需要声明前台服务类型：虚拟定位、工具箱悬浮窗和虚拟引擎守护进程都使用 `specialUse`，并写了用途说明。

## 安装

1. 用下面的命令打出 release APK，或使用你信任的已签名安装包。
2. 在系统里允许安装未知来源应用。
3. 安装后打开「平行空间」。HyperOS 上如果提示引擎未就绪，到 **设置 → 机型适配检查** 看进程名和电池项。
4. 要用分身里的悬浮小窗：到 **设置 → 工具箱** 打开开关，并允许悬浮窗。一键填入需要再打开无障碍服务；不打开也可以，密码会复制到剪贴板。

## 构建

需要 Flutter 3.27 或更高版本（仓库在 Flutter 3.47 / Dart 3.13 上验证过）。

```bash
flutter pub get
flutter analyze
flutter test
flutter build apk --release --target-platform android-arm64
```

产物在 `build/app/outputs/flutter-apk/app-release.apk` 或 `app-arm64-v8a-release.apk`（`--split-per-abi`）。版本来自 `pubspec.yaml`，当前是 **1.3.9+12309**。

**签名**：release 使用本机 `~/.android/debug.keystore`（与历史安装包同一证书，SHA-256 以 `5F:63:37:…` 开头）。覆盖安装必须保持同一证书；换证书会导致「必须先卸载」。`android/app/build.gradle.kts` 里 `signingConfig = signingConfigs.getByName("debug")`。

`android/app/libs/bcore-release.aar` 带有进程名字节码补丁，说明见 `android/app/libs/BCORE_PATCH.txt`。`proguard-rules.pro` 必须保留 `blackbox`、`blackreflection`、`black.**` 和 `me.weishu`，否则发布包里虚拟引擎无法初始化。

## 版本号

只把修订号加 1：当前 `1.3.9`（versionCode `12309`），下一次是 `1.3.10`（`12310`）。

1.3.x 的 `versionCode = 12300 + 修订号`（例如 1.3.1→12301、1.3.9→12309）。这样始终高于早期误用的 `10303–10305`，也高于已装的 `12301/12302`，覆盖安装不会因 versionCode 下降而失败。应用内更新按 **versionCode 数值大小** 比较。

每次改版本，同时更新：

- `pubspec.yaml` 的 `version`
- `lib/config/app_version.dart`
- 本文件顶部的 version 徽章
- `CHANGELOG.md`

## 项目结构

```
update_manifest.txt   应用内更新清单（发版时由上传脚本覆盖；App 读 GitHub raw）
lib/
  config/           版本号、更新清单地址
  models/           分身、应用信息
  screens/          首页、分身详情、设置、工具箱、密码本、机型适配
  services/         原生通道、备份、更新
  state/            分身、设置、私密空间
  theme/            Material You
  widgets/          毛玻璃、分身卡片
android/app/src/main/kotlin/com/parallelspace/parallel_space/
  VirtualEngine.kt              虚拟引擎
  OemAdapter.kt                 各厂商跳转和适配检查
  ToolboxOverlayService.kt      悬浮小窗
  ToolboxAccessibilityService   一键填入
  VaultStore.kt / VaultCrypto   密码本（明文 SharedPreferences；VaultCrypto 仅迁移旧加密文件）
  ProcessNameCompat.java        HyperOS 进程名补丁入口
android/app/libs/               Bcore AAR 与 black-reflection
android/app/proguard-rules.pro  发布包保留虚拟引擎反射类
```

## 常见问题

**HyperOS 上一直显示引擎未就绪**

1.3.1 起，`BlackBoxCore.getProcessName` 改为调用 `ProcessNameCompat.get`：先用 `Application.getProcessName()`，再读 `/proc/self/cmdline`，最后才查 ActivityManager。发布包里如果缺少 `blackreflection` 或 `black.**`，初始化会变成 `NoClassDefFoundError`。不要删 ProGuard keep。

**添加桌面快捷方式失败**

小米的桌面快捷方式是 AppOps **10017**。到 **设置 → 机型适配检查 → 桌面快捷方式**，或 **桌面快捷方式权限**。vivo / OPPO 没有稳定的读取接口，需要自己在权限页里打开。打不开厂商页时会退回应用详情，再退回系统设置。

**悬浮窗不出现 / 分身还在小球却没了**

先在工具箱里打开开关。各厂商的悬浮窗页面不同，都会先试厂商页，失败再试系统的「显示在其他应用上层」。1.3.9 起会用 `/proc` 兜底识别 `:p0`、`:p1` …，并在打开分身后较长时间内保持气泡；若仍消失，到 **设置 → 工具箱诊断日志** 复制内容反馈。

**无障碍服务过一段时间自己关了**

小米、华为等系统会回收无障碍服务。关掉之后，填入会自动改成复制到剪贴板。若通知权限已开，会提醒一次。到机型适配检查里可以重新打开。

**工作资料创建失败**

HyperOS 的「应用双开」不是工作资料，本应用也不依赖它。引擎可用时，设置里不会要求创建工作资料。

**32 位手机**

发布包只有 arm64。机型适配检查里的「64 位」会标出来。

## 开源协议

[MIT License](LICENSE)。版权人：流年似水。

虚拟引擎 NewBlackbox / Bcore 为 Apache-2.0，随 AAR 一起使用。

## 作者

流年似水

开源地址：<https://github.com/Liunian520/parallel_space>

**覆盖安装提示「必须卸载」**

多半是旧包 versionCode（如 10305）高于你手里的包，或签名证书不一致。1.3.6/1.3.7 起 versionCode 固定为 `12300+修订号`，并始终用同一把 debug 证书签名。装 1.3.6 或更新版本之后即可正常覆盖更新。

**工具箱把整屏糊住、分身点不了**

1.3.5 及更早在悬浮窗上开了系统 `FLAG_BLUR_BEHIND`。1.3.6 已去掉；小窗只是半透明小卡片，窗外触摸会穿透到下层应用。1.3.7 进一步修了编辑时气泡被误拆、键盘弹出导致面板消失的问题，并改为 START_STICKY + 看门狗自动恢复。1.3.8 重做了气泡/面板视觉（贴边半隐、回弹吸附、Material You），并修好了悬浮窗权限跳转。1.3.9 修了 HyperOS 上进程列表空导致小球误摘，并加了诊断日志。
