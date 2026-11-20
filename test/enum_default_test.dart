import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/models/app_info.dart';
import 'package:parallel_space/screens/home_screen.dart';
import 'package:parallel_space/services/backup_codec.dart';
import 'package:parallel_space/services/native_bridge.dart';
import 'package:parallel_space/state/clone_store.dart';
import 'package:parallel_space/state/location_store.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _key = '0123456789abcdef0123456789abcdef';

Future<CloneStore> _two() async {
  SharedPreferences.setMockInitialValues({'clones.v1.userOnly': true});
  final s = CloneStore(await SharedPreferences.getInstance());
  s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', userId: 0));
  s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', userId: 1));
  s.update(s.byInstance('com.a', 0)!, (c) => c.customLabel = '工作号');
  s.update(s.byInstance('com.a', 1)!, (c) => c.customLabel = '小号');
  s.setDefaultClone(s.byInstance('com.a', 0)!, launchKey: _key);
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

Map<String, Object?> _app(int userId) => {
      'pkg': 'com.a',
      'label': '示例',
      'ver': '1',
      'cat': 'other',
      'userId': userId,
      'inWork': true,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('通道列举失败或未就绪的空列表不会删掉分身和默认', () async {
    final s = await _two();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        throw PlatformException(code: 'native_error', message: 'binder dead');
      }
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      return null;
    });
    await s.refresh();
    expect(s.byInstance('com.a', 0)!.launchKey, _key);
    expect(s.defaultLabel('com.a'), '工作号');
    final again = CloneStore(await SharedPreferences.getInstance());
    expect(again.defaultLabel('com.a'), '工作号');
    expect(again.byInstance('com.a', 1), isNotNull);

    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        return {'complete': false, 'error': 'not_ready', 'apps': <Map>[]};
      }
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      return null;
    });
    await again.refresh();
    expect(again.clones, hasLength(2));
    expect(again.defaultClone('com.a')!.launchKey, _key);
  });

  test('某一 user 列举失败时保留全部可见分身；权威的较短列表才删除缺席实例', () async {
    final s = await _two();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        return {'complete': false, 'error': 'partial_users', 'apps': [_app(1)]};
      }
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      return null;
    });
    await s.refresh();
    expect(s.byInstance('com.a', 0)!.customLabel, '工作号');
    expect(s.byInstance('com.a', 1), isNotNull);
    expect(s.defaultClone('com.a')!.launchKey, _key);

    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        return [_app(1)];
      }
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      return null;
    });
    await s.refresh();
    expect(s.byInstance('com.a', 0), isNotNull, reason: '没有 complete 标记的列表不能当成权威结果');

    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        return {'complete': true, 'apps': [_app(1)]};
      }
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      return null;
    });
    await s.refresh();
    expect(s.byInstance('com.a', 0), isNull);
    expect(s.byInstance('com.a', 1)!.customLabel, '小号');
    expect(s.defaultClone('com.a'), isNull);
    final choice = s.resolvePackageLaunch('com.a');
    expect(choice.kind, 'chooser');
    expect(choice.clone, isNull);
  });

  test('权威空列表不再清掉分身、自定义名、默认和快捷方式身份', () async {
    final s = await _two();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') return {'complete': true, 'apps': <Map>[]};
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      if (call.method == 'recoverableClones') return <Map>[];
      return null;
    });
    await s.refresh();
    expect(s.resolve('w:com.a')!.customLabel, '工作号');
    expect(s.resolve('w:com.a#1')!.customLabel, '小号');
    expect(s.defaultClone('com.a')!.launchKey, _key);
    final restarted = CloneStore(await SharedPreferences.getInstance());
    expect(restarted.resolve('w:com.a#1')!.customLabel, '小号');
    expect(restarted.defaultLabel('com.a'), '工作号');
  });

  test('名单已被清空后，磁盘或快捷方式里的原身份可以加回，不重写数据', () async {
    SharedPreferences.setMockInitialValues({'clones.v1.userOnly': true});
    final s = CloneStore(await SharedPreferences.getInstance());
    expect(s.clones, isEmpty);
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') return {'complete': true, 'apps': <Map>[]};
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      if (call.method == 'recoverableClones') {
        return [
          {'pkg': 'com.mm', 'userId': 0, 'label': '崔宇珍'},
          {'pkg': 'com.mm', 'userId': 1, 'label': '陈令轩'},
        ];
      }
      return null;
    });
    await s.refresh();
    expect(s.resolve('w:com.mm')!.customLabel, '崔宇珍');
    expect(s.resolve('w:com.mm#1')!.customLabel, '陈令轩');
    expect(s.byInstance('com.mm', 0)!.userAdded, isTrue);
    final restarted = CloneStore(await SharedPreferences.getInstance());
    expect(restarted.resolve('w:com.mm#1')!.customLabel, '陈令轩');
  });

  test('权威的较短名单仍删除缺席分身，残留证据不能加回', () async {
    final s = await _two();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') return {'complete': true, 'apps': [_app(1)]};
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      if (call.method == 'recoverableClones') {
        return [
          {'pkg': 'com.a', 'userId': 0, 'label': '工作号'},
        ];
      }
      return null;
    });
    await s.refresh();
    expect(s.byInstance('com.a', 0), isNull);
    expect(s.resolve('w:com.a#1')!.customLabel, '小号');
  });

  test('权威空列表不会清掉已有分身；显式删除后再建不继承 launchKey', () async {
    final s = await _two();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') return {'complete': true, 'apps': <Map>[]};
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      return null;
    });
    await s.refresh();
    expect(s.resolve('w:com.a')!.customLabel, '工作号');
    expect(s.defaultClone('com.a')!.launchKey, _key);
    final gone = s.byInstance('com.a', 0)!;
    s.remove(gone.uid);
    s.adoptRestored(packageName: 'com.a', userId: 0, appName: '示例', customLabel: '新的');
    s.noteCloneDataRestore(packageName: 'com.a', sourceUserId: 0, targetUserId: 0, action: 'overwrite');
    expect(s.byInstance('com.a', 0)!.launchKey, isNull);
    expect(s.defaultClone('com.a'), isNull);
    expect(s.resolve('w:com.a#1')!.customLabel, '小号');
  });

  test('不完整刷新后覆盖恢复仍是原来的默认；显式删除后再建不继承', () async {
    final s = await _two();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'listVirtualClones') {
        return {'complete': false, 'error': 'partial_users', 'apps': <Map>[]};
      }
      if (call.method == 'listWorkApps') return {'state': 'none', 'apps': <Map>[]};
      return null;
    });
    await s.refresh();
    s.adoptRestored(packageName: 'com.a', userId: 0, appName: '示例', customLabel: '仍是这份');
    s.noteCloneDataRestore(packageName: 'com.a', sourceUserId: 0, targetUserId: 0, action: 'overwrite');
    expect(s.defaultLabel('com.a'), '仍是这份');
    expect(s.defaultClone('com.a')!.launchKey, _key);

    s.adoptRestored(packageName: 'com.a', userId: 3, appName: '示例', customLabel: '另一份');
    s.noteCloneDataRestore(packageName: 'com.a', sourceUserId: 0, targetUserId: 3, action: 'create');
    expect(s.byInstance('com.a', 3)!.launchKey, isNull);
    expect(s.defaultClone('com.a')!.userId, 0);

    final gone = s.byInstance('com.a', 0)!;
    s.remove(gone.uid);
    s.adoptRestored(packageName: 'com.a', userId: 0, appName: '示例', customLabel: '复用');
    expect(s.byInstance('com.a', 0)!.launchKey, isNull);
    expect(s.defaultClone('com.a'), isNull);
    final ask = s.resolvePackageLaunch('com.a');
    expect(ask.kind, 'chooser');
    expect(ask.clone?.userId, isNot(0));
  });

  test('defaults 不是数组时跳过该字段，其余配置仍可导入', () {
    final text = jsonEncode({
      'format': BackupCodec.format,
      'version': 3,
      'clones': [
        {'uid': 'w:com.a', 'pkg': 'com.a', 'kind': 'work', 'appName': '示例', 'name': '工作号', 'ua': true},
      ],
      'favorites': [],
      'routes': [],
      'settings': {'title': 'Alter Ego'},
      'defaults': {'pkg': 'com.a', 'userId': 2},
    });
    final data = BackupCodec.decode(text);
    expect(data.clones.single.customLabel, '工作号');
    expect(data.settings['title'], 'Alter Ego');
    expect(data.defaults, isEmpty);
    expect(data.skipped, greaterThan(0));

    final absent = BackupCodec.decode(jsonEncode({
      'format': BackupCodec.format,
      'version': 3,
      'clones': [],
      'settings': {'title': '甲'},
    }));
    expect(absent.defaults, isEmpty);
    expect(absent.settings['title'], '甲');

    final nulled = BackupCodec.decode(jsonEncode({
      'format': BackupCodec.format,
      'version': 3,
      'clones': [],
      'defaults': null,
    }));
    expect(nulled.defaults, isEmpty);
  });

  testWidgets('删除失败不改名单；删除成功即使界面已卸下也要清掉默认', (t) async {
    final s = await _two();
    final prefs = await SharedPreferences.getInstance();
    final target = s.byInstance('com.a', 0)!;
    s.setMocking(target.uid);

    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'deleteVirtual') return {'r': 'error', 'detail': 'busy'};
      return null;
    });
    await _showDelete(t, s, prefs);
    await t.tap(find.text('删除这一份'));
    await t.pump();
    await t.pump();
    expect(s.byInstance('com.a', 0), isNotNull);
    expect(s.defaultClone('com.a')!.launchKey, _key);

    final gate = _Gate();
    _channel((call) async {
      if (call.method == 'virtualStatus') return {'available': true, 'engine': 'fake'};
      if (call.method == 'deleteVirtual') return {'r': 'ok'};
      if (call.method == 'stopMock') {
        gate.markStarted();
        await gate.future;
        return 'ok';
      }
      return null;
    });
    await _showDelete(t, s, prefs);
    await t.tap(find.text('删除这一份'));
    await t.pump();
    await gate.started;
    await t.pumpWidget(const SizedBox.shrink());
    gate.release();
    await t.pump();
    await t.pump();
    expect(s.byInstance('com.a', 0), isNull);
    expect(s.defaultClone('com.a'), isNull);
    final left = s.resolvePackageLaunch('com.a');
    expect(left.kind, 'chooser');
    expect(left.clone, isNull);
    final restarted = CloneStore(await SharedPreferences.getInstance());
    expect(restarted.byInstance('com.a', 0), isNull);
    expect(restarted.byInstance('com.a', 1)!.customLabel, '小号');
  });
}

class _Gate {
  final _done = Completer<void>();
  final _started = Completer<void>();
  void markStarted() {
    if (!_started.isCompleted) _started.complete();
  }

  Future<void> get future => _done.future;
  Future<void> get started => _started.future;
  void release() {
    if (!_done.isCompleted) _done.complete();
  }
}

Future<void> _showDelete(WidgetTester t, CloneStore store, SharedPreferences prefs) async {
  final loc = LocationStore(prefs);
  await t.pumpWidget(MultiProvider(
    providers: [
      ChangeNotifierProvider<CloneStore>.value(value: store),
      ChangeNotifierProvider<LocationStore>.value(value: loc),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(builder: (context) {
          final c = store.byInstance('com.a', 0);
          return TextButton(
            onPressed: c == null ? null : () => deleteClone(context, c),
            child: const Text('删除这一份'),
          );
        }),
      ),
    ),
  ));
}
