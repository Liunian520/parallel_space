# 平行空间 · 应用分身（Flutter）

玻璃拟态 + Material You 风格的“应用分身”App。

**v1.3.3 主路径**：进程内虚拟引擎（NewBlackbox / Bcore，Apache-2.0）创建/启动/删除分身；工作资料只是回退。分身图标取个人空间里的原图标，桌面快捷方式直接启动虚拟分身。见 `CHANGELOG.md`。

**应用内更新**：设置 → 检查更新 / 自动检查（≤12h）；清单短链 `https://link.liunianai.cn/n8afhu`（Link 盾 HTML 时 WebView 抽取 JSON）；`versionCode` 方案 1.2.4→10204、1.3.0→10300；APK 走清单 `apkUrl` 直链。

## 运行
```bash
# 需要 Flutter 3.27+（开发验证于 Flutter 3.47.8 / Dart 3.13）
flutter pub get
flutter run            # 连接 Android/iOS 设备或模拟器
flutter analyze        # 静态检查（0 issues）
flutter test           # 冒烟测试
```

## 功能
- **我的分身**：渐变标题（双击/长按可自定义）、2 列毛玻璃卡片网格、隐藏菜单（打开/删除）、长按拖拽排序、增删缩放淡入淡出动画、空状态动画插画、扩展式 FAB。
- **添加分身**：固定搜索栏实时过滤、按分类分组、弹性动画 Bottom Sheet 与“创建分身”。
- **分身详情**：改名、换图标（颜色 + Emoji）、指纹/密码隐私锁、清除数据、卸载分身。
- **设置**：通知管理、主题（跟随系统/浅色/深色 + 动态取色/种子色）、关于。
- **动效**：Hero 共享元素（卡片图标 → 详情页 / 启动页）、Cupertino 右滑返回、动态 Blob 模糊背景、全局缩放 + 涟漪点击反馈。

## 新增：桌面快捷方式
- 在分身详情页或卡片菜单中点击 **添加到桌面**：Flutter 用 `PictureRecorder` 绘制带颜色/Emoji/分身编号的图标 PNG，通过 MethodChannel `parallel_space/native` 交给 `MainActivity.kt`，调用 `ShortcutManagerCompat.requestPinShortcut` 在系统桌面固定快捷方式（名称为分身名 + 编号）。
- 点击桌面图标会以 `clone_id` 启动 MainActivity；冷启动通过 `getLaunchCloneId` 读取，后台时通过 `onNewIntent → openClone` 回调，直接进入对应分身的启动页。
- 分身与设置使用 `shared_preferences` 持久化（首次运行写入 Mock 默认数据），重启后快捷方式仍可解析；分身被卸载后点击快捷方式会提示已失效。
- 启动器不支持固定快捷方式时会弹出提示。

## 新增：虚拟定位（系统全局）
- 分身详情 → **虚拟定位**：开关、预设城市（北京天安门、上海外滩、深圳、杭州西湖、成都、东京、纽约等）、手动输入经纬度、网格地图选点；首页卡片显示当前位置小标签。
- 点击 **开始模拟**：`MockLocationService`（前台服务 + 常驻通知）每秒通过 `LocationManager.addTestProvider / setTestProviderLocation` 推送 GPS 与 Network 位置；点击 **停止** 移除测试 Provider。
- ⚠️ Android 的模拟位置是 **整台设备生效** 的，无法只作用于某个分身；界面文案已明确说明。
- 首次使用需：设置 → 关于手机 → 连点版本号开启开发者选项 → **选择模拟位置信息应用 → 平行空间**。未设置时 App 会捕获 `SecurityException` 并弹出引导面板，可一键跳转开发者选项。

## v1.1 新功能

| 功能 | 真实 / 模拟 | 说明 |
|---|---|---|
| 真实分身（工作资料） | 真实 | `CloneAdminReceiver` + `ACTION_PROVISION_MANAGED_PROFILE` 创建工作资料；`LauncherApps` 列出并 `startMainActivity` 启动；Android 9+ 通过跨资料 `WorkOpActivity`（signature 权限保护）调用 `installExistingPackage` 复制个人空间应用。每台设备仅一个工作资料，部分 ROM 会屏蔽。 |
| 路线模拟 | 真实（系统全局） | 多点路线编辑（可缩放网格地图）+ 3 条预设路线；步行/骑行/驾车速度、循环/单程、按距离插值并带航向/速度、暂停/继续、进度面板。回放由 App 推进，前台服务持续推送最后坐标。 |
| 收藏位置 | 真实 | 保存/命名/删除，点按即应用到当前分身，持久化。 |
| 私密空间 | 真实 | PIN（加盐多轮 SHA-256）+ `local_auth` 生物识别；输错 5 次冷却 30 秒；App 进入后台或离开页面自动上锁；私密分身不出现在主屏/小组件/桌面快捷方式。 |
| 通知角标 / 免打扰 | 角标：模拟；免打扰：App 内标记 | 模拟分身显示模拟未读数；真实应用的未读数 Android 不允许读取。全局 + 单分身免打扰时间窗（支持跨午夜）。 |
| 备份与迁移 | 真实 | 导出 JSON（SAF 保存 / FileProvider 分享），导入校验格式与版本，可合并或覆盖；**永不包含 PIN**。 |
| 桌面小组件 / 快捷开关 | 真实 | AppWidget 显示主屏前 4 个分身（与快捷方式同一深链）；Quick Settings Tile 开关上次的模拟位置。 |
| 省电 | 模拟分身：模拟；真实分身：真实 | 冻结 / 自动冻结（闲置 N 分钟）。真实分身经工作资料所有者 `setPackagesSuspended`。今日用量来自 `UsageStatsManager`（需授权，个人空间应用）。 |

## 测试
`flutter test` 覆盖：路线插值（循环、单程、跨 ±180°、退化输入）、免打扰时间窗、PIN 哈希与冷却、v1 旧数据迁移、损坏数据、备份编解码与校验、导入合并/覆盖、自动冻结、私密空间生命周期上锁、320×568 与 411×891 浅色/深色各页面无溢出。

## 构建 APK
```bash
flutter build apk --release --split-per-abi --target-platform android-arm64
```

## 目录
```
lib/
  main.dart                 # 入口、DynamicColorBuilder、主题模式
  models/                   # AppInfo、CloneApp
  data/mock_apps.dart       # 模拟已安装应用
  state/                    # Provider：CloneStore、SettingsStore
  theme/app_theme.dart      # M3 主题、品牌渐变
  widgets/                  # Glass、Pressable、BlobBackground、CloneCard、ReorderableCloneGrid…
  services/                 # NativeBridge(MethodChannel)、快捷方式图标绘制、通用动作
  screens/                  # RootShell、Home、AddClone、CloneDetail、Settings、Launch
```

Android 原生代码：`android/app/src/main/kotlin/com/parallelspace/parallel_space/`（`MainActivity.kt` 快捷方式与通道，`MockLocationService.kt` 虚拟定位前台服务）。


## 关于“分身”实现方式

平行空间的主路径是进程内虚拟化（NewBlackbox / Bcore，Apache-2.0），不要求工作资料，也不做指纹伪装、计算器伪装或隐藏最近任务。工作资料只作为遗留回退。
在 HyperOS / 澎湃 OS 上，系统「应用双开」不是工作资料；跨空间授权经常不可用，因此不能再把工作资料当成启动条件。
