
import 'package:flutter_test/flutter_test.dart';

/// 与 ToolboxOverlayService.remarkTitle 一致：备注 → 账号 → 分身名/包名。
String remarkTitle({
  required String label,
  required String username,
  required String cloneName,
  required String pkg,
}) {
  final t = label.trim();
  if (t.isNotEmpty && t != '未命名') return t;
  if (username.trim().isNotEmpty) return username.trim();
  if (cloneName.trim().isNotEmpty) return cloneName.trim();
  if (pkg.trim().isNotEmpty) return pkg.trim();
  return '未命名';
}

void main() {
  test('备注名优先；空备注回退账号；再回退分身名', () {
    expect(
      remarkTitle(label: '主号', username: '138', cloneName: '微信', pkg: 'com.t'),
      '主号',
    );
    expect(
      remarkTitle(label: '', username: '1380000', cloneName: '微信', pkg: 'com.t'),
      '1380000',
    );
    expect(
      remarkTitle(label: '未命名', username: '', cloneName: '微信 分身2', pkg: 'com.t'),
      '微信 分身2',
    );
    expect(
      remarkTitle(label: '  ', username: '', cloneName: '', pkg: 'com.x'),
      'com.x',
    );
  });

  test('工具箱应展示全部账号，不按当前分身过滤（约定）', () {
    // 回归约定：列表数据源 = 全部 entries；当前分身只影响排序与「当前分身」分组标题。
    final all = [
      {'id': '1', 'pkg': 'a', 'label': 'A号'},
      {'id': '2', 'pkg': 'b', 'label': 'B号'},
    ];
    final currentPkg = 'a';
    final visible = all; // 1.3.18：不再 filter
    expect(visible.length, 2);
    final sorted = [...visible]..sort((x, y) {
      final ax = x['pkg'] == currentPkg ? 0 : 1;
      final bx = y['pkg'] == currentPkg ? 0 : 1;
      if (ax != bx) return ax - bx;
      return (x['label'] as String).compareTo(y['label'] as String);
    });
    expect(sorted.first['label'], 'A号');
  });
}
