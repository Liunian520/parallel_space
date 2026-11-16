import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/models/app_info.dart';
import 'package:parallel_space/models/clone_app.dart';
import 'package:parallel_space/services/backup_codec.dart';
import 'package:parallel_space/services/clone_actions.dart';
import 'package:parallel_space/services/default_clone.dart';
import 'package:parallel_space/state/clone_store.dart';
import 'package:parallel_space/widgets/clone_card.dart';
import 'package:parallel_space/widgets/default_clone_section.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'fake_native.dart';

const _keyA = '0123456789abcdef0123456789abcdef';
const _keyB = 'fedcba9876543210fedcba9876543210';

CloneLaunchRef _ref(
  String pkg,
  int userId, {
  String launchKey = '',
  String? label,
  bool available = true,
}) {
  return CloneLaunchRef(
    uid: CloneApp.uidFor(pkg, userId),
    packageName: pkg,
    userId: userId,
    launchKey: launchKey,
    label: label ?? '未命名',
    available: available,
  );
}

Future<CloneStore> _store() async {
  SharedPreferences.setMockInitialValues({'clones.v1.userOnly': true});
  return CloneStore(await SharedPreferences.getInstance());
}

Future<CloneStore> _two() async {
  final s = await _store();
  s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', userId: 0));
  s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', userId: 1));
  s.update(s.byInstance('com.a', 0)!, (c) => c.customLabel = '工作号');
  s.update(s.byInstance('com.a', 1)!, (c) => c.customLabel = '小号');
  return s;
}

void _screen(WidgetTester t) {
  t.view.physicalSize = const Size(800, 1600);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.resetPhysicalSize);
  addTearDown(t.view.resetDevicePixelRatio);
}

