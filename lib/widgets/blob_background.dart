import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'glass.dart';

/// 柔和色块背景。
///
/// 性能：不再使用 ImageFiltered(blur 70)（每帧对整屏做高斯模糊）。
/// 改为“预模糊”的径向渐变圆（本身就是柔边，绘制成本极低），并放在 RepaintBoundary 内，
/// 与前景内容的重绘互相隔离。精简特效开启时完全静止（只绘制一次）；关闭时缓慢漂移。
class BlobBackground extends StatefulWidget {
  const BlobBackground({super.key, required this.child});
  final Widget child;

  @override
  State<BlobBackground> createState() => _BlobBackgroundState();
}

class _BlobBackgroundState extends State<BlobBackground> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(seconds: 40));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final animate = !Effects.reducedOf(context) && !MediaQuery.disableAnimationsOf(context);
    if (animate && !_c.isAnimating) {
      _c.repeat();
    } else if (!animate && _c.isAnimating) {
      _c.stop();
    }
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final a = dark ? 0.38 : 0.30;
    final colors = [s.primary.withValues(alpha: a), const Color(0xFF00E5FF).withValues(alpha: a * 0.8), s.tertiary.withValues(alpha: a)];
    return Stack(children: [
      Positioned.fill(
        child: RepaintBoundary(
          child: CustomPaint(painter: _BlobPainter(_c, colors, s.surface), isComplex: true, willChange: false),
        ),
      ),
      Positioned.fill(child: widget.child),
    ]);
  }
}

class _BlobPainter extends CustomPainter {
  _BlobPainter(this.anim, this.colors, this.bg) : super(repaint: anim);
  final Animation<double> anim;
  final List<Color> colors;
  final Color bg;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = bg);
    final t = anim.value * 2 * pi;
    const radii = [260.0, 220.0, 200.0];
    for (var i = 0; i < 3; i++) {
      final c = Offset(
        size.width * (0.5 + 0.4 * sin(t + i * 2.1)),
        size.height * (0.32 + 0.28 * cos(t * 0.8 + i * 1.7)),
      );
      final r = radii[i] * (size.shortestSide / 400).clamp(0.8, 1.8);
      canvas.drawCircle(
        c,
        r,
        Paint()
          ..shader = RadialGradient(colors: [colors[i], colors[i].withValues(alpha: 0)], stops: const [0, 1])
              .createShader(Rect.fromCircle(center: c, radius: r)),
      );
    }
  }

  @override
  bool shouldRepaint(_BlobPainter old) => !listEquals(old.colors, colors) || old.bg != bg;
}
