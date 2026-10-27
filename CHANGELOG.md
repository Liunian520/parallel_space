# 更新记录

## 1.3.3+10303

### 原因

1.3.2 在 Redmi K80 上虚拟引擎已经能启动，但三件事还是按工作资料写的：

- 设置首页仍把「工作资料 / 尚未创建」放在分身空间第一项；添加分身的说明也还在讲复制到工作资料。
- `Shortcuts.pin` 在 `WorkProfile.ourUser == null` 时直接返回 `no_profile`。HyperOS 上没有可用工作资料，所以「添加到桌面」在权限检查之前就失败，权限页也不会打开。跳板 `ShortcutTrampolineActivity` 只调用 `WorkProfile.launch`，即使图标钉上了也不会走虚拟引擎。
- `AppIcon` 默认 `work: true`。分身网格、详情、私密空间都去 `LauncherApps` 要工作资料角标图标。没有工作资料时原生返回空，界面就停在彩色首字占位。

### 改动

- 引擎已就绪时，设置显示「虚拟分身引擎」，不再要求创建工作资料。系统里如果还留着早期的工作资料（ready / paused / foreign / inside），提供「删除残留工作资料」。引擎未就绪时，原来的工作资料回退入口还在。
- 虚拟引擎可用时可以固定桌面快捷方式，不要求工作资料。图标用个人空间里的原图标。点击快捷方式走 `VirtualEngine.launch`。HyperOS 的权限页多了一条不带 action 的权限编辑器，避免只落到应用详情。
- 分身图标默认 `work: false`，走 `PackageManager` / 个人空间 `LauncherApps`。小组件同样优先原图标。
- 版本 `1.3.3+10303`。1.3.1 的进程名补丁、1.3.2 的 blackreflection / `black.**` keep、错误页和 DaemonService specialUse 都还在。

### 验证

`flutter analyze` 无问题，`flutter test` 全部通过。发布包 `1.3.3` / `10303` 的 `classes.dex` 仍包含全部 21 个 `blackreflection` 类和 964 个 `black.**`，以及 `ProcessNameCompat`、`Reflection`、`JniHook` 和 `arm64-v8a/libblackbox.so`。

## 1.3.2+10302

### 原因

1.3.1 装到 Redmi K80 后，首页变成「虚拟分身引擎未就绪」，错误是：

`attach: NoClassDefFoundError: top.niunaijun.blackbox.BlackBoxCore`

发布包 `classes.dex` 里 **有** `BlackBoxCore`，也有 `ProcessNameCompat.get` 补丁。缺的是它在静态初始化里要用的反射层：

- `black-reflection.jar` 的 21 个类（`top.niunaijun.blackreflection.**`）在 dex 里一个都没有。这个 jar 没有 consumer ProGuard，R8 从应用调用图看它像是没人引用。
- AAR 里约 964 个 `black.**` 镜像接口同样被收掉。`BlackBoxCore.<clinit>` 会 `new BlackBoxCore()`，构造方法立刻 `BlackReflection.create(UserHandleStatic, …)`。1.3.1 的 dex 里这个接口被收成空接口并改名为 `hk0`，类上的 `@BClassName` 没了。
- `me.weishu.reflection.Reflection` 和 `top.niunaijun.jnihook.**` 还在，不是这次缺的。

类初始化失败后 ART 把后续访问报成 `NoClassDefFoundError: BlackBoxCore`（点「重新初始化」也会落到这句，因为失败的类在进程里一直是 erroneous）。

### 改动

- `proguard-rules.pro` 增加 keep：`top.niunaijun.blackreflection.**`、`black.**`、`me.weishu.reflection.**`，以及 AAR 里剩下的 `a.a` / `b.a` / `b.b` / `c.a` / `d.a`。保留 `RuntimeVisibleAnnotations`，镜像接口上的注解不会被剥掉。没有关掉 R8。
- `VirtualEngine` 的错误文本带上 cause 链，避免只看到最外层的 `NoClassDefFoundError: BlackBoxCore`。
- 版本 `1.3.2+10302`。1.3.1 的进程名补丁、错误页和 DaemonService 的 specialUse 子类型都还在。

### 验证

`flutter analyze` 无问题，`flutter test` 全部通过。`flutter build apk --release --target-platform android-arm64` 打出 `1.3.2` / `10302`。发布包 `classes.dex` 里：

