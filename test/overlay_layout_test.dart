
import 'package:flutter_test/flutter_test.dart';

/// 与 ToolboxOverlayService.clampOverlayPosition 保持一致的纯函数，防止回归。
(int, int) clampOverlayPosition(
  int x,
  int y,
  int winW,
  int winH,
  int screenW,
  int screenH,
) {
  final maxX = (screenW - winW) < 0 ? 0 : (screenW - winW);
  final maxY = (screenH - winH) < 0 ? 0 : (screenH - winH);
  final nx = x.clamp(0, maxX);
  final ny = y.clamp(0, maxY);
  return (nx, ny);
}

void main() {
  test('气泡在右边缘展开 320dp 面板时，x 必须左移到屏内', () {
    // Redmi K80 常见：宽 1440，气泡 x=1320，面板宽 1120（320dp@3.5）
    final (x, y) = clampOverlayPosition(1320, 847, 1120, 900, 1440, 3200);
    expect(x, 1440 - 1120); // 320
    expect(y, 847);
    expect(x + 1120, lessThanOrEqualTo(1440));
  });

  test('已经在屏内的坐标保持不变', () {
    final (x, y) = clampOverlayPosition(100, 200, 320, 400, 1440, 3200);
    expect(x, 100);
    expect(y, 200);
  });

  test('负坐标与超出都夹紧', () {
    final (x, y) = clampOverlayPosition(-50, 5000, 300, 300, 1000, 2000);
    expect(x, 0);
    expect(y, 2000 - 300);
  });
}
