import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/models/app_info.dart';
import 'package:parallel_space/services/native_bridge.dart';
import 'package:parallel_space/state/clone_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _key = '0123456789abcdef0123456789abcdef';

Future<CloneStore> _virtualClones() async {
  SharedPreferences.setMockInitialValues({'clones.v1.userOnly': true});
  final s = CloneStore(await SharedPreferences.getInstance());
  s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', version: '1', userId: 0));
  s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', version: '1', userId: 1));
  s.update(s.byInstance('com.a', 0)!, (c) => c.customLabel = '工作号');
  s.update(s.byInstance('com.a', 1)!, (c) => c.customLabel = '小号');
  s.setDefaultClone(s.byInstance('com.a', 1)!, launchKey: _key);
  return s;
}

void _channel(Future<Object?> Function(MethodCall call) handle) {
  NativeBridge.debugIsAndroid = true;
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
    const MethodChannel('parallel_space/native'),
    handle,
  );
  addTearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(const MethodChannel('parallel_space/native'), null);
    NativeBridge.debugIsAndroid = null;
  });
}

/// 工作资料名单没有 userId，解析后都是 0。这里故意不列出 com.a。
Map<String, Object?> _workReady({List<Map<String, Object?>> apps = const []}) => {
      'state': 'ready',
      'rom': 'hyperos',
      'xspace': true,
      'linked': true,
      'canInteract': true,
      'apps': apps,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('引擎不可用且工作资料 ready 时，不按工作资料名单删除虚拟分身和默认', () async {
    final s = await _virtualClones();
    _channel((call) async {
      if (call.method == 'virtualStatus' || call.method == 'retryVirtualEngine') {
        return {'available': false, 'error': 'create: engine down', 'engine': 'NewBlackbox/Bcore'};
      }
      if (call.method == 'listWorkApps') {
        return _workReady(apps: [
          {'pkg': 'com.other', 'label': '别的', 'ver': '9', 'cat': 'other'},
        ]);
      }
      return null;
    });
    await s.refresh();
    expect(s.engineAvailable, isFalse);
    expect(s.workState, WorkState.ready);
    expect(s.byInstance('com.a', 0)!.launchKey, isNull);
    expect(s.byInstance('com.a', 0)!.customLabel, '工作号');
    expect(s.byInstance('com.a', 1)!.launchKey, _key);
    expect(s.defaultLabel('com.a'), '小号');
    final restarted = CloneStore(await SharedPreferences.getInstance());
    expect(restarted.defaultClone('com.a')!.userId, 1);
    expect(restarted.byInstance('com.a', 0), isNotNull);
  });

  test('工作资料名单仍可更新 userId 0 的版本，但不改虚拟实例', () async {
    final s = await _virtualClones();
    _channel((call) async {
      if (call.method == 'virtualStatus') {
        return {'available': false, 'error': 'create: engine down', 'engine': 'NewBlackbox/Bcore'};
      }
      if (call.method == 'listWorkApps') {
        return _workReady(apps: [
          {'pkg': 'com.a', 'label': '示例新名', 'ver': '9.0', 'cat': 'social', 'suspended': true},
        ]);
      }
      return null;
    });
    await s.refresh();
    final first = s.byInstance('com.a', 0)!;
    expect(first.version, '9.0');
    expect(first.appName, '示例新名');
    expect(first.frozen, isTrue);
    expect(first.customLabel, '工作号');
    expect(s.byInstance('com.a', 1)!.version, '1');
    expect(s.byInstance('com.a', 1)!.frozen, isFalse);
    expect(s.defaultClone('com.a')!.userId, 1);
  });

  test('引擎恢复后仍用虚拟名单；显式删除后同一 userId 不继承默认', () async {
    final s = await _virtualClones();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        return {
          'complete': true,
          'apps': [
            {'pkg': 'com.a', 'label': '示例', 'ver': '2', 'userId': 0},
          ],
        };
      }
      if (call.method == 'listWorkApps') return _workReady();
      return null;
    });
    await s.refresh();
    expect(s.byInstance('com.a', 1), isNull);
    expect(s.defaultClone('com.a'), isNull);
    expect(s.byInstance('com.a', 0)!.version, '2');

    s.adoptRestored(packageName: 'com.a', userId: 1, appName: '示例', customLabel: '复用');
    s.noteCloneDataRestore(packageName: 'com.a', sourceUserId: 1, targetUserId: 1, action: 'create');
    expect(s.byInstance('com.a', 1)!.launchKey, isNull);
    expect(s.defaultClone('com.a'), isNull);
    final ask = s.resolvePackageLaunch('com.a');
    expect(ask.kind, isNot('launch'));
    expect(ask.clone?.userId, isNot(1));
  });

  test('引擎可用但不完整时，不改用工作资料空名单删除', () async {
    final s = await _virtualClones();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        return {'complete': false, 'error': 'partial_users', 'apps': <Map<String, Object?>>[]};
      }
      if (call.method == 'listWorkApps') return _workReady();
      return null;
    });
    await s.refresh();
    expect(s.engineAvailable, isTrue);
    expect(s.byInstance('com.a', 0)!.customLabel, '工作号');
    expect(s.byInstance('com.a', 1)!.launchKey, _key);
    expect(s.defaultLabel('com.a'), '小号');
  });

  test('重叠的刷新不会把进行中的工作资料同步变成删除', () async {
    final s = await _virtualClones();
    final gate = Completer<void>();
    var listed = 0;
    _channel((call) async {
      if (call.method == 'virtualStatus') {
        return {'available': false, 'error': 'create: engine down', 'engine': 'NewBlackbox/Bcore'};
      }
      if (call.method == 'listWorkApps') {
        listed++;
        if (listed == 1) await gate.future;
        return _workReady();
      }
      return null;
    });
    final first = s.refresh();
    await Future<void>.delayed(Duration.zero);
    final second = s.refresh();
    gate.complete();
    await Future.wait([first, second]);
    expect(s.byInstance('com.a', 1)!.launchKey, _key);
    expect(s.defaultLabel('com.a'), '小号');
    expect(listed, 1);
  });
}
