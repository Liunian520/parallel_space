import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/app_info.dart';

enum MockResult { ok, notMockApp, error }

/// 工作资料状态。
enum WorkState {
  /// 尚未检测
  unknown,

  /// 平行空间创建的工作资料可用
  ready,

  /// 工作资料存在但“工作应用”被关闭
  paused,

  /// 尚未创建
  none,

  /// 已存在其他软件 / 公司 / 系统（如三星安全文件夹）创建的工作资料（Android 只允许一个）
  foreign,

  /// 设备不支持工作资料
  unsupported,

  /// 当前实例运行在工作资料内
  inside,
}

class WorkProfileStatus {
  const WorkProfileStatus({
    this.state = WorkState.unknown,
    this.provisioningAllowed = false,
    this.xspace = false,
    this.rom = '',
    this.romLabel = '',
    this.romNote = '',
    this.sdk = 0,
    this.linked = true,
    this.canInteract = true,
    this.canRequestInteract = false,
  });
  final WorkState state;
  final bool provisioningAllowed;

  /// 系统自带的“应用双开 / 分身”空间存在（它不是工作资料，平行空间不会使用它）。
  final bool xspace;

  /// ROM 标识：hyperos / miui / harmony / emui / magicos / coloros / oxygenos / originos / funtouch / oneui / stock …
  final String rom;
  final String romLabel;

  /// 该 ROM 关于工作资料的已知限制说明。
  final String romNote;
  final int sdk;

  /// 跨资料操作通道是否可用（已授权 interact-across-profiles 且可启动 ProfileOp）。
  final bool linked;

  /// API 30+ CrossProfileApps.canInteractAcrossProfiles
  final bool canInteract;

  /// 系统是否提供授权页（canRequestInteractAcrossProfiles）
  final bool canRequestInteract;

  bool get isMiui => rom == 'hyperos' || rom == 'miui';

  static WorkProfileStatus fromMap(Map? m) => m == null
      ? const WorkProfileStatus(state: WorkState.unsupported)
      : WorkProfileStatus(
          state: WorkState.values.where((s) => s.name == m['state']).firstOrNull ?? WorkState.unsupported,
          provisioningAllowed: m['provisioningAllowed'] == true,
          xspace: m['xspace'] == true,
          rom: m['rom'] is String ? m['rom'] as String : '',
          romLabel: m['romLabel'] is String ? m['romLabel'] as String : '',
          romNote: m['romNote'] is String ? m['romNote'] as String : '',
          sdk: (m['sdk'] as num?)?.toInt() ?? 0,
          linked: m['linked'] != false,
          canInteract: m['canInteract'] != false,
          canRequestInteract: m['canRequestInteract'] == true,
        );
}

/// 固定快捷方式请求结果。
class PinResponse {
  const PinResponse(this.result, this.sid, this.rom);

  /// 'requested' | 'exists' | 'perm_denied' | 'unsupported' | 'no_profile' | 'error'
  final String result;
  final String sid;
  final String rom;
}

class UsageInfo {
  const UsageInfo(this.foregroundMs, this.lastUsedMs, [this.label = '']);
  final int foregroundMs;
  final int lastUsedMs;
  final String label;
}

/// 与 Android 原生层（MainActivity.kt）通信。所有调用在非 Android 平台安全降级。
class NativeBridge {
  NativeBridge._();
  static const _ch = MethodChannel('parallel_space/native');

  @visibleForTesting
  static bool? debugIsAndroid;
  static bool get _android => debugIsAndroid ?? (!kIsWeb && defaultTargetPlatform == TargetPlatform.android);

