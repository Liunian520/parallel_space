import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/config/app_version.dart';
import 'package:parallel_space/config/update_config.dart';
import 'package:parallel_space/main.dart';
import 'package:parallel_space/screens/compat_screen.dart';
import 'package:parallel_space/screens/settings_screen.dart';
import 'package:parallel_space/screens/toolbox_screen.dart';
import 'package:parallel_space/screens/vault_screen.dart';
import 'package:parallel_space/services/backup_codec.dart';
import 'package:parallel_space/services/vault_group.dart';
import 'package:parallel_space/state/settings_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'fake_native.dart';

Future<void> settle(WidgetTester t, [int frames = 20]) async {
  for (var i = 0; i < frames; i++) {
    await t.pump(const Duration(milliseconds: 60));
  }
}

void main() {
  test('版本号只加最后一位：1.3.13 → 12313', () {
    expect(kAppVersionName, '1.3.13');
    expect(kAppVersionCode, 12313);
    expect(encodeVersionCode(1, 3, 13), kAppVersionCode);
    expect(versionCodeFromName('1.3.13'), 12313);
    expect(encodeVersionCode(1, 3, 13), greaterThan(12302));
    expect(encodeVersionCode(1, 3, 13), greaterThan(10305));
    expect(encodeVersionCode(1, 3, 13), greaterThan(12312));
  });

  test('密码本按分身分组，当前包名排在前面', () {
    const items = [
      VaultEntry(id: '1', packageName: 'com.taobao.taobao', cloneUid: 'w:com.taobao.taobao', label: '淘宝', username: 'a', password: 'p'),
      VaultEntry(id: '2', packageName: 'com.tencent.mm', cloneUid: 'w:com.tencent.mm', label: '工作号', username: 'b', password: 'q'),
      VaultEntry(id: '3', packageName: 'com.tencent.mm', cloneUid: 'w:com.tencent.mm', label: '生活号', username: 'c', password: 'r'),
    ];
    final groups = groupVaultEntries(items, currentPackage: 'com.tencent.mm', titles: const {'w:com.tencent.mm': '微信'});
    expect(groups.first.title, '微信');
    expect(groups.first.current, isTrue);
    expect(groups.first.entries.map((e) => e.label), ['工作号', '生活号']);
    expect(groups.last.entries.single.packageName, 'com.taobao.taobao');
  });

  test('没有分身 uid 时按包名分组', () {
    const items = [
      VaultEntry(id: '1', packageName: 'com.a', cloneUid: '', label: 'b', username: '', password: '1'),
      VaultEntry(id: '2', packageName: 'com.a', cloneUid: '', label: 'a', username: '', password: '2'),
    ];
    final groups = groupVaultEntries(items);
    expect(groups, hasLength(1));
    expect(groups.single.key, 'com.a');
    expect(groups.single.entries.map((e) => e.label), ['a', 'b']);
  });

  test('合并多源密码本：文件覆盖 prefs，同 id 不丢条目', () {
    final prefs = [
      {'id': 'a', 'pkg': 'com.x', 'label': '旧', 'user': 'u1', 'pass': 'p1'},
      {'id': 'b', 'pkg': 'com.y', 'label': '仅 prefs', 'user': 'u2', 'pass': 'p2'},
    ];
    final file = [
      {'id': 'a', 'pkg': 'com.x', 'label': '新', 'user': 'u1', 'pass': 'p1'},
      {'id': 'c', 'pkg': 'com.z', 'label': '仅文件', 'user': 'u3', 'pass': 'p3'},
    ];
    final merged = mergeVaultSources([prefs, file]);
    final byId = {for (final e in merged) e['id']!: e};
    expect(byId.keys.toSet(), {'a', 'b', 'c'});
    expect(byId['a']!['label'], '新');
    expect(byId['b']!['label'], '仅 prefs');
    expect(byId['c']!['label'], '仅文件');
  });

  test('密码本搜索过滤备注名和账号', () {
    const items = [
      VaultEntry(id: '1', packageName: 'com.a', cloneUid: 'w:com.a', label: '主号', username: 'alice', password: 'x', note: '宿舍'),
      VaultEntry(id: '2', packageName: 'com.b', cloneUid: 'w:com.b', label: '工作', username: 'bob', password: 'y'),
    ];
    expect(filterVaultEntries(items, 'alice').single.label, '主号');
    expect(filterVaultEntries(items, '宿舍').single.id, '1');
    expect(filterVaultEntries(items, 'com.b').single.label, '工作');
    expect(filterVaultEntries(items, ''), hasLength(2));
  });

  test('工具箱开关写入设置，备份不含密码', () async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    final st = SettingsStore(prefs);
    expect(st.toolboxOverlay, isFalse);
    st.set((s) => s.toolboxOverlay = true);
    expect(prefs.getBool('s.toolbox'), isTrue);
    expect(st.toBackup()['toolbox'], isTrue);
    expect(BackupCodec.settingKeys, contains('toolbox'));
    st.applyBackup({'toolbox': false});
    expect(st.toolboxOverlay, isFalse);
  });

  testWidgets('设置底部有开源署名，并能打开工具箱和机型适配', (t) async {
    final native = FakeNative();
    native.install(t);
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await t.pumpWidget(buildProviders(prefs, child: const MaterialApp(home: SettingsScreen())));
    await settle(t);
    expect(find.text('工具箱'), findsWidgets);
    expect(find.text('密码本'), findsWidgets);
    await t.scrollUntilVisible(find.text('机型适配检查'), 300, scrollable: find.byType(Scrollable).first);
    await t.ensureVisible(find.text('机型适配检查'));
    await settle(t, 5);

    await t.tap(find.text('机型适配检查'));
    await settle(t);
    expect(find.byType(CompatScreen), findsOneWidget);
    expect(find.text('悬浮窗'), findsOneWidget);
    expect(find.text('64 位'), findsOneWidget);
    await t.tap(find.byTooltip('前往设置').first);
    await settle(t);
    expect(native.methods, contains('compatOpen'));

    Navigator.of(t.element(find.byType(CompatScreen))).pop();
    await settle(t, 30);
    // 「工具箱」在「机型适配」上方，弹回后滚到顶部再点 ListTile
    await t.drag(find.byType(Scrollable).first, const Offset(0, 800));
    await settle(t, 10);
    await t.tap(find.widgetWithText(ListTile, '工具箱'));
    await settle(t, 30);
    expect(find.byType(ToolboxScreen), findsOneWidget);
    await t.tap(find.descendant(of: find.byType(ToolboxScreen), matching: find.byType(Switch)));
    await settle(t);
    expect(find.text('需要悬浮窗权限'), findsOneWidget);
    await t.tap(find.text('去开启'));
    await settle(t, 40);
    expect(native.methods, contains('setToolboxEnabled'));
    expect(native.methods, contains('compatOpen'));

    Navigator.of(t.element(find.byType(ToolboxScreen))).pop();
    await settle(t);
    await t.scrollUntilVisible(find.text('本项目开源'), 400);
    expect(find.textContaining('流年似水'), findsOneWidget);
    expect(find.text('https://github.com/Liunian520/parallel_space'), findsOneWidget);
    expect(find.textContaining('1.3.13'), findsWidgets);
  });

  testWidgets('密码本可以直接添加、分组和删除', (t) async {
    final native = FakeNative()..currentPackage = 'com.tencent.mm';
    native.install(t);
    SharedPreferences.setMockInitialValues({
      'clones.v1.userOnly': true,
      'clones.v1.listPolicy': 3,
      'clones.v1':
          '[{"uid":"w:com.tencent.mm","pkg":"com.tencent.mm","name":"微信","appName":"微信","version":"1","category":"social","kind":"work","ua":true}]',
    });
    final prefs = await SharedPreferences.getInstance();
    await t.pumpWidget(buildProviders(prefs, child: const MaterialApp(home: VaultScreen())));
    await settle(t, 30);
    expect(find.text('还没有账号'), findsOneWidget);

    await t.tap(find.text('添加'));
    await settle(t, 30);
    expect(find.text('添加账号'), findsWidgets);
    // 0 = 页面搜索框；其后为备注名 / 账号 / 密码 / 备注
    final fields = find.byType(TextField);
    expect(fields, findsAtLeastNWidgets(4));
    await t.enterText(fields.at(1), '工作号');
    await t.enterText(fields.at(2), 'wx');
    await t.enterText(fields.at(3), 'secret');
    await t.tap(find.widgetWithText(FilledButton, '保存'));
    await settle(t, 40);

    expect(find.text('工作号'), findsOneWidget);
    expect(find.textContaining('当前分身'), findsOneWidget);
    expect(find.textContaining('微信'), findsWidgets);

    await t.tap(find.byType(PopupMenuButton<String>));
    await settle(t);
    await t.tap(find.text('删除'));
    await settle(t);
    await t.tap(find.widgetWithText(FilledButton, '删除'));
    await settle(t, 30);
    expect(find.text('还没有账号'), findsOneWidget);
  });
}