int? _launchedUser(FakeNative native) {
  final calls = native.calls.where((c) => c.method == 'launchVirtual');
  if (calls.isEmpty) return null;
  final user = (calls.last.arguments as Map)['userId'];
  return user is int ? user : (user as num).toInt();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('未指定具体分身', () {
    test('各包分开；没有默认时多份要询问，不挑最近打开或 userId 0', () async {
      final s = await _two();
      s.addFromApp(const AppInfo(packageName: 'com.b', name: '乙', userId: 0));
      s.addFromApp(const AppInfo(packageName: 'com.b', name: '乙', userId: 1));
      s.markOpened(s.byInstance('com.a', 1)!);
      expect(s.defaultClone('com.a'), isNull, reason: '最近打开不是默认');
      final ask = s.resolvePackageLaunch('com.a');
      expect(ask.kind, 'chooser');
      expect(ask.clone, isNull);
      s.setDefaultClone(s.byInstance('com.a', 0)!, launchKey: _keyA);
      expect(s.resolvePackageLaunch('com.a').clone!.userId, 0);
      expect(s.resolvePackageLaunch('com.b').kind, 'chooser');
      expect(s.defaultClone('com.b'), isNull);
    });

    test('设置、取消和重启后仍在；改名后标签跟着自定义名', () async {
      final s = await _two();
      final work = s.byInstance('com.a', 0)!;
      s.setDefaultClone(work, launchKey: _keyA);
      expect(s.defaultLabel('com.a'), '工作号');
      s.update(work, (c) => c.customLabel = '出差');
      expect(s.defaultLabel('com.a'), '出差');
      expect(s.defaultClone('com.a')!.launchKey, _keyA);

      final again = CloneStore(await SharedPreferences.getInstance());
      expect(again.defaultLabel('com.a'), '出差');
      expect(again.defaultClone('com.a')!.launchKey, _keyA);
      again.clearDefault('com.a');
      expect(again.defaultClone('com.a'), isNull);
      expect(again.byInstance('com.a', 0)!.launchKey, _keyA);
      final reloaded = CloneStore(await SharedPreferences.getInstance());
      expect(reloaded.defaultClone('com.a'), isNull);
      expect(reloaded.resolvePackageLaunch('com.a').kind, 'chooser');
    });

    test('只有一份且从没设过默认时直接打开', () async {
      final s = await _store();
      s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', userId: 0));
      final d = s.resolvePackageLaunch('com.a');
      expect(d.kind, 'launch');
      expect(d.clone!.userId, 0);
      expect(s.defaultClone('com.a'), isNull);
    });
  });

  group('点名打开不被默认改道', () {
    final clones = [
      _ref('com.a', 0, launchKey: _keyA, label: '工作号'),
      _ref('com.a', 1, label: '小号'),
    ];
    final choice = DefaultCloneChoice(packageName: 'com.a', launchKey: _keyA);

    test('快捷方式和通知上的 userId 原样打开', () {
      final shortcut = resolveExplicitLaunch(
        packageName: 'com.a',
        requestedUid: 'w:com.a#1',
        requestedUserId: 1,
        clones: clones,
        choice: choice,
        askPackages: {'com.a'},
      );
      expect(shortcut.kind, 'launch');
      expect(shortcut.userId, 1);

      final notification = resolveExplicitLaunch(
        packageName: 'com.a',
        requestedUserId: 1,
        clones: clones,
        choice: choice,
      );
      expect(notification.userId, 1);

      final oldShortcut = resolveExplicitLaunch(
        packageName: 'com.a',
        requestedUid: 'w:com.a',
        clones: [
          _ref('com.a', 0, label: '工作号'),
          _ref('com.a', 1, launchKey: _keyA, label: '小号'),
        ],
        choice: DefaultCloneChoice(packageName: 'com.a', launchKey: _keyA),
      );
      expect(oldShortcut.userId, 0);
    });

    test('点名的分身不在时不改开默认', () {
      final missing = resolveExplicitLaunch(
        packageName: 'com.a',
        requestedUid: 'w:com.a#9',
        clones: clones,
        choice: choice,
      );
      expect(missing.kind, 'absent');
      expect(missing.uid, isNull);
    });
  });

  group('默认失效', () {
    test('目标不可用或已删除时询问，不自动打开另一份', () {
      final gone = resolveGenericLaunch(
        packageName: 'com.a',
        clones: [_ref('com.a', 1, label: '小号')],
        choice: const DefaultCloneChoice(packageName: 'com.a', launchKey: _keyA),
      );
      expect(gone.kind, 'chooser');
      expect(gone.pruned, isTrue);
      expect(gone.uid, isNull);
      expect(gone.askPackages, contains('com.a'));

      final unavailable = resolveGenericLaunch(
        packageName: 'com.a',
        clones: [
          _ref('com.a', 0, launchKey: _keyA, available: false),
          _ref('com.a', 1, label: '小号'),
        ],
        choice: const DefaultCloneChoice(packageName: 'com.a', launchKey: _keyA),
      );
      expect(unavailable.kind, 'chooser');
      expect(unavailable.uid, isNull);
    });

    test('删掉默认后，复用的 userId 不会变成默认', () async {
      final s = await _two();
      s.setDefaultClone(s.byInstance('com.a', 0)!, launchKey: _keyA);
      s.remove(s.byInstance('com.a', 0)!.uid);
      s.adoptRestored(packageName: 'com.a', userId: 0, appName: '示例', customLabel: '新的');
      s.noteCloneDataRestore(packageName: 'com.a', sourceUserId: 0, targetUserId: 0, action: 'create');
      expect(s.byInstance('com.a', 0)!.launchKey, isNull);
      expect(s.defaultClone('com.a'), isNull);
      final d = s.resolvePackageLaunch('com.a');
      expect(d.kind, 'chooser');
      expect(d.stale, isTrue);
      expect(d.clone, isNull);

      final reloaded = CloneStore(await SharedPreferences.getInstance());
      final again = reloaded.resolvePackageLaunch('com.a');
      expect(again.kind, 'chooser');
      expect(again.stale, isTrue);
    });
  });

  group('分身数据恢复不按数字 id 搬家', () {
    test('新建映射不把默认改到新 userId，复制来的标记会被清掉', () {
      final effect = effectOfDataRestore(
        currentKey: _keyA,
        packageName: 'com.a',
        sourceUserId: 1,
        targetUserId: 3,
        action: 'create',
        clones: [_ref('com.a', 3, launchKey: _keyA, label: '复制')],
      );
      expect(effect.retargeted, isFalse);
      expect(effect.remainingKey, isNull);
      expect(effect.stripTargetKey, isTrue);
      expect(effect.ask, isTrue);

      final kept = effectOfDataRestore(
        currentKey: _keyA,
        packageName: 'com.a',
        sourceUserId: 0,
        targetUserId: 0,
        action: 'overwrite',
        clones: [_ref('com.a', 0, launchKey: _keyA, label: '工作号')],
      );
      expect(kept.remainingKey, _keyA);
      expect(kept.retargeted, isFalse);
      expect(kept.stripTargetKey, isFalse);
    });

    test('覆盖原分身仍是默认；新建的那一份即使抄了标记也不算', () async {
      final s = await _two();
      s.setDefaultClone(s.byInstance('com.a', 0)!, launchKey: _keyA);
      s.adoptRestored(packageName: 'com.a', userId: 0, appName: '示例', customLabel: '仍是这份');
      s.noteCloneDataRestore(packageName: 'com.a', sourceUserId: 0, targetUserId: 0, action: 'overwrite');
      expect(s.defaultLabel('com.a'), '仍是这份');
      expect(s.defaultClone('com.a')!.launchKey, _keyA);

      s.adoptRestored(packageName: 'com.a', userId: 3, appName: '示例', customLabel: '复制');
      // 模拟恢复把原标记抄到了新分身上，且还没落盘。note 应清掉新的，留下原来那份。
      s.byInstance('com.a', 3)!.launchKey = _keyA;
      s.noteCloneDataRestore(packageName: 'com.a', sourceUserId: 0, targetUserId: 3, action: 'create');
      expect(s.byInstance('com.a', 3)!.launchKey, isNull);
      expect(s.defaultClone('com.a')!.userId, 0);
      expect(s.defaultClone('com.a')!.launchKey, _keyA);
    });
  });

  group('配置备份', () {
    test('旧备份没有 defaults；只有 userId 的条目丢掉', () {
      final old = BackupCodec.decode('{"format":"${BackupCodec.format}","version":3,"clones":[]}');
      expect(old.defaults, isEmpty);
      expect(BackupCodec.version, 3);

      final parsed = BackupCodec.decode(jsonEncode({
        'format': BackupCodec.format,
        'version': 3,
        'clones': [],
        'defaults': [
          {'pkg': 'com.a', 'userId': 2},
          {'pkg': 'com.a', 'lk': _keyA, 'userId': 9},
          {'pkg': 'com.b', 'lk': 'NOT-A-KEY'},
        ],
      }));
      expect(parsed.defaults, hasLength(1));
      expect(parsed.defaults.single.launchKey, _keyA);
      expect(parsed.skipped, 2);

      final text = BackupCodec.encode(BackupData(
        clones: const [],
        favorites: const [],
        routes: const [],
        settings: const {},
        defaults: const [DefaultCloneChoice(packageName: 'com.a', launchKey: _keyA)],
      ));
      final defaults = (jsonDecode(text) as Map)['defaults'] as List;
      expect((defaults.single as Map).keys.toSet(), {'pkg', 'lk'});
    });

    test('userId 对上但 launchKey 对不上时不恢复；对得上的覆盖导入可以回来', () async {
      final s = await _store();
      s.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', userId: 2));
      s.importLaunchDefaults(
        const [DefaultCloneChoice(packageName: 'com.a', launchKey: _keyA)],
        replace: false,
      );
      expect(s.defaultClone('com.a'), isNull);
      expect(s.byInstance('com.a', 2)!.launchKey, isNull);

      s.importClones([
        CloneApp(uid: 'w:com.a#2', packageName: 'com.a', appName: '示例', userId: 2, launchKey: _keyB, customLabel: '备份名'),
      ], replace: false);
      expect(s.byInstance('com.a', 2)!.launchKey, isNull);
      expect(s.byInstance('com.a', 2)!.customLabel, '备份名');

      s.setDefaultClone(s.byInstance('com.a', 2)!, launchKey: _keyA);
      s.importClones([
        CloneApp(uid: 'w:com.a#2', packageName: 'com.a', appName: '示例', userId: 2, launchKey: _keyB, customLabel: '从备份'),
      ], replace: true);
      expect(s.byInstance('com.a', 2)!.launchKey, _keyA);
      s.importLaunchDefaults(const [
        DefaultCloneChoice(packageName: 'com.a', launchKey: _keyA),
        DefaultCloneChoice(packageName: 'com.b', launchKey: _keyB),
      ], replace: true);
      expect(s.defaultClone('com.a')!.launchKey, _keyA);
      expect(s.defaultClone('com.b'), isNull);

      s.importLaunchDefaults(const [], replace: true);
      expect(s.defaultClone('com.a'), isNull);
      expect(s.resolvePackageLaunch('com.a').kind, 'launch');
    });
  });

  testWidgets('卡片标出默认，多个分身才有按默认打开', (t) async {
    final clone = CloneApp(uid: 'w:com.a', packageName: 'com.a', appName: '示例', customLabel: '工作号');
    await t.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SizedBox(
          width: 180,
          child: CloneCard(clone: clone, isDefault: true, onTap: () {}, onOpen: () {}, onDelete: () {}, onPin: () {}, onOpenPackage: () {}),
        ),
      ),
    ));
    expect(find.text('工作号'), findsOneWidget);
    expect(find.text('默认'), findsOneWidget);
    await t.tap(find.byTooltip('更多操作'));
    await t.pumpAndSettle();
    expect(find.text('按默认打开'), findsOneWidget);
  });

  testWidgets('只有一份分身时菜单不出现按默认打开', (t) async {
    final clone = CloneApp(uid: 'w:com.a', packageName: 'com.a', appName: '示例', customLabel: '工作号');
    await t.pumpWidget(MaterialApp(
      home: Scaffold(body: SizedBox(width: 180, child: CloneCard(clone: clone, onTap: () {}, onOpen: () {}, onDelete: () {}, onPin: () {}))),
    ));
    await t.tap(find.byTooltip('更多操作'));
    await t.pumpAndSettle();
    expect(find.text('按默认打开'), findsNothing);
    expect(find.text('打开'), findsOneWidget);
  });

  testWidgets('详情可以设为默认，改名后文案更新，再关掉', (t) async {
    final store = await _store();
    store.addFromApp(const AppInfo(packageName: 'com.a', name: '示例', userId: 0));
    store.update(store.clones.single, (c) => c.customLabel = '工作号');
    await t.pumpWidget(ChangeNotifierProvider<CloneStore>.value(
      value: store,
      child: MaterialApp(home: Scaffold(body: DefaultCloneSection(clone: store.clones.single))),
    ));
    expect(find.text('设为默认分身'), findsOneWidget);
    expect(find.textContaining('最近打开的不会自动变成默认'), findsOneWidget);
    await t.tap(find.byType(Switch));
    await t.pump();
    expect(find.text('已是默认分身'), findsOneWidget);
    expect(find.textContaining('「工作号」'), findsWidgets);
    store.update(store.clones.single, (c) => c.customLabel = '出差');
    await t.pump();
    expect(find.textContaining('「出差」'), findsWidgets);
    expect(store.defaultLabel('com.a'), '出差');
    await t.tap(find.byType(Switch));
    await t.pump();
    expect(store.defaultClone('com.a'), isNull);
    expect(find.text('设为默认分身'), findsOneWidget);
  });

  testWidgets('未指定时弹出选择；记住后仍可点名打开另一份', (t) async {
    _screen(t);
    final native = FakeNative()..install(t);
    FakeNative.virtualAvailable = true;
    final store = await _two();
    await t.pumpWidget(ChangeNotifierProvider<CloneStore>.value(
      value: store,
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (context) {
            return Column(children: [
              TextButton(onPressed: () => openPackage(Navigator.of(context), 'com.a'), child: const Text('未指定打开')),
              TextButton(
                onPressed: () => openClone(Navigator.of(context), store.byInstance('com.a', 0)!),
                child: const Text('打开指定的工作号'),
              ),
            ]);
          }),
        ),
      ),
    ));

    await t.tap(find.text('未指定打开'));
    await t.pumpAndSettle();
    expect(find.text('选择分身'), findsOneWidget);
    expect(find.text('工作号'), findsOneWidget);
    expect(find.text('小号'), findsOneWidget);
    expect(_launchedUser(native), isNull);
    await t.tap(find.text('取消'));
    await t.pumpAndSettle();
    expect(_launchedUser(native), isNull);

    await t.tap(find.text('未指定打开'));
    await t.pumpAndSettle();
    await t.tap(find.text('小号'));
    await t.pump();
    await t.tap(find.text('记住为默认'));
    await t.pump();
    await t.tap(find.text('打开这一份'));
    await t.pumpAndSettle();
    expect(_launchedUser(native), 1);
    expect(store.defaultLabel('com.a'), '小号');

    native.calls.clear();
    await t.tap(find.text('打开指定的工作号'));
    await t.pumpAndSettle();
    expect(find.text('选择分身'), findsNothing);
    expect(_launchedUser(native), 0);
  });

  testWidgets('默认被删掉后询问，取消也不会打开剩下的分身', (t) async {
    _screen(t);
    final native = FakeNative()..install(t);
    FakeNative.virtualAvailable = true;
    final store = await _two();
    store.setDefaultClone(store.byInstance('com.a', 0)!, launchKey: _keyA);
    store.remove(store.byInstance('com.a', 0)!.uid);
    await t.pumpWidget(ChangeNotifierProvider<CloneStore>.value(
      value: store,
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (context) {
            return TextButton(onPressed: () => openPackage(Navigator.of(context), 'com.a'), child: const Text('未指定打开'));
          }),
        ),
      ),
    ));
    await t.tap(find.text('未指定打开'));
    await t.pumpAndSettle();
    expect(find.textContaining('不会自动打开另一份'), findsOneWidget);
    expect(find.text('小号'), findsOneWidget);
    expect(_launchedUser(native), isNull);
    await t.tap(find.text('取消'));
    await t.pumpAndSettle();
    expect(_launchedUser(native), isNull);
    expect(store.defaultClone('com.a'), isNull);
  });
}
