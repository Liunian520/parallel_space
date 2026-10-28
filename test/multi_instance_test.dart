import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/models/app_info.dart';
import 'package:parallel_space/models/clone_app.dart';
import 'package:parallel_space/services/vault_group.dart';
import 'package:parallel_space/state/clone_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('实例 id', () {
    test('第一个分身沿用旧格式，其余带 #userId', () {
      expect(CloneApp.uidFor('com.tencent.mm'), 'w:com.tencent.mm');
      expect(CloneApp.uidFor('com.tencent.mm', 0), 'w:com.tencent.mm');
      expect(CloneApp.uidFor('com.tencent.mm', 2), 'w:com.tencent.mm#2');
      expect(CloneApp.userIdOf('w:com.tencent.mm#2'), 2);
      expect(CloneApp.userIdOf('w:com.tencent.mm'), 0);
      expect(CloneApp.pkgOf('w:com.tencent.mm#2'), 'com.tencent.mm');
      expect(CloneApp.pkgOf('w:com.tencent.mm'), 'com.tencent.mm');
    });

    test('默认名：分身N；自定义名优先', () {
      final a = CloneApp(uid: 'w:a', packageName: 'a', appName: '微信');
      final b = CloneApp(uid: 'w:a#1', packageName: 'a', appName: '微信', userId: 1);
      expect(a.customName, '微信');
      expect(b.customName, '微信 分身2');
      b.customLabel = '工作号';
      expect(b.customName, '工作号');
    });

    test('json 往返保留 userId；旧数据无 u 视为 0', () {
      final b = CloneApp(uid: 'w:a#3', packageName: 'a', appName: 'A', userId: 3);
      final back = CloneApp.fromJson(jsonDecode(jsonEncode(b.toJson())) as Map<String, dynamic>)!;
      expect(back.userId, 3);
      expect(back.uid, 'w:a#3');
      final old = CloneApp.fromJson({'uid': 'w:a', 'pkg': 'a', 'kind': 'work', 'appName': 'A', 'ua': true})!;
      expect(old.userId, 0);
    });
  });

  group('CloneStore 多实例', () {
    Future<CloneStore> mk(List<Map<String, Object?>> clones) async {
      SharedPreferences.setMockInitialValues({'clones.v1': jsonEncode(clones)});
      return CloneStore(await SharedPreferences.getInstance());
    }

    test('同包名的两个实例都保留（旧版会去重）', () async {
      final s = await mk([
        {'uid': 'w:com.x', 'pkg': 'com.x', 'kind': 'work', 'appName': 'X', 'ua': true},
        {'uid': 'w:com.x#1', 'pkg': 'com.x', 'kind': 'work', 'appName': 'X', 'u': 1, 'ua': true},
      ]);
      expect(s.clones.length, 2);
      expect(s.instancesOf('com.x').map((c) => c.userId), [0, 1]);
      expect(s.resolve('w:com.x#1')?.userId, 1);
      expect(s.resolve('w:com.x')?.userId, 0);
    });

    test('sync 按 包名+userId 匹配，只移除消失的实例', () async {
      final s = await mk([
        {'uid': 'w:com.x', 'pkg': 'com.x', 'kind': 'work', 'appName': 'X', 'ua': true},
        {'uid': 'w:com.x#1', 'pkg': 'com.x', 'kind': 'work', 'appName': 'X', 'u': 1, 'ua': true},
      ]);
      s.sync(const [AppInfo(packageName: 'com.x', name: 'X', userId: 1)]);
      expect(s.clones.map((c) => c.uid), ['w:com.x#1']);
    });

    test('addFromApp 新实例；改名同步给原生', () async {
      final s = await mk([
        {'uid': 'w:com.x', 'pkg': 'com.x', 'kind': 'work', 'appName': 'X', 'ua': true},
      ]);
      List<Map<String, Object?>>? sent;
      s.onNames = (items) => sent = items;
      s.addFromApp(const AppInfo(packageName: 'com.x', name: 'X', userId: 2));
      expect(s.clones.length, 2);
      final c = s.byInstance('com.x', 2)!;
      s.update(c, (c) => c.customLabel = '小号');
      expect(sent!.firstWhere((m) => m['userId'] == 2)['label'], '小号');
      expect(sent!.firstWhere((m) => m['userId'] == 0)['label'], 'X');
    });
  });

  test('密码本按实例关联；旧条目归第一个分身', () {
    const old = VaultEntry(id: '1', packageName: 'com.x', cloneUid: 'w:com.x', label: 'a', username: '', password: '');
    const second = VaultEntry(id: '2', packageName: 'com.x', cloneUid: 'w:com.x#1', label: 'b', username: '', password: '');
    expect(vaultEntryMatchesInstance(old, 'w:com.x'), isTrue);
    expect(vaultEntryMatchesInstance(old, 'w:com.x#1'), isFalse);
    expect(vaultEntryMatchesInstance(second, 'w:com.x#1'), isTrue);
    expect(vaultEntryMatchesInstance(second, 'w:com.x'), isFalse);
  });

  test('添加分身逻辑：已有分身再 addFromApp 得到分身2', () async {
    SharedPreferences.setMockInitialValues({
      'clones.v1': jsonEncode([
        {'uid': 'w:com.x', 'pkg': 'com.x', 'kind': 'work', 'appName': '小雅', 'ua': true},
      ]),
    });
    final store = CloneStore(await SharedPreferences.getInstance());
    expect(store.instancesOf('com.x').length, 1);
    store.addFromApp(const AppInfo(packageName: 'com.x', name: '小雅', userId: 1));
    expect(store.instancesOf('com.x').map((c) => c.userId).toList(), [0, 1]);
    expect(store.byInstance('com.x', 1)!.customName, '小雅 分身2');
  });
}
