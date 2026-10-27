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
  test('版本号只加最后一位：1.3.7 → 12307', () {
    expect(kAppVersionName, '1.3.7');
    expect(kAppVersionCode, 12307);
    expect(encodeVersionCode(1, 3, 7), kAppVersionCode);
    expect(versionCodeFromName('1.3.7'), 12307);
    expect(encodeVersionCode(1, 3, 7), greaterThan(12302));
    expect(encodeVersionCode(1, 3, 7), greaterThan(10305));
    expect(encodeVersionCode(1, 3, 7), greaterThan(12306));
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
    expect(find.text('机型适配检查'), findsOneWidget);

    await t.tap(find.text('机型适配检查'));
    await settle(t);
    expect(find.byType(CompatScreen), findsOneWidget);
    expect(find.text('悬浮窗'), findsOneWidget);
    expect(find.text('64 位'), findsOneWidget);
    await t.tap(find.byTooltip('前往设置'));
    await settle(t);
    expect(native.methods, contains('compatOpen'));

    Navigator.of(t.element(find.byType(CompatScreen))).pop();
    await settle(t);
    await t.tap(find.text('分身运行时的悬浮小窗，第一项是账号密码本'));
    await settle(t);
    expect(find.byType(ToolboxScreen), findsOneWidget);
    await t.tap(find.descendant(of: find.byType(ToolboxScreen), matching: find.byType(Switch)));
    await settle(t);
    expect(find.text('需要悬浮窗权限'), findsOneWidget);
    await t.tap(find.text('去开启'));
    await settle(t);
    expect(native.methods, contains('setToolboxEnabled'));

    Navigator.of(t.element(find.byType(ToolboxScreen))).pop();
    await settle(t);
    await t.scrollUntilVisible(find.text('本项目开源'), 400);
    expect(find.textContaining('流年似水'), findsOneWidget);
    expect(find.text('https://github.com/Liunian520/parallel_space'), findsOneWidget);
    expect(find.textContaining('1.3.7'), findsWidgets);
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
    expect(find.text('还没有账号。可以按分身或包名分别保存。'), findsOneWidget);

    await t.tap(find.text('添加'));
    await settle(t);
    await t.enterText(find.widgetWithText(TextField, '名称'), '工作号');
    await t.enterText(find.widgetWithText(TextField, '用户名'), 'wx');
    await t.enterText(find.widgetWithText(TextField, '密码'), 'secret');
    await t.tap(find.text('保存'));
    await settle(t, 30);

    expect(find.text('工作号'), findsOneWidget);
    expect(find.textContaining('当前分身'), findsOneWidget);
    expect(find.textContaining('微信'), findsWidgets);

    await t.tap(find.byType(PopupMenuButton<String>));
    await settle(t);
    await t.tap(find.text('删除'));
    await settle(t);
    await t.tap(find.widgetWithText(FilledButton, '删除'));
    await settle(t, 30);
    expect(find.text('还没有账号。可以按分身或包名分别保存。'), findsOneWidget);
  });
}
