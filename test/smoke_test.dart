import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/main.dart';
import 'package:parallel_space/screens/add_clone_screen.dart';
import 'package:parallel_space/screens/clone_detail_screen.dart';
import 'package:parallel_space/screens/private_space_screen.dart';
import 'package:parallel_space/screens/settings_screen.dart';
import 'package:parallel_space/services/backup_codec.dart';
import 'package:parallel_space/services/backup_service.dart';
import 'package:parallel_space/state/clone_store.dart';
import 'package:parallel_space/state/private_space_store.dart';
import 'package:parallel_space/state/settings_store.dart';
import 'package:parallel_space/widgets/clone_card.dart';
import 'package:parallel_space/widgets/reorderable_clone_grid.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'fake_native.dart';

Future<void> settle(WidgetTester t, [int frames = 20]) async {
  for (var i = 0; i < frames; i++) {
    await t.pump(const Duration(milliseconds: 60));
  }
}

Future<SharedPreferences> freshPrefs([Map<String, Object> v = const {}]) async {
  SharedPreferences.setMockInitialValues(v);
  return SharedPreferences.getInstance();
}

void setScreen(WidgetTester t, Size logical, {double textScale = 1}) {
  t.view.devicePixelRatio = 2;
  t.view.physicalSize = logical * 2;
  t.platformDispatcher.textScaleFactorTestValue = textScale;
  addTearDown(t.view.reset);
  addTearDown(t.platformDispatcher.clearTextScaleFactorTestValue);
}

/// 在指定页面上滚动到底，过程中任何溢出 / 异常都会让测试失败。
Future<void> scrollThrough(WidgetTester t) async {
  final sc = find.byType(Scrollable);
  if (sc.evaluate().isEmpty) return;
  for (var i = 0; i < 25; i++) {
    await t.drag(sc.first, const Offset(0, -400), warnIfMissed: false);
    await t.pump(const Duration(milliseconds: 100));
  }
}

FakeNative standardNative() => FakeNative(
      work: [FakeNative.app('com.tencent.mm', '微信'), FakeNative.app('com.eg.android.AlipayGphone', '支付宝', cat: 'productivity')],
      personal: [
        FakeNative.app('com.tencent.mm', '微信'),
        FakeNative.app('com.eg.android.AlipayGphone', '支付宝', cat: 'productivity'),
        FakeNative.app('com.taobao.taobao', '淘宝', cat: 'other', ver: '10.40.0'),
        FakeNative.app('com.tencent.tmgp.sgame', '王者荣耀', cat: 'game'),
        FakeNative.app('com.android.settings', '设置', cat: 'other', system: true),
      ],
    );

/// 用户已添加的分身（与 standardNative.work 对应）。
Map<String, Object> seededClonePrefs() => {
      'clones.v1.userOnly': true,
      'clones.v1.listPolicy': 3,
      'clones.v1':
          '[{"uid":"w:com.tencent.mm","pkg":"com.tencent.mm","name":"微信","appName":"微信","version":"1.0","category":"social","kind":"work","ua":true},'
          '{"uid":"w:com.eg.android.AlipayGphone","pkg":"com.eg.android.AlipayGphone","name":"支付宝","appName":"支付宝","version":"1.0","category":"productivity","kind":"work","ua":true}]',
    };

Future<void> pumpApp(WidgetTester t, SharedPreferences prefs) async {
  await t.pumpWidget(buildProviders(prefs, child: const ParallelSpaceApp()));
  await settle(t);
}

