import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/screens/toolbox_screen.dart';

void main() {
  test('复制最近 50 行：只保留末尾条目', () {
    final log = List.generate(80, (i) => 'line $i').join('\n---\n');
    final r = recentLogEntries(log, 50).split('\n');
    expect(r.length, 50);
    expect(r.first, 'line 30');
    expect(r.last, 'line 79');
  });
  test('空日志返回空', () => expect(recentLogEntries('', 50), ''));
}
