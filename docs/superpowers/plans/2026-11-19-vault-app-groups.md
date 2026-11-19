# 密码本按应用分段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 悬浮密码本把全部可见账号按包名分成不可点击的段，主应用搜索补上应用名和分身自定义名，版本记为 1.3.40+12340。

**Architecture:** 分段、标题和搜索都是无 I/O 的纯函数。Dart `vault_group.dart` 与 Java `VaultAppHeading` 用同一套夹具。Kotlin 只读包管理器，把异常收成 `unavailable`，悬浮标题晚到时只 `setText`。主应用用代际丢掉过期的应用名响应，过滤时读当时的查询词。

**Tech Stack:** Dart / Flutter 测试、Java `main()`（不依赖 Android SDK）、Kotlin 通道与悬浮窗。

用户已批准规格并指定在本会话按测试驱动实现，不再选择执行方式。继续 PR #6 的分支 `cursor/vault-app-groups-design-0150`。

## Global Constraints

- 规格 `docs/superpowers/specs/2026-11-19-vault-app-groups-design.md`，基准 `main` `5107659d214e05f09b102f1caea01541e81e7498`，当前版本 1.3.39+12339。实现版本 `1.3.40+12340`。不改 `update_manifest`，不打包，不上传，不 force push，不合并。
- 悬浮列表展示 `VaultStore.list` 的全部可见记录，每条一次。按 `pkg` 分段。标题不可点击、不折叠、不筛选。没有记录的包名不生成标题。
- 段顺序：前台包名（悬浮窗现有 `pkg`，且该段有记录）排最前，其余按包名 UTF-16 码元升序。空包名若出现，单独排在所有非空包名之后，标题全文 `应用信息不可用`，不删除。
- 段内：现有 `matchesInstance` 为真的在前，然后 `label` 的 UTF-16 码元，然后 `id`。不用显示名，不用 `remarkTitle`。
- 标题：`pending` → 包名；非空 `named` → 名称；空白 `named` 与 `unavailable` → `包名 · 应用信息不可用`（U+00B7，两侧各一空格）；`reliablyUninstalled` → `包名 · 未安装`。生产路径不构造最后一种。`NameNotFoundException` 和其他异常都是 `unavailable`。
- 补名写入内存备忘并 `setText` 到 `tag == 包名` 的标题。不调用 `requestRebuild`、`rebuild`、`refreshVaultAsync`、`invalidateCache`。不改 `appLabelCached`、`registerVaultListener`、`startVaultFileObserver`、`refreshVaultAsync`、`matchesInstance`。
- 列表说明保持 `全部 N 个账号 · 点备注名查看 / 填入（不限当前分身）`。空列表、搜索无命中、详情缺失三句文案不改。
- 详情仍用 UUID `vaultDetailId`。`entryRow`、复制按钮、`performFill` 不改。窗口继续 `FLAG_NOT_FOCUSABLE`。悬浮路径不加 `EditText` / `SearchView`。
- 主应用继续 `groupVaultEntries`。搜索保留 label、user、pkg、note、clone，另加同包名的 `appName`、异步应用名、以及 `vaultEntryMatchesInstance` 对得上的 `customName`。不读取 `password`。查询词不进磁盘、备份、Intent 或日志。
- 代际不一致则整表丢弃。应用时用当前 `_query`，不使用发起时的查询词。键入不增加代际。
- 不改 `VaultStore.kt`、`clone_detail_screen.dart` 的密码本入口、配置备份 v3、`.aebak`、图标、无障碍常驻、权限页、`launchKey`、隔离。
- 云端无 Android SDK 时不得写成 Kotlin 已编译。HyperOS 不得写成已验证。

## 文件

- 修改 `lib/services/vault_group.dart`：标题、分段、搜索可选名称、代际门闩。
- 修改 `lib/screens/vault_screen.dart`：把名称表传入搜索；持有代际和异步表。分组调用仍是 `groupVaultEntries`。
- 修改 `lib/services/native_bridge.dart`：只增加 `vaultAppLabels`。
- 修改 `test/fake_native.dart`：该通道返回空 labels，避免页面测试落到未知方法。
- 新建 `android/app/src/main/java/com/parallelspace/parallel_space/VaultAppHeading.java`。
- 新建 `android/app/src/test/java/com/parallelspace/parallel_space/VaultAppHeadingTest.java`，入口 `main`。
- 修改 `ToolboxController.kt`：通道只读包管理器，不写日志。
- 修改 `ToolboxOverlayService.kt` 的 `buildVault` 与新增的标题查找。不改详情和复制行。
- 新建 `test/vault_app_group_test.dart`、`test/vault_source_guard_test.dart`。
- 版本：`pubspec.yaml`、`lib/config/app_version.dart`、`README.md`、`CHANGELOG.md`、`test/vault_test.dart` 的版本断言。