- `black-reflection` 21 个类全部在，包括 `BlackReflection`。
- `black.**` 964 个类全部在。`UserHandleStatic` 仍有 `myUserId`，类注解是 `@BClassNameNotProcess("android.os.UserHandle")`。
- `BlackBoxCore.<init>`（由 `<clinit>` 调用）是 `const-class UserHandleStatic` + `BlackReflection.create`，不再是空接口 `hk0`。
- `getProcessName` 仍是 `invoke-static ProcessNameCompat.get`。
- `me.weishu.reflection.Reflection`、`jnihook` 的 `JniHook` / `MethodUtils` / `ReflectCore` 都在。
- 包含 `arm64-v8a/libblackbox.so`。

## 1.3.1+10301

### 原因

Redmi K80（HyperOS / Android 15）安装 1.3.0 后，首页一直停在「正在准备虚拟分身」，点「重新检测」没有变化。

`CloneStore.ready` 为真的条件是虚拟引擎可用，或工作资料已就绪。这台机器上工作资料不可用（`canInteractAcrossProfiles = false`），所以界面完全取决于 `VirtualEngine.available`。

`VirtualEngine.attach` 调用 `BlackBoxCore.doAttachBaseContext`。该方法里的 `getProcessName` **只**查询 `ActivityManager.getRunningAppProcesses()`。在 `Application.attachBaseContext` 阶段，HyperOS 经常返回 null 或不包含当前 pid。查不到时 BlackBox 直接抛出：

`RuntimeException("processName = null")`

这个异常没有被 BlackBox 自己接住，`mProcessType` 和 `HookManager.init` 都不会执行。`VirtualEngine` 把错误记成 `attach: RuntimeException: processName = null`，并且 `create()` 看到 attach 失败就直接返回。引擎从此一直是 `ready = false`。

「重新检测」只是再读一次 `virtualStatus()`，不会重新 `attach`。首页在工作资料状态为 `none` 时展示的是固定文案，**不显示 `engineError`**，所以失败看起来像一直在准备。

另外，Bcore 的 `:black` 守护进程声明了 `foregroundServiceType="specialUse"`，AAR 清单里没有 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`。targetSdk 34+ 上 `startForeground` 会失败，系统随后可能杀掉 `:black`，即使主进程已经就绪也无法真正安装/启动分身。

排除过的猜测：`libblackbox.so` 的 LOAD 段按 16KB 对齐（`p_align = 0x4000` 且偏移与虚拟地址同余），不是 16KB 页设备上的加载失败。发布构建没有打开 R8 压缩。

### 改动

- 只改 Bcore 的 `BlackBoxCore.getProcessName`：改为调用 `ProcessNameCompat.get`。优先 `Application.getProcessName()`（API 28+），其次 `/proc/self/cmdline`，最后才是 ActivityManager。不再因为查不到进程名而中断初始化。补丁脚本：`android/tools/PatchBlackBoxProcessName.java`。说明：`android/app/libs/BCORE_PATCH.txt`。
- `VirtualEngine.retry`：attach 失败可以再试；attach 已成功则只重跑 `create`。首页「重新初始化」和「重新检测」都会走它。
- 引擎没起来且工作资料也不是 ready 时，首页显示具体错误（例如 `attach: RuntimeException: processName = null`），不再显示「正在准备虚拟分身」。
- 给 DaemonService / DaemonInnerService 补上 specialUse 子类型说明，避免 Android 14+ 杀掉 `:black`。
- 版本 `1.3.1+10301`。

工作资料仍然只是回退，不要求在 HyperOS 上创建工作资料。没有加入指纹伪装、计算器伪装或隐藏最近任务。更新检查仍只用 `https://link.liunianai.cn/n8afhu`。

### 验证

`flutter analyze` 无问题，`flutter test` 全部通过。`flutter build apk --release --target-platform android-arm64` 打出 `1.3.1` / `10301`。发布包里的 dex 中，`BlackBoxCore.getProcessName` 是 `invoke-static ProcessNameCompat.get`；清单里 DaemonService 带有 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，`SystemCallProvider` 的 authority 已替换成 `com.parallelspace.parallel_space.blackbox.SystemCallProvider`，并包含 `arm64-v8a/libblackbox.so`。
