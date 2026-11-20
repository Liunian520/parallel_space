# 分身通知页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在设置里增加「分身通知」页，只显示宿主自己仍活跃且 tag 能解析成 `ae1` 的通知，点一行只打开对应虚拟分身。

**Architecture:** `CloneNotificationPolicy.parse` 与 `CloneNotificationInbox` 是无 Android 依赖的决策。Kotlin 只调用 `getActiveNotifications()` / `cancel(tag, id)`，把标题和正文收成最小字段。Flutter 用当前分身名单决定名字和能否打开，并直接调用 `launchVirtual(pkg, userId)`。

**Tech Stack:** Java `main()`、Kotlin、Flutter。

用户已批准规格并指定在本会话按测试驱动实现，不再选择执行方式。继续 PR #8 的分支 `cursor/clone-notifications-design-0150`。

## Global Constraints

- 规格 `docs/superpowers/specs/2026-11-20-clone-notifications-design.md`。基准 `main` `da50875f408bf5290b4e660f2c9641b54d35ea68`，当前 1.3.41。实现版本 `1.3.42+12342`。不改 `update_manifest`，不打包，不上传，不合并，不 force push。
- 列表只来自 `NotificationManager.getActiveNotifications()`。索引文件不决定「还在不在」。
- 不添加 `NotificationListenerService`。不持久化标题、正文、ticker、extras。日志不写这些字段。
- 点一行只 `launchVirtual(解析出的包名, 解析出的 userId)`。不 `send` `contentIntent`，不走 `openPackage`，不回退工作资料。
- 单条清除前重新解析 tag、`belongsTo`，并且这次活跃列表里仍有同一个 tag 和 id。否则不 `cancel`。禁止 `cancelAll`。
- 对不上当前分身名单：文案 `当前没有这个分身`，无打开，可单条清除，不自动清。
- 页顶始终有 `工作资料里的应用通知不在此列表。` 空列表另有 `现在没有正在显示的虚拟分身通知。` 服务失败用 `暂时读不到通知`，不说成空列表。
- 分组摘要仍是一行，并标 `分组摘要`。
- userId 复用残留风险不改投递格式，不宣称已解决。
- 不改默认分身、密码本、权限状态页、备份、登录隔离、图标。

## 文件

- 修改 `CloneNotificationPolicy.java`：公开 `parse`。
- 新建 `CloneNotificationInbox.java`：过滤、排序、去重、清除决策。
- 新建 `CloneNotificationInboxTest.java`。
- 新建 `lib/services/clone_notification_inbox.dart`：同一套展示规则与刷新代际。
- 新建 `lib/screens/clone_notifications_screen.dart`。
- 修改 `settings_screen.dart`、`native_bridge.dart`、`MainActivity.kt`、`fake_native.dart`。
- 新建 `CloneNotificationReader.kt`：只读系统通知并单条清除。
- 测试 `test/clone_notifications_test.dart`。
- 版本：`pubspec.yaml`、`app_version.dart`、`README.md`、`CHANGELOG.md`、`vault_test.dart` 的版本断言。

## 接口

```java
public static Parsed parse(String tag) // null = 丢弃
public static List<Row> present(List<Active> active, List<Known> clones)
public static boolean allowCancel(String tag, int id, List<Active> activeNow)
```

`Parsed` 含 `pkg`、`userId`、`appId`、`appTag`、`uid`。userId 只能是十进制数字且 `>= 0`。应用 id 必须是整数。包名空则 null。

`present` 丢掉解析失败的项。同一个 tag+id 只留 `postTime` 更大的一条。对上 `pkg+userId` 才 `canOpen`，名字用传入的自定义名；否则名字是 `当前没有这个分身` 且 `canOpen=false`。标题和正文都空白时，展示标题为 `这条通知没有标题`。排序为 postTime 降序、tag、id。

```dart
CloneNoteParsed? parseCloneNotificationTag(String tag)
List<CloneNoteRow> presentCloneNotifications(List<CloneNoteActive> active, List<CloneNoteKnown> clones)
class RefreshGate { int begin(); bool accept(int token); }
```

Dart 与 Java 使用同一组 tag 夹具。

通道 `cloneNotifications` 成功时 `{ok: true, items: [...]}`，失败时 `{ok: false, error: unavailable}`。`cloneNotificationCancel` 返回 `ok` | `rejected` | `unavailable`。

## 任务

### Task 1: Java 解析与决策

写失败的 `main` 测试：正常 tag、userId 0/1 的 uid、分隔符留在应用 tag 里、负数和非法 userId、空包名、非 ae1、同 tag+id 去重、同包两份分身、孤儿不可打开、摘要保留、索引不参与、`allowCancel` 在活跃列表没有该 id 时为 false。`javac` 先失败，再实现。不提供 `cancelAll`。

### Task 2: Dart 展示与代际

用同一夹具写失败测试，外加 `RefreshGate`：旧 token 不接受。`shouldLaunchCloneNotification` 要求解析结果、名单里的 uid、包名和 userId 四点一致。

### Task 3: 页面、通道与 Kotlin

Widget 先失败再实现。设置能进入页面。空列表与工作资料两句同时在。服务失败只显示「暂时读不到通知」和工作资料说明。点打开只产生 `launchVirtual`，参数为该包名和 userId，没有 `launchWorkApp`。孤儿没有打开、有清除。清除参数是该行 tag 和 id。先完成的旧刷新不盖住后一次结果。摘要行有「分组摘要」。

Kotlin 只映射 tag、id、postTime、标题、正文、是否分组摘要。异常返回 `unavailable`，不把异常文本交回。`cancel` 先 `allowCancel`，成功后 `CloneNotificationIndex.forget` 这一条。不调用 `cancelAll`，不 `send` `contentIntent`。页面用 `BlobBackground` 和 `Glass`。

### Task 4: 1.3.42

版本测试先改成 1.3.42 / 12342 并确认失败，再同步四处文档。CHANGELOG 写明不改 `update_manifest`、云端不能把 Kotlin 说成已编译、HyperOS 未验收、userId 复用风险仍在。跑 `flutter analyze`、`flutter test` 和全部 Java `main`。没有 Android SDK 时不跑通知公开 API 检查。

## 规格对照

| 规格 | 任务 |
|---|---|
| 只读活跃的 ae1 通知 | Task 1、3 |
| 索引不伪装活跃 | Task 1 不把索引传入 `present` |
| 点一行只 launchVirtual | Task 2、3 |
| 单条 belongsTo cancel，无 cancelAll | Task 1、3 |
| 孤儿只清除 | Task 1、2、3 |
| 工作资料说明与空列表 | Task 3 |
| 分组摘要 | Task 1、3 |
| 代际与服务失败 | Task 2、3 |
| 不改投递、默认、密码本、权限页、备份、图标 | 不改那些文件 |
| 1.3.42 | Task 4 |

## 自审

- 打开不走 `openClone`，避免工作资料回退；也不走 `openPackage`，避免默认分身。
- 清除在 Kotlin 里再次读活跃列表，不信任页面上旧的「可清除」标记。
- 畸形 tag 在 Java 和 Dart 都丢掉，不会落到「当前没有这个分身」。
- 没有 TBD。