## 接口

```dart
enum VaultAppKind { pending, named, unavailable, reliablyUninstalled }

String vaultAppHeading(String pkg, VaultAppKind kind, [String label = ''])

class VaultAppRow {
  const VaultAppRow({required this.id, required this.pkg, required this.label, required this.currentClone});
  final String id, pkg, label;
  final bool currentClone;
}

class VaultAppSection {
  const VaultAppSection({required this.pkg, required this.rows});
  final String pkg;
  final List<VaultAppRow> rows;
}

List<VaultAppSection> groupVaultByPackage(List<VaultAppRow> rows, String foregroundPkg)

List<VaultEntry> filterVaultEntries(
  List<VaultEntry> entries,
  String query, {
  Map<String, List<String>> syncAppNames = const {},
  Map<String, String> asyncAppLabels = const {},
  Map<String, String> cloneNames = const {},
})

class VaultLabelApply {
  const VaultLabelApply({required this.names, required this.query});
  final Map<String, String> names;
  final String query;
}

VaultLabelApply? acceptVaultLabelResponse({
  required int responseGen,
  required int currentGen,
  required Map<String, String> names,
  required String queryNow,
})
```

```java
public enum Kind { PENDING, NAMED, UNAVAILABLE, RELIABLY_UNINSTALLED }
public static String heading(String pkg, Kind kind, String label)
public static Kind kindFromLookup(boolean found, String label, boolean nameNotFound, boolean failed)
public static List<Section> sections(List<Row> rows, String foregroundPkg)
```

`kindFromLookup` 只返回 `PENDING`、`NAMED`、`UNAVAILABLE`。`failed || nameNotFound` → `UNAVAILABLE`；未找到且未失败 → `PENDING`；找到但名称为空白 → `UNAVAILABLE`；否则 `NAMED`。

通道 `vaultAppLabels` 入参 `{"pkgs": [String]}`。出参 `{"labels": {pkg: {"state": "named"|"unavailable", "label": String}}}`。没有 `uninstalled`。不接收查询词。

---

### Task 1: Dart 纯函数

**Files:**
- Modify: `lib/services/vault_group.dart`
- Test: `test/vault_app_group_test.dart`

**Interfaces:**
- Consumes: 现有 `VaultEntry`、`vaultEntryMatchesInstance`
- Produces: 上一节的 Dart 签名

- [ ] **Step 1: 写失败测试**

夹具与规格一致。前台 `com.z` 时期望段 `com.z`、`com.a`、`com.m`，`com.a` 内 `a`、`d`、`c`。前台空且布尔值全否时，段 `com.a`、`com.m`、`com.z`，`com.a` 内 `d`、`a`、`c`。空包名排最后，标题 `应用信息不可用`。五种标题全文与规格表一致，间隔点是 U+00B7。搜索：`alice`、`宿舍`、`com.tencent`、`w:com.tencent.mm#1`、`主号`、`微信`、`小号` 命中；`大号`、`p@ss-unique`、`应用信息不可用`、`未安装` 不命中。空查询保留原顺序。`clone` 为空的同包名旧记录能被 `大号` 命中、不能被 `小号` 命中。代际 1 在当前代际 2 时返回 null；代际 2 返回的 `query` 等于 `queryNow`。

- [ ] **Step 2: 运行并确认失败**

Run: `flutter test test/vault_app_group_test.dart`
Expected: 编译失败，函数未定义。

- [ ] **Step 3: 实现纯函数**

`groupVaultByPackage` 用 `LinkedHashMap` 按输入顺序收集，不丢行。段排序键：空包名 2，等于非空前台包名 0，其余 1；同档 `compareTo`。段内当前分身 0 否则 1，再 `label.compareTo`，再 `id.compareTo`。`filterVaultEntries` 在原五个字段之后检查三个可选表，函数体不出现 `.password`。`acceptVaultLabelResponse` 只在代际相等时返回，查询词用 `queryNow`。

- [ ] **Step 4: 测试通过，并跑现有密码本测试**

Run: `flutter test test/vault_app_group_test.dart test/vault_test.dart test/multi_instance_test.dart`
Expected: 全部通过。现有「按分身分组」测试仍在。

- [ ] **Step 5: Commit**

`test: 锁定密码本按包名分段和搜索名称`

### Task 2: Java 纯函数

