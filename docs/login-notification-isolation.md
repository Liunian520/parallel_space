# 登录隔离与通知隔离（1.3.35）

已按批准范围把两件事做在同一版里。本文是实现所依据的设计，不是对外宣传。

## 结论

- 设备标识按分身 `(包名, userId)` 单独保存，默认关闭。老分身继续读到本机真实 Android ID，不会在升级时被悄悄换成新号。
- 打开、关闭或更换之后，分身进程里的 Settings 缓存不会自己刷新，需要重新打开该分身；应用可能要求重新登录。这不能绕过应用服务器自己的账号、设备或风控限制。
- 通知在宿主 UID 下投递。用分身专属的 tag、id、渠道 id 区分，`cancel` / `cancelAll` 只动这个分身投过的通知，不动其他分身，也不动 Alter Ego 自己的通知。不做成统一通知收件箱。
- 状态栏里尽量显示分身自定义名（没有自定义名时用「应用名 分身N」）。点击仍走原来的 PendingIntent。

## 设备标识：实际走到的代码

`AndroidIdProxy`、`ISettingsProviderProxy`、`ISettingsSystemProxy`、`IXiaomiSettingsProxy` 都在 `HookManager.init` 里注册了，但 `getWho()` 返回 null，`ClassInvocationStub.injectHook` 会直接返回，注解上的 hook 不会挂上。`ContentResolverProxy` 虽然拿得到 ContentResolver，`inject()` 是空的，代理没有装回系统。

分身读 `Settings.Secure.ANDROID_ID` 时，真正经过的是 `ContentProviderDelegate`：它把 `settings` 的 `IContentProvider` 包进 `SystemProviderStub`（已缓存的 provider 再被 `ContentProviderStub` 包一层）。这两处 `invoke` 现在把 `call` / `query` 原样转给系统，所以读到的是宿主那一份 Android ID。

补丁只改这两个 `invoke`：先走原来的方法，再调用 `CloneIdentityCompat.rewrite`。仅当当前是分身进程、该实例已打开隔离、并且这次调用是 settings 的 `GET_secure` / `GET_system` / `GET_global` 或 settings 查询时，才把结果里的 `android_id` 换成已保存的 16 位小写十六进制。关着的时候原样返回，包括空串和 `0`，不再生成一次性随机数。

标识写在宿主 `files/clone_identity.json`，键为 `包名#userId`。原子替换文件，读端按修改时间缓存。同一应用的分身进程和宿主界面进程都能读（同一个 UID）。关闭隔离只把 `enabled` 设为 false，标识留着，再次打开还是原来的值。只有用户点「更换标识」才换新值。

## 备份

`.aebak` 仍是版本 1。`meta.json` 里多一个可选的 `identity`。旧版 Alter Ego 不认识这个字段，仍能恢复应用数据。没有 `identity` 的旧备份：

- 覆盖：该分身改回使用本机真实标识（`enabled=false`）。文件里已经生成过的标识保留，不删除。
- 新建：不写标识记录。

备份里带了标识时：

- 覆盖，或本机还没有这个分身：沿用备份里的开关和标识。
- 本机已有、用户选择再新建一份：原分身不动；新分身若备份里开着隔离，另生成一个不同的标识，避免两份分身共用同一个隔离标识。

标识在数据恢复成功之后才写入。写入失败则保留原来的文件，并在结果里带上警告，不把已经恢复的数据再删掉。

## 通知：实际走到的代码

`INotificationManagerProxy` 会替换 `NotificationManager.sService`，这条路径是通的。`enqueueNotificationWithTag` 和 `cancelNotificationWithTag` 进了 `BNotificationManagerService`，但：

- 系统 id 只用包名、userId、应用 id 做哈希，tag 被丢掉，同 id 不同 tag 会撞车。
- 渠道后缀只有 userId，同一 user 下不同应用的同名渠道会撞车。
- `notify` 不带 tag。
- `cancelAllNotifications` 没有 hook，会落到宿主包名上，清掉所有克隆和 Alter Ego 自己的通知。
- 状态栏应用名是宿主名。

补丁在分身进程里改这些 hook：改写 tag、id、渠道、分组和展示名之后，再调用原来的系统服务对象（`hook` 的第一个参数就是真实服务），避免 `NotificationManager.notify` 再进代理造成递归。`invoke` 里拦截 `cancelAll*`，只取消本分身记录过的通知。`setNotificationsEnabledForPackage` 不转发给系统，避免一个分身关掉宿主的总开关。

系统 tag 形如 `ae1 + 分隔符 + userId + 包名 + 应用 id + 原 tag`。系统 id 落在高位区间，并带上 tag。渠道 id 在原 id 后加 `@ae1-userId:包名`，读回时再剥掉，渠道名称、重要性、声音不改。公开 SDK 的 `Notification` 没有 `setChannelId` / `setGroup`，改渠道和分组走 `Notification.Builder.recoverBuilder`，渠道对象用公开构造函数复制。投递记录写在 `files/clone_notification_index.json`，重启后 `cancel` / `cancelAll` 仍只命中自己的记录。

PendingIntent、动作按钮不改写。所有通知仍由宿主 UID 发出，不伪造成另一个 Linux uid。

## 界面

分身详情增加「登录隔离」：开关、当前标识、更换。文案说明需要重新打开分身，以及可能要重新登录，并写明不能绕过服务器限制。通知隔离没有单独开关，也不做收件箱。

## 不在本版

- 不改相机 AttributionSource 的 -1 修复、边衬、最近任务标题、桌面图标、只读密码工具箱。
- 不提供统一通知列表。
- 不承诺多开账号一定成功。
