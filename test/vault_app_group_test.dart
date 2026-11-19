import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/services/vault_group.dart';

const _dot = '\u00B7';

VaultAppRow _row(String id, String pkg, String label, {bool current = false}) =>
    VaultAppRow(id: id, pkg: pkg, label: label, currentClone: current);

List<VaultAppRow> _fixture({bool currentA = true}) => [
      _row('b', 'com.z', '乙'),
      _row('a', 'com.a', '甲', current: currentA),
      _row('c', 'com.a', '甲'),
      _row('d', 'com.a', '乙'),
      _row('e', 'com.m', ''),
    ];

void main() {
  test('前台包名 com.z 时该段在最前，段内按当前分身、备注名、id', () {
    final sections = groupVaultByPackage(_fixture(), 'com.z');
    expect(sections.map((s) => s.pkg), ['com.z', 'com.a', 'com.m']);
    expect(sections[1].rows.map((r) => r.id), ['a', 'd', 'c']);
    expect(sections.expand((s) => s.rows).map((r) => r.id).toSet(), {'a', 'b', 'c', 'd', 'e'});
    expect(sections.expand((s) => s.rows).length, 5);
  });

  test('前台包名为空且没有当前分身时按包名和备注名排序', () {
    final sections = groupVaultByPackage(_fixture(currentA: false), '');
    expect(sections.map((s) => s.pkg), ['com.a', 'com.m', 'com.z']);
    expect(sections.first.rows.map((r) => r.id), ['d', 'a', 'c']);
  });

  test('空包名仍保留并排在最后', () {
    final rows = [..._fixture(), _row('z', '', '无名')];
    final sections = groupVaultByPackage(rows, 'com.z');
    expect(sections.last.pkg, '');
    expect(sections.last.rows.single.id, 'z');
    expect(vaultAppHeading('', VaultAppKind.unavailable), '应用信息不可用');
    expect(sections.expand((s) => s.rows).length, 6);
  });

  test('标题全文：未解析用包名，查不到不写成未安装', () {
    expect(vaultAppHeading('com.qq', VaultAppKind.pending), 'com.qq');
    expect(vaultAppHeading('com.qq', VaultAppKind.named, '微信'), '微信');
    expect(vaultAppHeading('com.qq', VaultAppKind.named, '  '), 'com.qq $_dot 应用信息不可用');
    expect(vaultAppHeading('com.qq', VaultAppKind.unavailable), 'com.qq $_dot 应用信息不可用');
    expect(vaultAppHeading('com.qq', VaultAppKind.reliablyUninstalled), 'com.qq $_dot 未安装');
    expect(vaultAppHeading('com.qq', VaultAppKind.unavailable).contains('未安装'), isFalse);
  });

  test('搜索命中原字段、应用名和所属分身名，不命中密码', () {
    const items = [
      VaultEntry(
        id: '1',
        packageName: 'com.tencent.mm',
        cloneUid: 'w:com.tencent.mm#1',
        label: '主号',
        username: 'alice',
        password: 'p@ss-unique',
        note: '宿舍',
      ),
    ];
    const sync = {
      'com.tencent.mm': ['微信'],
    };
    const clones = {
      'w:com.tencent.mm': '大号',
      'w:com.tencent.mm#1': '小号',
    };
    List<VaultEntry> hit(String q) => filterVaultEntries(
          items,
          q,
          syncAppNames: sync,
          cloneNames: clones,
        );
    expect(hit(''), items);
    for (final q in ['alice', '宿舍', 'com.tencent', 'w:com.tencent.mm#1', '主号', '微信', '小号']) {
      expect(hit(q).map((e) => e.id), ['1'], reason: q);
    }
    for (final q in ['大号', 'p@ss-unique', '应用信息不可用', '未安装']) {
      expect(hit(q), isEmpty, reason: q);
    }
  });

  test('空 clone 的旧记录只匹配第一份分身的自定义名', () {
    const old = VaultEntry(
      id: 'old',
      packageName: 'com.tencent.mm',
      cloneUid: '',
      label: '旧号',
      username: '',
      password: 'secret-old',
    );
    const names = {
      'w:com.tencent.mm': '大号',
      'w:com.tencent.mm#1': '小号',
    };
    expect(filterVaultEntries([old], '大号', cloneNames: names).single.id, 'old');
    expect(filterVaultEntries([old], '小号', cloneNames: names), isEmpty);
    expect(filterVaultEntries([old], 'secret-old', cloneNames: names), isEmpty);
  });

  test('旧代际的应用名不应用，当前代际使用此刻的查询词', () {
    const items = [
      VaultEntry(
        id: '1',
        packageName: 'com.tencent.mm',
        cloneUid: 'w:com.tencent.mm',
        label: '主号',
        username: 'alice',
        password: 'p@ss-unique',
      ),
    ];
    expect(
      acceptVaultLabelResponse(
        responseGen: 1,
        currentGen: 2,
        names: const {'com.tencent.mm': '旧名'},
        queryNow: '旧名',
      ),
      isNull,
    );
    final applied = acceptVaultLabelResponse(
      responseGen: 2,
      currentGen: 2,
      names: const {'com.tencent.mm': '微信'},
      queryNow: '微信',
    );
    expect(applied, isNotNull);
    expect(applied!.query, '微信');
    expect(
      filterVaultEntries(items, applied.query, asyncAppLabels: applied.names).single.label,
      '主号',
    );
  });
}