**Files:**
- Create: `android/app/src/main/java/com/parallelspace/parallel_space/VaultAppHeading.java`
- Test: `android/app/src/test/java/com/parallelspace/parallel_space/VaultAppHeadingTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `heading`、`kindFromLookup`、`sections`、`Row`、`Section`

- [ ] **Step 1: 写 `main` 测试，断言与 Task 1 同一夹具**
- [ ] **Step 2: `javac` 失败，因为类不存在**
- [ ] **Step 3: 实现 Java。不 import `android.*`。`heading` 里「未安装」只出现在 `RELIABLY_UNINSTALLED` 分支**
- [ ] **Step 4: 编译并运行 `main`，打印 `VaultAppHeadingTest ok`**

```bash
javac -encoding UTF-8 -d /tmp/vault-heading \
  android/app/src/main/java/com/parallelspace/parallel_space/VaultAppHeading.java \
  android/app/src/test/java/com/parallelspace/parallel_space/VaultAppHeadingTest.java
java -cp /tmp/vault-heading com.parallelspace.parallel_space.VaultAppHeadingTest
```

- [ ] **Step 5: Commit** `test: 用 Java main 锁定悬浮分段顺序`

### Task 3: 只读应用名通道

**Files:**
- Modify: `lib/services/native_bridge.dart`
- Modify: `android/app/src/main/kotlin/com/parallelspace/parallel_space/ToolboxController.kt`
- Modify: `test/fake_native.dart`

**Interfaces:**
- Consumes: `VaultAppHeading.kindFromLookup`、`PackageManager.getApplicationInfo`、`getApplicationLabel`
- Produces: `NativeBridge.vaultAppLabels(List<String>) → Future<Map<String, String>>`，只收录 `state == named` 且名称非空的项

- [ ] **Step 1: Kotlin 方法 `vaultAppLabels` 放在 `ToolboxController`，`handle` 增加同名分支。`pkgs` 用 `call.argument<List<*>>`。每个包名 `try` 公开的 `getApplicationInfo(pkg, 0)` 与 `getApplicationLabel`。`catch (e: PackageManager.NameNotFoundException)` 与 `catch (t: Throwable)` 都交给 `kindFromLookup`，不写 `ToolboxCrashLog`，不记录查询词。不引用 `isHarmony` 或任何 private 成员**
- [ ] **Step 2: Dart 解析出参。`PlatformException` 已由 `_call` 变成 null。本方法把 null 收成空 Map，不把异常文本拼进返回值**
- [ ] **Step 3: FakeNative 返回 `{'labels': {}}`**
- [ ] **Step 4: Commit** `feat: 只读解析应用显示名`

通道实现没有单独的 Dart 失败测试能覆盖 Kotlin。源码守卫在 Task 6 锁住「不写未安装、不写日志」。云端不把这一步说成 Kotlin 已编译。

### Task 4: 主应用搜索接线

**Files:**
- Modify: `lib/screens/vault_screen.dart`

**Interfaces:**
- Consumes: `filterVaultEntries`、`acceptVaultLabelResponse`、`NativeBridge.vaultAppLabels`、`CloneStore`
- Produces: 页面内 `_labelGen`、`_asyncLabels`、`_labelKey`

- [ ] **Step 1: `_reload` 成功后，若非空包名集合变化，则 `++_labelGen` 并请求通道。返回时若 `!mounted` 或代际不等，直接 return。否则 `acceptVaultLabelResponse(..., queryNow: _query)`，只把 `names` 放进 `_asyncLabels`。键入只改 `_query`**
- [ ] **Step 2: `build` 仍调用 `groupVaultEntries`。`filterVaultEntries` 额外传入：同包名 `appName` 列表、`_asyncLabels`、`uid → customName`**
- [ ] **Step 3: 搜索提示改为 `搜索备注名、账号、应用或分身…`。不把查询词写入 `debugPrint`、`print` 或 SharedPreferences。通道失败时不改 `_error`**
- [ ] **Step 4: 跑 `flutter test test/vault_test.dart test/vault_app_group_test.dart`**
- [ ] **Step 5: Commit** `feat: 主应用密码本搜索应用名和分身名`

### Task 5: 悬浮列表分段

**Files:**
- Modify: `ToolboxOverlayService.kt` 的 `buildVault`，并在同一类新增私有的标题备忘与查找

**Interfaces:**
- Consumes: `VaultAppHeading.sections`、`heading`、`kindFromLookup`、现有 `matchesInstance`、`remarkRow`、`section`、`emptyVault`
- Produces: `headingMemo`、`resolveSectionHeading`

- [ ] **Step 1: 替换 `当前分身` / `其他分身` / `全部账号` 这三段循环。用 `VaultAppHeading.Row(e.id, e.pkg, e.label, matchesInstance(e))` 调用 `sections(rows, pkg)`。每段一个 `section(标题)`，`tag = section.pkg`，不设置点击和 `setFocusable(true)`。段内按返回顺序 `remarkRow`**
- [ ] **Step 2: 备忘未命中时标题用 `PENDING`。空包名直接写入 `UNAVAILABLE` 的全文，不发起查找。其他包名在后台查找，完成后写 `headingMemo` 并 `card?.findViewWithTag<TextView>(pkg)?.text = text`。这条路径不调用 `requestRebuild`**
- [ ] **Step 3: 上方说明句、`emptyVault`、`buildVaultDetail`、`entryRow` 保持原文**
- [ ] **Step 4: Commit** `feat: 悬浮密码本按应用分段`

### Task 6: 源码守卫

**Files:**
- Test: `test/vault_source_guard_test.dart`

- [ ] **Step 1: 写测试，读仓库文件**

`ToolboxOverlayService.kt` 含 `FLAG_NOT_FOCUSABLE`、`VaultAppHeading.sections`、`matchesInstance` 的两段现有表达式，且 `requestRebuild("label_memo")` 只出现一次。该文件不含 `EditText`、`SearchView`、`未安装`、`reliablyUninstalled`、`VaultStore.persist`、`VaultStore.upsert`。`android/app/src/main/kotlin` 不含 `未安装` 与 `reliablyUninstalled`。`VaultAppHeading.java` 的「未安装」只在 `RELIABLY_UNINSTALLED` 分支。`filterVaultEntries` 函数体不含 `.password` 与 `['pass']`。`clone_detail_screen.dart` 仍是 `openVaultScreen(context, initialPackage: c.packageName, openEditor: true)`。`VaultStore.kt` 的 SHA-256 等于实现开始时的值。`BackupCodec.settingKeys` 仍正好是 `title theme dyn seed fx updAuto toolbox`。

- [ ] **Step 2: `flutter test test/vault_source_guard_test.dart` 通过**
- [ ] **Step 3: Commit** `test: 守卫悬浮窗无输入且密码本不写回`

### Task 7: 1.3.40 文档

**Files:**
- Modify: `test/vault_test.dart`、`lib/config/app_version.dart`、`pubspec.yaml`、`README.md`、`CHANGELOG.md`

- [ ] **Step 1: 先把版本测试改成 1.3.40 / 12340，并断言大于 12339。跑测试，确认红在旧常量**
- [ ] **Step 2: 同步四处版本。README 徽章、当前版本句、下一次版本句。历史句「1.3.39 起机型适配」保留。CHANGELOG 顶部改当前版本，新增 1.3.40 条目：悬浮按应用分段、主应用搜索补名称、不写密码本、不改 `update_manifest`、云端未编译 Kotlin、HyperOS 未验证、打包机尚未出 APK**
- [ ] **Step 3: `flutter test` 全量。记录通过数量**
- [ ] **Step 4: Commit** `chore: 版本 1.3.40+12340`

## 规格对照

| 规格要求 | 任务 |
|---|---|
| 全部可见账号按 pkg 一段一条 | Task 1、2、5 |
| 标题不可点击、不折叠、不筛选 | Task 5、6 |
| 当前应用段优先，其余包名稳定 | Task 1、2 |
| 段内当前分身 / label / id | Task 1、2、5 |
| 异步补名不重排、不新增重建源 | Task 5、6 |
| 空 clone、缺失名称仍保留；空 pkg 最后 | Task 1、2 |
| NameNotFound 不是未安装 | Task 2、3、5、6 |
| 主应用仍按分身分组 | Task 4，现有 `vault_test` |
| 搜索补应用名和自定义名，原五字段仍在，不匹配密码 | Task 1、4 |
| 查询词不落盘、不进日志、不进异常文本 | Task 3、4、6 |
| 旧名称响应不覆盖；用当前查询重算 | Task 1、4 |
| JSON v2 零写回，未知字段不因本功能丢失 | 不改 `VaultStore.kt`，Task 6 |
| 不改配置 v3 / .aebak / 详情入口 / 复制 / 填入 / 焦点标志 | Task 5、6 |
| 1.3.40，不改 update_manifest | Task 7 |
| Kotlin 编译与 HyperOS 不谎称 | Task 7 的 CHANGELOG |

## 自审

- 夹具用 `com.z` 当前段，避免和码元顺序重合。空前台另有 `d、a、c`。
- `乙` U+4E59 在 `甲` U+7532 之前，计划没有改成拼音。
- `kindFromLookup` 没有通向 `RELIABLY_UNINSTALLED` 的参数。界面源码不写「未安装」。
- 分段标题不调用 `appLabelCached`，因此不增加 `label_memo` 重建。
- 过滤函数用可选参数，旧的两参调用保持可用。
- 没有 TBD。Kotlin 步骤写了公开 SDK 方法和异常类型，没有 private 跨类访问。