  static Future<T?> _call<T>(String m, [Object? args]) async {
    if (!_android) return null;
    try {
      return await _ch.invokeMethod<T>(m, args);
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  /// 原生 → Dart 事件。
  static void listen({
    required void Function(String cloneId) onOpenClone,
    VoidCallback? onMockChanged,
    void Function(String sid)? onPinned,
  }) {
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'openClone' && call.arguments is String) onOpenClone(call.arguments as String);
      if (call.method == 'mockChanged') onMockChanged?.call();
      if (call.method == 'pinResult' && call.arguments is String) onPinned?.call(call.arguments as String);
    });
  }

  static Future<String?> launchCloneId() => _call<String>('getLaunchCloneId');

  // ---------------- 桌面快捷方式 ----------------
  static Future<PinResponse> pinShortcut({
    required String id,
    required String pkg,
    required String label,
    bool force = false,
  }) async {
    final m = await _call<Map>('pinShortcut', {'id': id, 'pkg': pkg, 'label': label, 'force': force});
    if (m == null) return PinResponse(_android ? 'error' : 'unsupported', '', '');
    return PinResponse('${m['r'] ?? 'error'}', '${m['sid'] ?? ''}', '${m['rom'] ?? ''}');
  }

  /// 旧式 INSTALL_SHORTCUT 广播（部分国产启动器仍支持；无法确认结果）。
  static Future<bool> pinLegacy({required String id, required String pkg, required String label}) async =>
      await _call<String>('pinLegacy', {'id': id, 'pkg': pkg, 'label': label}) == 'sent';

  static Future<bool> isPinned(String sid) async => await _call<bool>('isPinned', {'sid': sid}) ?? false;

  // ---------------- 厂商适配 ----------------
  /// 打开本 ROM 的“桌面快捷方式”权限页（找不到时回退到应用详情）。'rom' | 'details' | 'error'
  static Future<String> openShortcutPermission() async => await _call<String>('openShortcutPermission') ?? 'error';

  /// 打开自启动 / 后台运行管理页。
  static Future<String> openAutoStart() async => await _call<String>('openAutoStart') ?? 'error';

  /// 请求忽略电池优化。'ok'（已豁免）| 'requested' | 'error'
  static Future<String> requestIgnoreBattery() async => await _call<String>('requestIgnoreBattery') ?? 'error';

  static Future<bool> isIgnoringBattery() async => await _call<bool>('isIgnoringBattery') ?? false;

  /// ROM 信息：rom、romLabel、shortcutPerm（true/false/null）、hasAutoStart。
  static Future<Map> romInfo() async => await _call<Map>('romInfo') ?? const {};

  // ---------------- 虚拟定位 ----------------
  static Future<MockResult> startMock(double lat, double lng) async {
    final r = await _call<String>('startMock', {'lat': lat, 'lng': lng});
    return switch (r) { 'ok' => MockResult.ok, 'not_mock_app' => MockResult.notMockApp, _ => MockResult.error };
  }

  /// 服务运行中时仅更新坐标（不重建测试 Provider）。
  static Future<void> updateMock(double lat, double lng, {double bearing = 0, double speed = 0}) =>
      _call('updateMock', {'lat': lat, 'lng': lng, 'bearing': bearing, 'speed': speed});

  static Future<void> stopMock() => _call('stopMock');
  static Future<bool> isMocking() async => await _call<bool>('isMocking') ?? false;
  static Future<void> openDevSettings() => _call('openDevSettings');

  // ---------------- 工作资料（真实分身） ----------------
  static Future<WorkProfileStatus> workProfileStatus() async => _android
      ? WorkProfileStatus.fromMap(await _call<Map>('workStatus'))
      : const WorkProfileStatus(state: WorkState.unsupported);

  /// 'ok' | 'cancelled' | 'exists' | 'unsupported' | 'not_allowed' | 'foreign' | 'error'
  static Future<String> provisionWorkProfile() async => await _call<String>('provisionWork') ?? 'unsupported';

  /// 工作资料状态 + 其中的应用（state != ready 时应用列表为空，调用方不得据此删除数据）。
  static Future<({WorkProfileStatus status, List<AppInfo> apps})> workSnapshot() async {
    final m = await _call<Map>('listWorkApps');
    final st = _android ? WorkProfileStatus.fromMap(m) : const WorkProfileStatus(state: WorkState.unsupported);
    final raw = m?['apps'];
    return (status: st, apps: <AppInfo>[if (raw is List) ...raw.map(AppInfo.fromMap).nonNulls]);
  }

  static Future<List<AppInfo>> listPersonalApps() async {
    final r = await _call<List>('listPersonalApps');
    return <AppInfo>[if (r != null) ...r.map(AppInfo.fromMap).nonNulls];
  }

  /// 由工作资料中的本 App（资料所有者）复制个人空间已安装应用。'ok' | 'unsupported' | 'not_owner' | 'no_profile' | 'error'
  static Future<String> cloneIntoWork(String pkg) async => await _call<String>('cloneIntoWork', {'pkg': pkg}) ?? 'error';

  /// 静默跑 ProfileOpActivity(SETUP)。返回 {r, detail, canRequest?}。
  static Future<Map<String, dynamic>> repairWorkLink() async {
    final m = await _call<Map>('repairWorkLink');
    if (m == null) return {'r': 'error', 'detail': 'null'};
    return Map<String, dynamic>.from(m);
  }

  /// 打开系统跨资料授权页。'ok' | 'already' | 'unsupported' | 'settings' | 'error…'
  static Future<String> requestInteractAcrossProfiles() async =>
      await _call<String>('requestInteractAcrossProfiles') ?? 'unsupported';

  /// 工作资料内实例：切换到个人空间的平行空间。
  static Future<String> openPersonalSpace() async => await _call<String>('openPersonalSpace') ?? 'error';

  /// 打开系统中可删除工作资料的设置页。
  static Future<String> openWorkProfileSettings() async => await _call<String>('openWorkProfileSettings') ?? 'error';

  static Future<bool> isInsideWorkProfile() async => await _call<bool>('isInsideWorkProfile') ?? false;

  // -------- 虚拟引擎（NewBlackbox）--------
  static Future<Map<String, dynamic>> virtualStatus() async {
    final m = await _call<Map>('virtualStatus');
    return m == null ? {'available': false} : Map<String, dynamic>.from(m);
  }

  /// 冷启动失败后再初始化一次引擎，并返回最新状态。
  static Future<Map<String, dynamic>> retryVirtualEngine() async {
    final m = await _call<Map>('retryVirtualEngine');
    return m == null ? {'available': false, 'error': 'retry_unavailable'} : Map<String, dynamic>.from(m);
  }

  static Future<List<AppInfo>> listVirtualClones() async {
    final r = await _call<List>('listVirtualClones');
    return <AppInfo>[if (r != null) ...r.map(AppInfo.fromMap).nonNulls];
  }

  static Future<Map<String, dynamic>> cloneVirtual(String pkg) async {
    final m = await _call<Map>('cloneVirtual', {'pkg': pkg});
    return m == null ? {'r': 'error'} : Map<String, dynamic>.from(m);
  }

  static Future<Map<String, dynamic>> launchVirtual(String pkg) async {
    final m = await _call<Map>('launchVirtual', {'pkg': pkg});
    return m == null ? {'r': 'error'} : Map<String, dynamic>.from(m);
  }

  static Future<Map<String, dynamic>> deleteVirtual(String pkg) async {
    final m = await _call<Map>('deleteVirtual', {'pkg': pkg});
    return m == null ? {'r': 'error'} : Map<String, dynamic>.from(m);
  }

  static Future<bool> canInstallPackages() async => await _call<bool>('canInstallPackages') ?? true;

  static Future<String> openUnknownAppSources() async => await _call<String>('openUnknownAppSources') ?? 'error';

  static Future<String> installApk(String path) async => await _call<String>('installApk', {'path': path}) ?? 'error';

  /// Link 盾短链：WebView 跟随后返回 JSON 文本；失败返回 null。
  static Future<String?> fetchUpdateManifestViaWeb(String url) async =>
      await _call<String>('fetchUpdateManifestViaWeb', {'url': url});

  /// 删除整个工作资料。'ok' | 'pending' | 'settings'（已打开系统设置）| 'no_profile' | 'error'
  static Future<String> wipeWorkProfile() async => await _call<String>('wipeWorkProfile') ?? 'error';

  /// 从工作资料中移除（隐藏）应用。
  static Future<String> hideWorkApp(String pkg) async => await _call<String>('hideWorkApp', {'pkg': pkg}) ?? 'error';

  static Future<bool> isInWork(String pkg) async => await _call<bool>('isInWork', {'pkg': pkg}) ?? false;

  /// 只在工作资料中启动，绝不回退到个人空间。'ok' | 'not_found' | 'no_profile' | 'paused' | 'suspended' | 'error'
  static Future<String> launchWorkApp(String pkg) async => await _call<String>('launchWorkApp', {'pkg': pkg}) ?? 'error';

  /// 请求系统开启“工作应用”。'ok' | 'requested' | 'unsupported' | 'no_profile' | 'error'
  static Future<String> unpauseWork() async => await _call<String>('unpauseWork') ?? 'error';

  /// 'ok' | 'not_owner' | 'no_profile' | 'unsupported' | 'error'
  static Future<String> setWorkAppSuspended(String pkg, bool suspended) async =>
      await _call<String>('suspendWorkApp', {'pkg': pkg, 'suspended': suspended}) ?? 'unsupported';

  /// 批量获取真实应用图标（PNG，原生层磁盘缓存）。
  static Future<Map<String, Uint8List>> icons(List<String> pkgs, {required bool work, int size = 144}) async {
    final r = await _call<Map>('icons', {'pkgs': pkgs, 'work': work, 'size': size});
    return {
      if (r != null)
        for (final e in r.entries)
          if (e.key is String && e.value is Uint8List) e.key as String: e.value as Uint8List,
    };
  }

  // ---------------- 显示 ----------------
  /// Android 11+：请求最高刷新率。
  static Future<void> requestHighRefreshRate() => _call('highRefreshRate');

  // ---------------- 用量统计 ----------------
  static Future<bool> hasUsageAccess() async => await _call<bool>('hasUsageAccess') ?? false;
  static Future<void> openUsageAccessSettings() => _call('openUsageSettings');

  /// 今日（本地 0 点起）个人空间各应用前台时长。
  static Future<Map<String, UsageInfo>> usageToday() async {
    final r = await _call<Map>('usageToday');
    if (r == null) return const {};
    return {
      for (final e in r.entries)
        if (e.key is String && e.value is Map)
          e.key as String: UsageInfo(
            ((e.value as Map)['fg'] as num?)?.toInt() ?? 0,
            ((e.value as Map)['last'] as num?)?.toInt() ?? 0,
            ((e.value as Map)['label'] as String?) ?? e.key as String,
          ),
    };
  }

  // ---------------- 备份文件 ----------------
  /// 'ok' | 'cancelled' | 'error'
  static Future<String> saveFile(String name, String content) async =>
      await _call<String>('saveFile', {'name': name, 'content': content}) ?? 'error';
  static Future<String> shareFile(String name, String content) async =>
      await _call<String>('shareFile', {'name': name, 'content': content}) ?? 'error';

  /// 返回文件内容；取消返回 null。
  static Future<String?> openFile() => _call<String>('openFile');

  // ---------------- 桌面小组件 ----------------
  static Future<void> updateWidget(List<Map<String, Object?>> items) => _call('updateWidget', {'items': items});

  // ---------------- 工具箱 / 密码本 / 机型适配 ----------------
  static Future<String> setToolboxEnabled(bool on) async => await _call<String>('setToolboxEnabled', {'on': on}) ?? 'error';

  static Future<bool?> overlayGranted() => _call<bool>('overlayGranted');

  static Future<String?> toolboxCurrentPackage() => _call<String>('toolboxCurrentPackage');

  static Future<Map?> compatReport() => _call<Map>('compatReport');

  /// 'rom' | 'details' | 'ok' | 'requested' | 'error' | 'none'
  static Future<String> compatOpen(String id) async => await _call<String>('compatOpen', {'id': id}) ?? 'error';

  static Future<String> openUrl(String url) async => await _call<String>('openUrl', {'url': url}) ?? 'error';

  /// 'ok' | 'cancelled' | 'need_lock' | 'error'
  static Future<String> vaultUnlock() async => await _call<String>('vaultUnlock') ?? 'error';

  static Future<void> vaultLock() => _call('vaultLock');

  static Future<Map?> vaultList() => _call<Map>('vaultList');

  /// 'ok' 或错误说明。
  static Future<String?> vaultUpsert(Map<String, String> entry) => _call<String>('vaultUpsert', entry);

  static Future<String?> vaultDelete(String id) => _call<String>('vaultDelete', {'id': id});

  /// 'filled' | 'copied' | 'copied_no_field' | 'locked' | 'need_auth' | 'error'
  static Future<String> vaultFill(String id) async => await _call<String>('vaultFill', {'id': id}) ?? 'error';
}
