import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/main.dart';
import 'package:parallel_space/screens/clone_detail_screen.dart';
import 'package:parallel_space/screens/vault_screen.dart';
import 'package:parallel_space/services/vault_group.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'fake_native.dart';
import 'vault_test.dart' show settle;

const _clones = '''
[
  {"uid":"w:com.tencent.mm","pkg":"com.tencent.mm","name":"大号","appName":"微信","u":0,"version":"1","category":"social","kind":"work","ua":true},
  {"uid":"w:com.tencent.mm#1","pkg":"com.tencent.mm","name":"小号","appName":"微信","u":1,"version":"1","category":"social","kind":"work","ua":true}
]
''';

Future<void> _pump(WidgetTester t, Widget home) async {
  SharedPreferences.setMockInitialValues({
    'clones.v1.userOnly': true,
    'clones.v1.listPolicy': 3,
    'clones.v1': _clones,
  });
  final prefs = await SharedPreferences.getInstance();
  await t.pumpWidget(buildProviders(prefs, child: MaterialApp(home: home)));
  await settle(t, 30);
}

Future<void> _saveFirstAccount(WidgetTester t) async {
  expect(find.text('添加账号'), findsWidgets);
  final fields = find.byType(TextField);
  expect(fields, findsAtLeastNWidgets(4));
  await t.enterText(fields.at(1), '第二份里加的');
  await t.enterText(fields.at(2), 'alice');
  await t.enterText(fields.at(3), 'p@ss-unique');
  await t.tap(find.widgetWithText(FilledButton, '保存'));
  await settle(t, 40);
}

void main() {
  testWidgets('第二份分身详情新建账号保留该实例 uid，并按自定义名搜索', (t) async {
    final native = FakeNative();
    native.install(t);
    await _pump(t, const CloneDetailScreen(uid: 'w:com.tencent.mm#1'));
    await t.scrollUntilVisible(find.text('管理此分身账号'), 200);
    await t.tap(find.text('管理此分身账号'));
    await settle(t, 40);
    await _saveFirstAccount(t);

    expect(native.vault, hasLength(1));
    expect(native.vault.single['clone'], 'w:com.tencent.mm#1');
    expect(native.vault.single['pkg'], 'com.tencent.mm');
    final saved = VaultEntry.fromMap(native.vault.single)!;
    const names = {'w:com.tencent.mm': '大号', 'w:com.tencent.mm#1': '小号'};
    expect(filterVaultEntries([saved], '小号', cloneNames: names).single.id, saved.id);
    expect(filterVaultEntries([saved], '大号', cloneNames: names), isEmpty);
    expect(filterVaultEntries([saved], 'p@ss-unique', cloneNames: names), isEmpty);
  });

  testWidgets('第一份分身详情和悬浮入口仍记到各自的实例', (t) async {
    final native = FakeNative();
    native.install(t);
    await _pump(t, const CloneDetailScreen(uid: 'w:com.tencent.mm'));
    await t.scrollUntilVisible(find.text('管理此分身账号'), 200);
    await t.tap(find.text('管理此分身账号'));
    await settle(t, 40);
    await _saveFirstAccount(t);
    expect(native.vault.single['clone'], 'w:com.tencent.mm');

    native.vault.clear();
    await t.pumpWidget(const SizedBox.shrink());
    await settle(t, 5);
    await _pump(t, const VaultScreen(initialPackage: 'w:com.tencent.mm#1', openEditor: true));
    await _saveFirstAccount(t);
    expect(native.vault.single['clone'], 'w:com.tencent.mm#1');
  });
}
