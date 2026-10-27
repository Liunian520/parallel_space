import 'dart:convert';

import 'package:fake_async/fake_async.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/models/app_info.dart';
import 'package:parallel_space/models/clone_app.dart';
import 'package:parallel_space/data/mock_locations.dart';
import 'package:parallel_space/models/location_models.dart';
import 'package:parallel_space/services/backup_codec.dart';
import 'package:parallel_space/services/native_bridge.dart';
import 'package:parallel_space/state/clone_store.dart';
import 'package:parallel_space/state/location_store.dart';
import 'package:parallel_space/state/private_space_store.dart';
import 'package:parallel_space/state/settings_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<SharedPreferences> prefsWith(Map<String, Object> v) async {
  SharedPreferences.setMockInitialValues(v);
  return SharedPreferences.getInstance();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  AppInfo app(String pkg, {String? name, bool suspended = false, String ver = '1.0'}) =>
      AppInfo(packageName: pkg, name: name ?? pkg.split('.').last, version: ver, suspended: suspended);

  Future<CloneStore> readyStore(List<String> pkgs, [Map<String, Object> prefs = const {}]) async {
    final p = await prefsWith({'clones.v1.userOnly': true, ...prefs});
    final s = CloneStore(p);
    s.debugSetStatus(const WorkProfileStatus(state: WorkState.ready));
    for (final pkg in pkgs) {
      s.addFromApp(app(pkg));
    }
    return s;
  }

  group('CloneStore', () {
    test('预装包名判定：文件/联系人/Play', () {
      expect(CloneStore.isPresetPackage('com.android.fileexplorer', label: '文件'), isTrue);
      expect(CloneStore.isPresetPackage('com.android.documentsui', label: '文件'), isTrue);
      expect(CloneStore.isPresetPackage('com.tencent.mm', label: '微信'), isFalse);
    });

    test('首次运行没有任何分身（不再写入模拟数据）', () async {
      final p = await prefsWith({});
      final s = CloneStore(p);
      expect(s.clones, isEmpty);
      expect(s.workState, WorkState.unknown);
      expect(s.ready, isFalse);
    });

    test('升级迁移：丢弃 1.x 模拟分身，保留工作资料分身及其设置', () async {
      final v1 = jsonEncode([
        {'uid': 'a', 'app': 'wechat', 'index': 2, 'name': '微信', 'emoji': '😀', 'unread': 3},
        {'uid': 'w1', 'app': 'real:com.x', 'pkg': 'com.x', 'kind': 'work', 'appName': 'X', 'index': 2, 'pw': true},
        {'uid': 'w2', 'pkg': 'com.x', 'kind': 'work'}, // 重复包名
        'junk',
      ]);
      final p = await prefsWith({'clones.v1': v1, 'clones.v1.seq': 2, 'clones.v1.mocking': 'a'});
      final s = CloneStore(p);
      expect(s.clones.map((c) => c.uid), ['w1']);
      expect(s.clones.single.passwordLock, isTrue);
      expect(s.mockingUid, isNull, reason: '指向被丢弃分身的 mocking 状态应被清除');
      // 迁移结果已写回，原生跳板读取的是干净数据
      final saved = jsonDecode(p.getString('clones.v1')!) as List;
      expect(saved.length, 1);
      expect((saved.single as Map)['pkg'], 'com.x');
      expect((saved.single as Map).containsKey('emoji'), isFalse);
    });

    test('数据损坏不崩溃，原文被保留', () async {
      final p = await prefsWith({'clones.v1': '{broken'});
      final s = CloneStore(p);
      expect(s.clones, isEmpty);
      expect(p.getString('clones.v1.corrupt'), '{broken');
    });

    test('sync：只更新/移除用户已添加的分身，不把工作资料预装自动加进来', () async {
      final s = await readyStore(['com.a', 'com.b', 'com.c']);
      expect(s.clones.map((c) => c.packageName), ['com.a', 'com.b', 'com.c']);
      s.update(s.byPackage('com.b')!, (c) => c.fingerprintLock = true);
      s.reorderByUid(s.byPackage('com.c')!.uid, s.byPackage('com.a')!.uid);
      s.sync([app('com.a'), app('com.b', name: '新名', ver: '2.0', suspended: true), app('com.c'), app('com.d')]);
      // com.d 在工作资料中但用户没添加 → 不出现
      expect(s.clones.map((c) => c.packageName), ['com.c', 'com.a', 'com.b']);
      final b = s.byPackage('com.b')!;
      expect(b.fingerprintLock, isTrue);
      expect(b.appName, '新名');
      expect(b.version, '2.0');
      expect(b.frozen, isTrue);
      s.sync([app('com.d')]); // a/b/c 都不在工作资料了 → 清空
      expect(s.clones, isEmpty);
    });

    test('工作资料不可用时 refresh 不删除任何分身', () async {
      final s = await readyStore(['com.a']);
      // 非 Android 环境：workSnapshot 返回 unsupported 且应用为空
      await s.refresh();
      expect(s.ready, isFalse);
      expect(s.clones.length, 1);
    });

    test('refresh：通过原生通道读取工作资料（模拟 MethodChannel）', () async {
      final s = CloneStore(await prefsWith({}));
      NativeBridge.debugIsAndroid = true;
      addTearDown(() => NativeBridge.debugIsAndroid = null);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('parallel_space/native'),
        (call) async => call.method == 'listWorkApps'
            ? {
                'state': 'ready',
                'rom': 'hyperos',
                'xspace': true,
                'apps': [
                  {'pkg': 'com.tencent.mm', 'label': '微信', 'ver': '8.0', 'cat': 'social'},
                  {'pkg': ''},
                ],
              }
            : null,
      );
      addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('parallel_space/native'), null));
      await s.refresh();
      expect(s.ready, isTrue);
      expect(s.status.isMiui, isTrue);
      expect(s.status.xspace, isTrue);
      // 工作资料里有微信也不自动进列表
      expect(s.clones, isEmpty);
      s.addFromApp(AppInfo(packageName: 'com.tencent.mm', name: '微信', version: '8.0', category: AppCategory.social));
      await s.refresh();
      expect(s.clones.single.customName, '微信');
      expect(s.clones.single.version, '8.0');
      expect(s.resolve('w:com.tencent.mm'), isNotNull);
    });

    test('虚拟引擎 available 时 ready，即使工作资料是 none', () async {
      final s = CloneStore(await prefsWith({}));
      NativeBridge.debugIsAndroid = true;
      addTearDown(() => NativeBridge.debugIsAndroid = null);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('parallel_space/native'),
        (call) async => switch (call.method) {
          'virtualStatus' => {'available': true, 'error': null, 'engine': 'NewBlackbox/Bcore'},
          'listVirtualClones' => <Map<String, dynamic>>[],
          'listWorkApps' => {'state': 'none', 'rom': 'hyperos', 'romLabel': '小米 HyperOS', 'romNote': 'note', 'apps': <Map>[]},
          _ => null,
        },
      );
      addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('parallel_space/native'), null));
      await s.refresh();
      expect(s.engineAvailable, isTrue);
      expect(s.engineError, isNull);
      expect(s.workState, WorkState.none);
      expect(s.ready, isTrue);
      expect(s.clones, isEmpty);
    });

    test('引擎初始化失败时记下 engineError，且 ready 为 false', () async {
      final s = CloneStore(await prefsWith({}));
      NativeBridge.debugIsAndroid = true;
      addTearDown(() => NativeBridge.debugIsAndroid = null);
      const err = 'attach: RuntimeException: processName = null';
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('parallel_space/native'),
        (call) async => switch (call.method) {
          'virtualStatus' || 'retryVirtualEngine' => {'available': false, 'error': err, 'engine': 'NewBlackbox/Bcore'},
          'listWorkApps' => {'state': 'none', 'rom': 'hyperos', 'apps': <Map>[]},
          _ => null,
        },
      );
      addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(const MethodChannel('parallel_space/native'), null));
      await s.refresh();
      expect(s.engineAvailable, isFalse);
      expect(s.engineError, err);
      expect(s.ready, isFalse);
      await s.retryEngine();
      expect(s.engineError, err);
      expect(s.ready, isFalse);
    });

    test('按 uid 排序：私密分身不影响主屏顺序', () async {
      final s = await readyStore(['com.a', 'com.b', 'com.c']);
      final ids = s.clones.map((c) => c.uid).toList();
      s.update(s.clones[0], (c) => c.isPrivate = true);
      expect(s.visibleClones.map((c) => c.uid), [ids[1], ids[2]]);
      s.reorderByUid(ids[2], ids[1]);
      expect(s.visibleClones.map((c) => c.uid), [ids[2], ids[1]]);
      expect(s.privateClones.single.uid, ids[0]);
    });

    test('删除正在模拟定位的分身会清除 mocking；更新已删除分身为无操作', () async {
      final s = await readyStore(['com.a']);
      final c = s.clones.single;
      s.setMocking(c.uid);
      s.remove(c.uid);
      expect(s.mockingUid, isNull);
      s.update(c, (c) => c.isPrivate = true);
      expect(s.clones, isEmpty);
    });

    test('导入：按包名应用设置，统计本机没有的应用；覆盖会重置设置', () async {
      final s = await readyStore(['com.a', 'com.b']);
      s.update(s.byPackage('com.b')!, (c) => c.passwordLock = true);
      final r = s.importClones([
        CloneApp(uid: 'x', packageName: 'com.a', appName: 'A', customLabel: '工作号', isPrivate: true),
        CloneApp(uid: 'y', packageName: 'com.zzz', appName: 'Z'),
      ], replace: false);
      expect(r.applied, 1);
      expect(r.missing, 1);
      expect(s.byPackage('com.a')!.customName, '工作号');
      expect(s.byPackage('com.a')!.isPrivate, isTrue);
      expect(s.byPackage('com.b')!.passwordLock, isTrue);
      s.importClones(const [], replace: true);
      expect(s.byPackage('com.b')!.passwordLock, isFalse);
      expect(s.byPackage('com.a')!.customLabel, isNull);
      expect(s.clones.length, 2, reason: '覆盖导入不会删除工作资料中的应用');
    });

    test('自动冻结：仅闲置超时且未冻结的分身', () async {
      final s = await readyStore(['com.a', 'com.b', 'com.c']);
      final now = DateTime(2026, 1, 1, 12);
      final ms = now.millisecondsSinceEpoch;
      s.update(s.clones[0], (c) => c
        ..autoFreezeMin = 15
        ..lastActiveMs = ms - 16 * 60000);
      s.update(s.clones[1], (c) => c
        ..autoFreezeMin = 15
        ..lastActiveMs = ms - 5 * 60000);
      s.update(s.clones[2], (c) => c
        ..autoFreezeMin = 15
        ..frozen = true
        ..lastActiveMs = 0);
      expect(s.dueForAutoFreeze(now).map((c) => c.packageName), ['com.a']);
      s.markOpened(s.clones[0], now: now);
      expect(s.dueForAutoFreeze(now), isEmpty);
    });

    test('小组件数据：最多 4 个，排除私密分身，包含包名', () async {
      final s = await readyStore(['a.a', 'b.b', 'c.c', 'd.d', 'e.e']);
      s.update(s.clones[0], (c) => c.isPrivate = true);
      final w = s.widgetItems();
      expect(w.length, 4);
      expect(w.map((e) => e['pkg']), ['b.b', 'c.c', 'd.d', 'e.e']);
      expect(w.first.keys, containsAll(['id', 'pkg', 'label']));
    });
  });

  group('SettingsStore', () {
    test('越界 / 类型错误不崩溃；移除旧的模拟通知设置；精简特效默认开启', () async {
      final p = await prefsWith({'s.theme': 9, 'priv.pinHash': 'h', 's.dyn': 'yes', 's.badge': true, 's.dndS': 3});
      final st = SettingsStore(p);
      expect(st.themeMode.index, 2);
      expect(st.useDynamicColor, isTrue);
      expect(st.reduceEffects, isTrue);
      expect(p.containsKey('s.badge'), isFalse);
      expect(p.containsKey('s.dndS'), isFalse);
      final b = st.toBackup();
      expect(b.keys.every(BackupCodec.settingKeys.contains), isTrue);
      expect(jsonEncode(b), isNot(contains('h"')));
      st.applyBackup({'title': '一二三四五六七八九十十一', 'theme': -3, 'fx': false});
      expect(st.spaceTitle.length, 10);
      expect(st.themeMode.index, 0);
      expect(st.reduceEffects, isFalse);
    });
  });

  group('PrivateSpaceStore', () {
    test('设置 PIN、上锁、解锁、仅校验不解锁', () async {
      final p = await prefsWith({});
      final s = PrivateSpaceStore(p);
      expect(s.setPin('12'), isFalse);
      expect(s.setPin('1234'), isTrue);
      expect(s.unlocked, isTrue);
      expect(p.getKeys().any((k) => p.get(k) == '1234'), isFalse, reason: 'PIN 明文不落盘');
      s.didChangeAppLifecycleState(AppLifecycleState.inactive);
      expect(s.unlocked, isTrue, reason: 'inactive（弹窗等）不应上锁');
      s.didChangeAppLifecycleState(AppLifecycleState.paused);
      expect(s.unlocked, isFalse);
      expect(s.checkPin('1234'), 'ok');
      expect(s.unlocked, isFalse);
      expect(s.unlockWithPin('1234'), 'ok');
      expect(s.unlocked, isTrue);
      // 重新加载后 PIN 仍有效，但默认是锁定的
      final s2 = PrivateSpaceStore(p);
      expect(s2.unlocked, isFalse);
      expect(s2.unlockWithPin('1234'), 'ok');
    });

    test('连续输错 5 次进入 30 秒冷却', () async {
      var now = DateTime(2026);
      final s = PrivateSpaceStore(await prefsWith({}), clock: () => now);
      s.setPin('1234');
      s.lock();
      for (var i = 0; i < 4; i++) {
        expect(s.unlockWithPin('0000'), 'wrong');
      }
      expect(s.unlockWithPin('0000'), 'cooldown');
      expect(s.unlockWithPin('1234'), 'cooldown', reason: '冷却期内正确 PIN 也拒绝');
      now = now.add(const Duration(seconds: 31));
      expect(s.unlockWithPin('1234'), 'ok');
    });

    test('未设置 PIN 时生物识别不可解锁', () async {
      final s = PrivateSpaceStore(await prefsWith({'priv.bio': true}));
      expect(await s.unlockWithBiometrics(), isFalse);
      expect(s.unlockWithPin('1234'), 'nopin');
    });
  });

  group('LocationStore', () {
    const ch = MethodChannel('parallel_space/native');
    final calls = <String>[];

    setUp(() {
      calls.clear();
      NativeBridge.debugIsAndroid = true;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(ch, (c) async {
        calls.add(c.method);
        return c.method == 'startMock' ? 'ok' : null;
      });
    });
    tearDown(() {
      NativeBridge.debugIsAndroid = null;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(ch, null);
    });

    test('收藏与路线持久化、损坏数据被忽略', () async {
      final p = await prefsWith({'loc.routes.v1': 'garbage'});
      final l = LocationStore(p);
      expect(l.userRoutes, isEmpty);
      final f = l.addFavorite('  ', 1, 2);
      expect(f.name, '未命名');
      l.renameFavorite(f.id, '公司');
      l.saveRoute(name: 'r', points: const [GeoPoint(0, 0), GeoPoint(0, 0.01)]);
      final l2 = LocationStore(p);
      expect(l2.favorites.single.name, '公司');
      expect(l2.userRoutes.single.name, 'r');
      expect(l2.routes.length, presetRoutes().length + 1);
      l2.removeFavorite(f.id);
      expect(LocationStore(p).favorites, isEmpty);
    });

    test('导入：预设路线 id 不会被导入，合并跳过已存在', () async {
      final l = LocationStore(await prefsWith({}));
      final f = FavoriteLocation(id: 'x', name: 'n', lat: 0, lng: 0);
      final preset = presetRoutes().first;
      expect(l.importData([f, f], [preset], replace: false), 1);
      expect(l.importData([f], const [], replace: false), 0);
      expect(l.importData([f], const [], replace: true), 1);
    });

    test('路线回放：开始、推进、暂停冻结进度、继续、单程到终点自动暂停', () {
      fakeAsync((async) {
        late LocationStore l;
        final clk = async.getClock(DateTime(2026));
        prefsWith({}).then((p) => l = LocationStore(p, now: clk.now));
        async.flushMicrotasks();
        // 约 111 米（drive 13.9 m/s ≈ 8 秒走完）
        final r = MockRoute(id: 'r', name: 'r', points: const [GeoPoint(0, 0), GeoPoint(0, 0.001)], speed: SpeedMode.drive);
        var ok = false;
        l.startRoute(r, 'clone').then((v) => ok = v);
        async.flushMicrotasks();
        expect(ok, isTrue);
        expect(l.isPlaying, isTrue);
        async.elapse(const Duration(seconds: 3));
        final p1 = l.progress;
        expect(p1, greaterThan(0.2));
        expect(calls, contains('updateMock'));
        l.pauseRoute();
        async.elapse(const Duration(seconds: 5));
        expect(l.progress, p1);
        l.resumeRoute();
        async.elapse(const Duration(seconds: 10));
        expect(l.sample!.finished, isTrue);
        expect(l.isPaused, isTrue);
        l.resumeRoute(); // 到终点后“重新开始”
        async.elapse(const Duration(milliseconds: 600));
        expect(l.progress, lessThan(0.2));
        l.stopRoute();
        expect(l.isActive, isFalse);
      }, initialTime: DateTime(2026));
    });
  });
}
