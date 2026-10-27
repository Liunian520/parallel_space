import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/services/native_bridge.dart';

const _png =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

/// 模拟原生层（MethodChannel parallel_space/native），记录所有调用。
class FakeNative {
  FakeNative({
    this.state = 'ready',
    this.rom = 'hyperos',
    List<Map<String, Object?>>? work,
    List<Map<String, Object?>>? personal,
  })  : work = work ?? [],
        personal = personal ?? [];

  static const channel = MethodChannel('parallel_space/native');
  String state;
  String rom;
  bool provisioningAllowed = true;
  String provisionResult = 'ok';
  String pinResult = 'requested';
  bool linked = true;
  bool canInteract = true;
  bool canRequestInteract = false;

  /// 默认关闭：既有 widget 测试仍走工作资料路径。
  static bool virtualAvailable = false;
  String wipeResult = 'ok';
  String cloneResult = 'ok';
  final List<Map<String, Object?>> work;
  final List<Map<String, Object?>> personal;
  final List<MethodCall> calls = [];

  /// launchWorkApp 时工作资料中没有该应用（模拟需先复制）。
  final Set<String> missingInWork = {};
  bool overlayGranted = false;
  String currentPackage = '';
  String vaultUnlockResult = 'ok';
  bool vaultOpen = true;
  final List<Map<String, Object?>> vault = [];

  Iterable<String> get methods => calls.map((c) => c.method);

  static Map<String, Object?> app(String pkg, String label, {String cat = 'social', String ver = '1.0', bool system = false}) =>
      {'pkg': pkg, 'label': label, 'ver': ver, 'cat': cat, 'system': system};

  void install(WidgetTester? t) {
    virtualAvailable = false;
    NativeBridge.debugIsAndroid = true;
    final m = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    m.setMockMethodCallHandler(channel, _handle);
    addTearDown(() {
      m.setMockMethodCallHandler(channel, null);
      NativeBridge.debugIsAndroid = null;
    });
  }

  Map<String, Object?> get _status => {
        'state': state,
        'provisioningAllowed': provisioningAllowed,
        'xspace': rom == 'hyperos',
        'rom': rom,
        'romLabel': rom == 'hyperos' ? '小米 HyperOS' : '原生 Android',
        'romNote': rom == 'hyperos' ? '系统的「应用双开」不是工作资料' : '',
        'sdk': 35,
        'linked': linked,
        'canInteract': canInteract,
        'canRequestInteract': canRequestInteract,
      };