void main() {
  testWidgets('未创建工作资料：首页显示虚拟引擎引导（不再强制创建工作资料）', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..state = 'none';
    native.install(t);
    final prefs = await freshPrefs();
    await pumpApp(t, prefs);
    expect(find.text('虚拟分身引擎未就绪'), findsOneWidget);
    expect(find.text('正在准备虚拟分身'), findsNothing);
    expect(find.textContaining('disabled_in_test'), findsOneWidget);
    expect(find.textContaining('虚拟引擎'), findsWidgets);
    expect(find.textContaining('应用双开'), findsWidgets, reason: 'HyperOS 上仍提示系统双开不是工作资料');
    expect(find.byType(CloneCard), findsNothing);
    expect(find.text('创建工作资料'), findsNothing);
    native.calls.clear();
    await t.tap(find.text('重新检测').first);
    await settle(t, 20);
    expect(native.methods, contains('retryVirtualEngine'));
    expect(find.byType(CloneCard), findsNothing);
  });

  testWidgets('引擎就绪且没有分身：显示空列表，而不是准备页', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..state = 'none';
    native.install(t);
    FakeNative.virtualAvailable = true;
    final prefs = await freshPrefs();
    await pumpApp(t, prefs);
    expect(find.text('还没有分身'), findsOneWidget);
    expect(find.text('虚拟分身引擎未就绪'), findsNothing);
    expect(find.text('正在准备虚拟分身'), findsNothing);
    expect(find.byType(CloneCard), findsNothing);
  });

  testWidgets('引擎就绪且没有工作资料：设置只显示虚拟引擎', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..state = 'none';
    native.install(t);
    FakeNative.virtualAvailable = true;
    await pumpApp(t, await freshPrefs());
    await t.tap(find.byIcon(Icons.settings_outlined));
    await settle(t, 20);
    expect(find.text('虚拟分身引擎'), findsOneWidget);
    expect(find.text('工作资料'), findsNothing);
    expect(find.text('尚未创建'), findsNothing);
    expect(find.text('删除残留工作资料'), findsNothing);
  });

  testWidgets('引擎就绪且系统里还有工作资料：可以删除残留', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..state = 'ready';
    native.install(t);
    FakeNative.virtualAvailable = true;
    await pumpApp(t, await freshPrefs());
    await t.tap(find.byIcon(Icons.settings_outlined));
    await settle(t, 20);
    expect(find.text('虚拟分身引擎'), findsOneWidget);
    expect(find.text('工作资料'), findsNothing);
    await t.ensureVisible(find.text('删除残留工作资料'));
    await t.tap(find.text('删除残留工作资料'));
    await settle(t, 10);
    expect(find.textContaining('虚拟引擎创建的分身'), findsOneWidget);
    await t.tap(find.text('继续'));
    await settle(t, 10);
    expect(native.methods, isNot(contains('wipeWorkProfile')));
    await t.tap(find.text('永久删除'));
    await settle(t, 40);
    expect(native.methods, contains('wipeWorkProfile'));
  });

  testWidgets('分身网格加载个人空间图标，不请求工作资料角标', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative();
    native.install(t);
    await pumpApp(t, await freshPrefs(seededClonePrefs()));
    await settle(t, 20);
    final iconCalls = native.calls.where((c) => c.method == 'icons').toList();
    expect(iconCalls, isNotEmpty);
    for (final c in iconCalls) {
      expect((c.arguments as Map)['work'], isFalse);
    }
  });

  testWidgets('重新初始化成功后离开失败页', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..state = 'none';
    native.install(t);
    final prefs = await freshPrefs();
    await pumpApp(t, prefs);
    expect(find.textContaining('disabled_in_test'), findsOneWidget);
    FakeNative.virtualAvailable = true;
    await t.tap(find.text('重新初始化'));
    await settle(t, 30);
    expect(find.text('还没有分身'), findsOneWidget);
    expect(native.methods, contains('retryVirtualEngine'));
  });

  testWidgets('引擎未就绪时：none 显示虚拟引导；foreign / paused 仍给原因', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()
      ..state = 'none'
      ..provisioningAllowed = false;
    native.install(t);
    final prefs = await freshPrefs();
    await pumpApp(t, prefs);
    expect(find.text('虚拟分身引擎未就绪'), findsOneWidget);
    expect(find.textContaining('disabled_in_test'), findsOneWidget);
    expect(find.text('创建工作资料'), findsNothing);

    native.state = 'foreign';
    await t.tap(find.text('重新检测').first);
    await settle(t);
    expect(find.text('已存在其他工作资料'), findsOneWidget);

    native.state = 'paused';
    await t.tap(find.text('重新检测').first);
    await settle(t);
    expect(find.text('开启工作应用'), findsOneWidget);
  });

  testWidgets('打开分身只在工作资料中启动；缺失时先复制再启动', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative();
    native.install(t);
    final prefs = await freshPrefs(seededClonePrefs());
    await pumpApp(t, prefs);
    final store = t.element(find.byType(CloneCard).first).read<CloneStore>();
    final wechat = store.byPackage('com.tencent.mm')!;

    await t.tap(find.byTooltip('更多操作').first);
    await settle(t, 10);
    await t.tap(find.text('打开'));
    await settle(t, 10);
    final launches = native.calls.where((c) => c.method == 'launchWorkApp').toList();
    expect(launches.length, 1);
    expect((launches.single.arguments as Map)['pkg'], wechat.packageName);

    native.missingInWork.add('com.tencent.mm');
    native.calls.clear();
    await t.tap(find.byTooltip('更多操作').first);
    await settle(t, 10);
    await t.tap(find.text('打开'));
    await settle(t, 30);
    expect(native.methods.where((m) => m == 'launchWorkApp').length, 2);
    expect(native.methods, contains('cloneIntoWork'));
    expect(native.methods.toSet().intersection({'launchPersonal', 'getLaunchIntentForPackage'}), isEmpty);
  });

  testWidgets('添加分身：列出真实已安装应用（分类、版本），隐藏系统应用，复制到工作资料', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative();
    native.install(t);
    final prefs = await freshPrefs(seededClonePrefs());
    await pumpApp(t, prefs);
    await t.tap(find.text('添加分身'));
    await settle(t, 20);
    expect(find.byType(AddCloneScreen), findsOneWidget);
    expect(find.text('游戏 · 1'), findsOneWidget);
    expect(find.text('版本 10.40.0'), findsOneWidget);
    expect(find.text('设置'), findsNothing, reason: '系统应用默认隐藏');
    // 已在工作资料中的应用显示对勾
    expect(find.byIcon(Icons.check_circle_rounded), findsNWidgets(2));

    await t.tap(find.text('淘宝'));
    await settle(t, 20);
    await t.tap(find.text('创建分身'));
    await settle(t, 30);
    expect(native.methods, contains('cloneIntoWork'));
    // 成功后返回首页
    expect(find.byType(AddCloneScreen), findsNothing);
    expect(find.text('淘宝'), findsOneWidget);
    final store = t.element(find.byType(CloneCard).first).read<CloneStore>();
    expect(store.byPackage('com.taobao.taobao'), isNotNull);
  });

  testWidgets('添加到桌面：HyperOS 权限被拒 → 引导并打开权限页；请求成功 → 回执确认', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..pinResult = 'perm_denied';
    native.install(t);
    final prefs = await freshPrefs(seededClonePrefs());
    await pumpApp(t, prefs);
    await t.tap(find.byTooltip('更多操作').first);
    await settle(t, 10);
    await t.tap(find.text('添加到桌面'));
    await settle(t, 10);
    expect(find.text('需要“桌面快捷方式”权限'), findsOneWidget);
    expect(find.textContaining('权限管理 → 桌面快捷方式'), findsOneWidget);
    await t.tap(find.text('去开启'));
    await settle(t, 10);
    expect(native.methods, contains('openShortcutPermission'));

    native.pinResult = 'requested';
    await t.tap(find.byTooltip('更多操作').first);
    await settle(t, 10);
    await t.tap(find.text('添加到桌面'));
    await settle(t, 5);
    final call = native.calls.lastWhere((c) => c.method == 'pinShortcut');
    expect((call.arguments as Map)['pkg'], isNotEmpty);
    await FakeNative.emit('pinResult', 'clone_${(call.arguments as Map)['pkg']}');
    await settle(t, 40);
    expect(find.textContaining('已添加'), findsOneWidget);
  });

  testWidgets('详情：自定义名称、虚拟定位、移除分身（隐藏）', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative();
    native.install(t);
    final prefs = await freshPrefs(seededClonePrefs());
    await pumpApp(t, prefs);
    await t.tap(find.text('微信'));
    await settle(t);
    expect(find.text('隐私'), findsOneWidget);
    expect(find.text('com.tencent.mm'), findsOneWidget);

    await t.tap(find.text('改名'));
    await settle(t, 10);
    await t.enterText(find.byType(TextField), '工作微信');
    await t.tap(find.text('保存'));
    await settle(t, 10);
    expect(find.text('工作微信'), findsOneWidget);

    await t.scrollUntilVisible(find.text('启用虚拟定位'), 200, scrollable: find.byType(Scrollable).first);
    final sw = find.descendant(of: find.widgetWithText(ListTile, '启用虚拟定位'), matching: find.byType(Switch));
    await t.ensureVisible(sw);
    await settle(t, 5);
    await t.tap(sw);
    await settle(t);
    await t.scrollUntilVisible(find.text('东京'), 200, scrollable: find.byType(Scrollable).first);
    await t.tap(find.text('东京'));
    await settle(t);
    expect(find.text('开始模拟'), findsOneWidget);

    await t.scrollUntilVisible(find.text('移除分身'), 300, scrollable: find.byType(Scrollable).first);
    await t.tap(find.text('移除分身'));
    await settle(t, 10);
    await t.tap(find.text('删除'));
    await settle(t, 20);
    expect(native.methods, contains('hideWorkApp'));
    final restored = CloneStore(prefs);
    expect(restored.byPackage('com.tencent.mm'), isNull);
  });

  testWidgets('性能：精简特效下首页无 BackdropFilter；网格懒加载', (t) async {
    setScreen(t, const Size(390, 844));
    final native = FakeNative(work: [for (var i = 0; i < 60; i++) FakeNative.app('com.app$i', '应用$i')]);
    native.install(t);
    final cloneJson = [
      for (var i = 0; i < 60; i++)
        '{"uid":"w:com.app$i","pkg":"com.app$i","name":"应用$i","appName":"应用$i","version":"1","category":"other","kind":"work","ua":true}'
    ].join(',');
    final prefs = await freshPrefs({'clones.v1.userOnly': true, 'clones.v1': '[$cloneJson]'});
    await pumpApp(t, prefs);
    expect(find.byType(BackdropFilter), findsNothing, reason: '精简特效默认开启，首页不应有 BackdropFilter');
    expect(find.byType(CloneCard), findsWidgets);
    // 懒加载：不应一次构建全部 60 张卡片
    expect(find.byType(CloneCard).evaluate().length, lessThan(40));
    final grid = find.byType(ReorderableCloneGrid);
    expect(grid, findsOneWidget);
  });

  test('自适应列数与大字体卡片高度', () {
    expect(ReorderableCloneGrid.columnsFor(280), 2);
    expect(ReorderableCloneGrid.columnsFor(370), 2);
    expect(ReorderableCloneGrid.columnsFor(600), 3);
    expect(ReorderableCloneGrid.columnsFor(1200), 6);
    final normal = ReorderableCloneGrid.extentFor(TextScaler.noScaling);
    final big = ReorderableCloneGrid.extentFor(const TextScaler.linear(1.3));
    expect(big, greaterThan(normal));
  });

  for (final dark in [false, true]) {
    for (final (size, scale) in const [
      (Size(320, 568), 1.3),
      (Size(411, 891), 1.0),
      (Size(800, 1280), 1.0),
      (Size(1280, 800), 1.3),
    ]) {
      testWidgets('各页面无溢出 · ${dark ? '深色' : '浅色'} · ${size.width.toInt()}x${size.height.toInt()} · 字体$scale', (t) async {
        setScreen(t, size, textScale: scale);
        final native = standardNative()
          ..work.add(FakeNative.app('com.example.reallylongpackagename.app', '超长真实应用名称测试一二三'));
        native.install(t);
        final prefs = await freshPrefs({...seededClonePrefs(), 's.theme': dark ? 2 : 1});
        await pumpApp(t, prefs);
        expect(t.takeException(), isNull);
        final store = t.element(find.byType(CloneCard).first).read<CloneStore>();
        final c = store.clones.first;
        store.update(c, (c) {
          c.customLabel = '一个非常非常长的分身名称';
          c.mockEnabled = true;
          c.mockName = '非常长的自定义位置名称名称名称';
          c.fingerprintLock = true;
          c.frozen = true;
        });
        await settle(t, 5);
        await scrollThrough(t);
        expect(t.takeException(), isNull);

        Future<void> pumpPage(Widget page) async {
          await t.pumpWidget(buildProviders(prefs, child: Builder(builder: (context) {
            final st = context.watch<SettingsStore>();
            return MaterialApp(
              themeMode: st.themeMode,
              theme: ThemeData(useMaterial3: true),
              darkTheme: ThemeData(useMaterial3: true, brightness: Brightness.dark),
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(context).copyWith(textScaler: MediaQuery.textScalerOf(context).clamp(maxScaleFactor: 1.3)),
                child: child!,
              ),
              home: page,
            );
          })));
          await settle(t, 15);
          await scrollThrough(t);
          expect(t.takeException(), isNull);
        }

        await pumpPage(CloneDetailScreen(uid: c.uid));
        await pumpPage(const SettingsScreen());
        await pumpPage(const PrivateSpaceScreen());
        await pumpPage(const AddCloneScreen());
      });
    }
  }

  testWidgets('私密空间：设置 PIN → 移入分身 → 后台自动上锁 → 主屏隐藏', (t) async {
    setScreen(t, const Size(390, 844));
    standardNative().install(t);
    final prefs = await freshPrefs(seededClonePrefs());
    final pre = CloneStore(prefs);
    await pre.refresh();
    await t.pumpWidget(buildProviders(prefs, child: const MaterialApp(home: PrivateSpaceScreen())));
    await settle(t);
    final ctx = t.element(find.byType(PrivateSpaceScreen));
    final store = ctx.read<CloneStore>();
    await store.refresh();
    Future<void> enter(String pin) async {
      for (final d in pin.split('')) {
        await t.tap(find.byKey(ValueKey('pin-$d')));
        await t.pump(const Duration(milliseconds: 50));
      }
      await t.tap(find.byKey(const ValueKey('pin-✓')));
      await settle(t, 10);
    }

    await enter('1357');
    expect(find.text('再次输入 PIN'), findsOneWidget);
    await enter('1357');
    expect(find.text('移入分身'), findsOneWidget);

    final priv = ctx.read<PrivateSpaceStore>();
    store.update(store.clones.first, (c) => c.isPrivate = true);
    await settle(t, 5);
    expect(find.text('私密分身 · 1'), findsOneWidget);
    expect(store.visibleClones.length, 1);
    expect(store.widgetItems().length, 1);

    priv.didChangeAppLifecycleState(AppLifecycleState.paused);
    await settle(t, 10);
    expect(find.text('私密空间已锁定'), findsOneWidget);

    await enter('0000');
    expect(find.textContaining('PIN 不正确'), findsOneWidget);
    await enter('1357');
    expect(find.text('私密分身 · 1'), findsOneWidget);
    expect(prefs.getKeys().any((k) => prefs.get(k).toString().contains('1357')), isFalse);
  });

  testWidgets('导入备份：无效文件提示，有效文件按包名合并设置', (t) async {
    setScreen(t, const Size(390, 844));
    standardNative().install(t);
    final prefs = await freshPrefs(seededClonePrefs());
    late BuildContext ctx;
    await t.pumpWidget(buildProviders(prefs,
        child: MaterialApp(home: Scaffold(body: Builder(builder: (c) {
          ctx = c;
          return const SizedBox();
        })))));
    await settle(t, 3);
    final store = ctx.read<CloneStore>();
    await store.refresh();

    applyBackupText(ctx, '{"hello":1}');
    await settle(t, 5);
    expect(find.textContaining('导入失败'), findsOneWidget);

    store.update(store.clones.first, (c) => c.customLabel = '小号');
    final json = buildBackupJson(ctx);
    expect(json.toLowerCase(), isNot(contains('pin')));
    expect(BackupCodec.decode(json).clones.length, 2);
    store.update(store.clones.first, (c) => c.customLabel = null);

    applyBackupText(ctx, json);
    await settle(t, 5);
    await t.tap(find.text('合并'));
    await settle(t, 20);
    expect(find.textContaining('恢复 2 个分身设置'), findsOneWidget);
    expect(store.clones.first.customName, '小号');
  });
}
