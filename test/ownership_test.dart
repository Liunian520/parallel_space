import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/screens/clone_detail_screen.dart';
import 'package:parallel_space/screens/settings_screen.dart';
import 'package:parallel_space/widgets/clone_card.dart';

import 'fake_native.dart';
import 'smoke_test.dart' show freshPrefs, pumpApp, settle, setScreen, standardNative, seededClonePrefs;

void main() {
  testWidgets('未授权跨空间：横幅显示授权按钮，完成初始化说明权限而非「未能启动」', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()
      ..linked = false
      ..canInteract = false
      ..canRequestInteract = true;
    native.install(t);
    await pumpApp(t, await freshPrefs(seededClonePrefs()));
    expect(find.text('授权跨空间'), findsOneWidget);
    expect(find.text('完成初始化'), findsOneWidget);
    await t.tap(find.text('完成初始化'));
    await settle(t, 20);
    expect(find.text('需要跨空间授权'), findsOneWidget);
    expect(find.textContaining('未能启动'), findsNothing);
    await t.tap(find.text('去授权'));
    await settle(t, 10);
    expect(native.methods, contains('requestInteractAcrossProfiles'));
  });

  testWidgets('每次启动/sync 清掉预装「文件」等，即使 userOnly 已标记', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()
      ..work.addAll([
        FakeNative.app('com.android.fileexplorer', '文件', system: true),
        FakeNative.app('com.android.documentsui', '文件', system: true),
        FakeNative.app('com.android.vending', 'Play 商店', system: true),
      ]);
    native.install(t);
    final prefs = await freshPrefs({
      'clones.v1.userOnly': true,
      'clones.v1': '['
          '{"uid":"w:com.android.fileexplorer","pkg":"com.android.fileexplorer","name":"文件","appName":"文件","version":"1","category":"other","kind":"work","ua":false},'
          '{"uid":"w:com.android.documentsui","pkg":"com.android.documentsui","name":"文件","appName":"文件","version":"1","category":"other","kind":"work","ua":true},'
          '{"uid":"w:com.tencent.mm","pkg":"com.tencent.mm","name":"微信","appName":"微信","version":"1","category":"social","kind":"work","ua":true}'
          ']',
    });
    await pumpApp(t, prefs);
    expect(find.text('文件'), findsNothing);
    expect(find.text('Play 商店'), findsNothing);
    expect(find.text('微信'), findsOneWidget);
    expect(find.byType(CloneCard), findsOneWidget);
  });

  testWidgets('自己的工作资料（未关联）：进入首页；预装不进列表；静默补关联且不弹「请稍后重试」', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()
      ..linked = false
      ..work.addAll([
        FakeNative.app('com.android.dialer', '电话', system: true),
        FakeNative.app('com.android.contacts', '联系人', system: true),
        FakeNative.app('com.android.vending', 'Play 商店', system: true),
      ]);
    native.install(t);
    // 模拟 1.2.1 曾把预装写进本地列表
    final prefs = await freshPrefs({
      'clones.v1': '[{"uid":"w:com.android.contacts","pkg":"com.android.contacts","name":"联系人","appName":"联系人","version":"1","category":"other","kind":"work","ua":false},'
          '{"uid":"w:com.tencent.mm","pkg":"com.tencent.mm","name":"微信","appName":"微信","version":"1","category":"social","kind":"work","ua":true},'
          '{"uid":"w:com.eg.android.AlipayGphone","pkg":"com.eg.android.AlipayGphone","name":"支付宝","appName":"支付宝","version":"1","category":"productivity","kind":"work","ua":true}]',
    });
    await pumpApp(t, prefs);
    expect(find.text('已存在其他工作资料'), findsNothing);
    expect(find.textContaining('请稍后重试'), findsNothing);
    expect(find.text('联系人'), findsNothing);
    expect(find.text('Play 商店'), findsNothing);
    expect(find.byType(CloneCard), findsNWidgets(2));
    expect(find.text('微信'), findsOneWidget);
    expect(native.methods.where((m) => m == 'repairWorkLink').length, 1);
    expect(native.methods, isNot(contains('provisionWork')));
  });

  testWidgets('其他工作资料：说明原因并可打开系统设置，重新检测后识别为自己的', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..state = 'foreign';
    native.install(t);
    await pumpApp(t, await freshPrefs(seededClonePrefs()));
    expect(find.text('已存在其他工作资料'), findsOneWidget);
    await t.tap(find.text('打开系统设置'));
    await settle(t, 5);
    expect(native.methods, contains('openWorkProfileSettings'));
    native.state = 'ready';
    await t.tap(find.text('重新检测'));
    await settle(t);
    expect(find.byType(CloneCard), findsNWidgets(2));
  });

  testWidgets('无工作资料：显示虚拟引擎引导（不再创建工作资料）', (t) async {
    setScreen(t, const Size(390, 844));
    standardNative()
      ..state = 'none'
      ..install(t);
    await pumpApp(t, await freshPrefs());
    expect(find.text('虚拟分身引擎未就绪'), findsOneWidget);
    expect(find.textContaining('disabled_in_test'), findsOneWidget);
    expect(find.text('正在准备虚拟分身'), findsNothing);
    expect(find.text('创建工作资料'), findsNothing);
  });

  testWidgets('运行在工作资料内：不允许创建，提供切换到主空间', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..state = 'inside';
    native.install(t);
    await pumpApp(t, await freshPrefs());
    expect(find.text('创建工作资料'), findsNothing);
    expect(find.textContaining('请在主空间打开 Alter Ego 进行管理'), findsOneWidget);
    await t.tap(find.text('切换到主空间'));
    await settle(t, 5);
    expect(native.methods, contains('openPersonalSpace'));
    expect(native.methods, isNot(contains('provisionWork')));
  });

  testWidgets('卡片 ⋮ 菜单「删除分身」：确认后从工作资料移除并刷新', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..work.add(FakeNative.app('com.taobao.taobao', '淘宝'));
    native.install(t);
    final prefs = await freshPrefs({
      'clones.v1': '[{"uid":"w:com.tencent.mm","pkg":"com.tencent.mm","name":"微信","appName":"微信","version":"1","category":"social","kind":"work","ua":true},'
          '{"uid":"w:com.eg.android.AlipayGphone","pkg":"com.eg.android.AlipayGphone","name":"支付宝","appName":"支付宝","version":"1","category":"productivity","kind":"work","ua":true},'
          '{"uid":"w:com.taobao.taobao","pkg":"com.taobao.taobao","name":"淘宝","appName":"淘宝","version":"1","category":"other","kind":"work","ua":true}]',
      'clones.v1.userOnly': true,
    });
    await pumpApp(t, prefs);
    expect(find.byType(CloneCard), findsNWidgets(3));
    final menu = find.descendant(
        of: find.ancestor(of: find.text('淘宝'), matching: find.byType(CloneCard)), matching: find.byTooltip('更多操作'));
    await t.tap(menu);
    await settle(t, 10);
    await t.tap(find.text('删除分身'));
    await settle(t, 10);
    await t.tap(find.text('删除'));
    await settle(t, 20);
    final hide = native.calls.where((c) => c.method == 'hideWorkApp').single;
    expect((hide.arguments as Map)['pkg'], 'com.taobao.taobao');
    expect(find.byType(CloneCard), findsNWidgets(2));
  });

  testWidgets('详情页「移除分身」', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative();
    native.install(t);
    await pumpApp(t, await freshPrefs(seededClonePrefs()));
    await t.tap(find.text('微信'));
    await settle(t, 20);
    expect(find.byType(CloneDetailScreen), findsOneWidget);
    await t.scrollUntilVisible(find.text('移除分身'), 300, scrollable: find.byType(Scrollable).first);
    await t.ensureVisible(find.text('移除分身'));
    await settle(t, 5);
    await t.tap(find.text('移除分身'));
    await settle(t, 10);
    await t.tap(find.text('删除'));
    await settle(t, 20);
    expect(native.methods, contains('hideWorkApp'));
    expect(find.byType(CloneDetailScreen), findsNothing);
  });

  testWidgets('设置「删除工作资料」需两次确认', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative();
    native.install(t);
    await pumpApp(t, await freshPrefs(seededClonePrefs()));
    await t.tap(find.byIcon(Icons.settings_outlined));
    await settle(t, 20);
    expect(find.byType(SettingsScreen), findsOneWidget);
    await t.ensureVisible(find.text('删除工作资料'));
    await settle(t, 5);
    await t.tap(find.text('删除工作资料'));
    await settle(t, 10);
    await t.tap(find.text('继续'));
    await settle(t, 10);
    expect(native.methods, isNot(contains('wipeWorkProfile')));
    await t.tap(find.text('永久删除'));
    await settle(t, 40);
    expect(native.methods, contains('wipeWorkProfile'));
    expect(find.text('删除工作资料'), findsNothing);
  });

  testWidgets('未关联时仍可打开已添加的分身（不依赖 forwarder）', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..linked = false;
    native.install(t);
    final prefs = await freshPrefs({
      'clones.v1': '[{"uid":"w:com.tencent.mm","pkg":"com.tencent.mm","name":"微信","appName":"微信","version":"1","category":"social","kind":"work","ua":true}]',
      'clones.v1.userOnly': true,
    });
    await pumpApp(t, prefs);
    await t.tap(find.byTooltip('更多操作').first);
    await settle(t, 10);
    await t.tap(find.text('打开'));
    await settle(t, 15);
    expect(native.methods, contains('launchWorkApp'));
    expect(find.textContaining('请稍后重试'), findsNothing);
  });

  testWidgets('复制被系统拒绝：弹出明确错误，不出现「请稍后重试」', (t) async {
    setScreen(t, const Size(390, 844));
    final native = standardNative()..cloneResult = 'rejected';
    native.install(t);
    final prefs = await freshPrefs({'clones.v1.userOnly': true});
    await pumpApp(t, prefs);
    await t.tap(find.text('添加你的第一个分身'));
    await settle(t, 20);
    await t.tap(find.text('淘宝'));
    await settle(t, 10);
    await t.tap(find.text('创建分身'));
    await settle(t, 20);
    expect(find.text('无法创建分身'), findsOneWidget);
    expect(find.textContaining('系统拒绝了静默复制'), findsOneWidget);
    expect(find.textContaining('请稍后重试'), findsNothing);
    await t.tap(find.text('知道了'));
    await settle(t, 5);
  });
}