  Future<Object?> _handle(MethodCall call) async {
    calls.add(call);
    final a = call.arguments is Map ? call.arguments as Map : const {};
    switch (call.method) {
      case 'workStatus':
        return _status;
      case 'listWorkApps':
        return {..._status, 'apps': state == 'ready' ? work : []};
      case 'listPersonalApps':
        final inWork = work.map((e) => e['pkg']).toSet();
        return [for (final p in personal) {...p, 'inWork': inWork.contains(p['pkg'])}];
      case 'provisionWork':
        if (provisionResult == 'ok') state = 'ready';
        return provisionResult;
      case 'cloneIntoWork':
        if (cloneResult != 'ok') return cloneResult;
        final pkg = a['pkg'] as String;
        missingInWork.remove(pkg);
        if (!work.any((e) => e['pkg'] == pkg)) {
          work.add(personal.firstWhere((e) => e['pkg'] == pkg, orElse: () => app(pkg, pkg)));
        }
        return 'ok';
      case 'hideWorkApp':
        work.removeWhere((e) => e['pkg'] == a['pkg']);
        return 'ok';
      case 'isInWork':
        return work.any((e) => e['pkg'] == a['pkg']) && !missingInWork.contains(a['pkg']);
      case 'launchWorkApp':
        return missingInWork.contains(a['pkg']) ? 'not_found' : 'ok';
      case 'suspendWorkApp':
        return 'ok';
      case 'icons':
        final bytes = Uint8List.fromList(base64Decode(_png));
        return {for (final p in (a['pkgs'] as List)) p: bytes};
      case 'pinShortcut':
        return {'r': pinResult, 'sid': 'clone_${a['pkg']}', 'rom': rom};
      case 'isPinned':
        return false;
      case 'openShortcutPermission':
      case 'openAutoStart':
        return 'rom';
      case 'pinLegacy':
        return 'sent';
      case 'repairWorkLink':
        if (!canInteract) {
          return {'r': 'need_permission', 'detail': 'canInteractAcrossProfiles=false', 'canRequest': canRequestInteract};
        }
        linked = true;
        return {'r': 'ok', 'detail': 'started ProfileOpActivity'};
      case 'requestInteractAcrossProfiles':
        canInteract = true;
        linked = true;
        return 'ok';
      case 'virtualStatus':
      case 'retryVirtualEngine':
        return {
          'available': virtualAvailable,
          'engine': 'fake',
          'license': 'test',
          'userId': 0,
          if (!virtualAvailable) 'error': 'disabled_in_test',
        };
      case 'listVirtualClones':
        return virtualAvailable ? work : <Map<String, dynamic>>[];
      case 'cloneVirtual':
        if (!virtualAvailable) return {'r': 'engine_unavailable'};
        final pkg = a['pkg'] as String;
        if (!work.any((e) => e['pkg'] == pkg)) {
          work.add(personal.firstWhere((e) => e['pkg'] == pkg, orElse: () => app(pkg, pkg)));
        }
        return {'r': cloneResult == 'ok' ? 'ok' : cloneResult};
      case 'launchVirtual':
        if (!virtualAvailable) return {'r': 'engine_unavailable'};
        final pkg = a['pkg'] as String?;
        if (pkg != null && missingInWork.contains(pkg)) return {'r': 'not_found'};
        return {'r': 'ok'};
      case 'deleteVirtual':
        if (!virtualAvailable) return {'r': 'engine_unavailable'};
        work.removeWhere((e) => e['pkg'] == a['pkg']);
        return {'r': 'ok'};
      case 'isInsideWorkProfile':
        return false;
      case 'canInstallPackages':
        return true;
      case 'fetchUpdateManifestViaWeb':
        return null;
      case 'installApk':
        return 'ok';
      case 'openPersonalSpace':
        return 'ok';
      case 'openWorkProfileSettings':
        return 'android.settings.SYNC_SETTINGS';
      case 'wipeWorkProfile':
        if (wipeResult == 'ok') {
          state = 'none';
          work.clear();
        }
        return wipeResult;
      case 'isMocking':
        return false;
      case 'getLaunchCloneId':
        return null;
      case 'startMock':
        return 'ok';
      case 'setToolboxEnabled':
      case 'vaultLock':
        if (call.method == 'vaultLock') vaultOpen = false;
        return 'ok';
      case 'overlayGranted':
        return overlayGranted;
      case 'toolboxCurrentPackage':
        return currentPackage;
      case 'compatReport':
        return {
          'rom': rom,
          'romLabel': rom == 'hyperos' ? '小米 HyperOS 2.0' : '原生 Android',
          'romVersion': rom == 'hyperos' ? '2.0' : '',
          'brand': rom == 'hyperos' ? 'Redmi' : 'Google',
          'family': rom == 'hyperos' ? 'XIAOMI' : 'STOCK',
          'model': 'Test',
          'sdk': 35,
          'release': '15',
          'abi': 'arm64-v8a',
          'items': [
            {'id': 'overlay', 'title': '悬浮窗', 'status': overlayGranted ? 'ok' : 'off', 'detail': '尚未允许显示悬浮窗', 'canOpen': true},
            {'id': 'accessibility', 'title': '无障碍填入', 'status': 'off', 'detail': '未开启', 'canOpen': true},
            {'id': 'abi', 'title': '64 位', 'status': 'ok', 'detail': '当前进程是 64 位', 'canOpen': false},
          ],
        };
      case 'compatOpen':
        return 'rom';
      case 'compatChains':
        return {
          'XIAOMI': {
            'overlay': ['miui:PermissionsEditorActivity', 'ACTION_MANAGE_OVERLAY_PERMISSION:package', 'APPLICATION_DETAILS', 'SETTINGS'],
            'accessibility': ['ACTION_ACCESSIBILITY_SETTINGS', 'APPLICATION_DETAILS', 'SETTINGS'],
            'autostart': ['miui:AutoStartManagementActivity', 'APPLICATION_DETAILS', 'SETTINGS'],
            'shortcut': ['miui:PermissionsEditorActivity', 'APPLICATION_DETAILS', 'SETTINGS'],
            'notification': ['ACTION_APP_NOTIFICATION_SETTINGS', 'APPLICATION_DETAILS', 'SETTINGS'],
            'battery': ['ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS', 'APPLICATION_DETAILS', 'SETTINGS'],
          },
        };
      case 'a11yGuideSeen':
        return true;
      case 'a11yGuideMark':
        return 'ok';
      case 'toolboxCrashLog':
        return '（测试环境无工具箱日志）';
      case 'toolboxCrashLogClear':
        return 'ok';
      case 'toolboxCrashLogShare':
        return 'ok';
      case 'vaultCopy':
        return (a['which'] == 'user' || a['which'] == 'username') ? 'copied_user' : 'copied_pass';
      case 'openUrl':
        return 'ok';
      case 'vaultUnlock':
        if (vaultUnlockResult != 'ok') return vaultUnlockResult;
        vaultOpen = true;
        return 'ok';
      case 'vaultList':
        return {'locked': false, 'entries': vault};
      case 'vaultUpsert':
        final id = a['id'] is String && (a['id'] as String).isNotEmpty ? a['id'] as String : 'id${vault.length + 1}';
        vault.removeWhere((e) => e['id'] == id);
        vault.add({
          'id': id,
          'pkg': a['pkg'] ?? '',
          'clone': a['clone'] ?? '',
          'label': a['label'] is String && (a['label'] as String).trim().isNotEmpty ? a['label'] : '未命名',
          'user': a['user'] ?? '',
          'pass': a['pass'] ?? '',
          'note': '',
        });
        return 'ok';
      case 'vaultDelete':
        vault.removeWhere((e) => e['id'] == a['id']);
        return 'ok';
      case 'vaultFill':
        return 'copied';
      default:
        return null;
    }
  }

  /// 原生 → Dart 事件。
  static Future<void> emit(String method, Object? args) async {
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      channel.name,
      const StandardMethodCodec().encodeMethodCall(MethodCall(method, args)),
      (_) {},
    );
  }
}
