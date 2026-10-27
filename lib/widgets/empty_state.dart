import 'dart:math';

import 'package:flutter/material.dart';

import 'glass.dart';

import '../theme/app_theme.dart';
import 'pressable.dart';

/// 空状态：纯 Flutter 动画插画（漂浮的双手机 + 轨道粒子）。
class EmptyState extends StatefulWidget {
  const EmptyState({super.key, required this.onAdd});
  final VoidCallback onAdd;

  @override
  State<EmptyState> createState() => _EmptyStateState();
}

class _EmptyStateState extends State<EmptyState> with SingleTickerProviderStateMixin {
  late final AnimationController _c =
      AnimationController(vsync: this, duration: const Duration(seconds: 4));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 精简特效：不做循环动画（避免首页持续重绘）
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

  Widget _phone(ColorScheme s, double opacity) => Container(
        width: 74,
        height: 128,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20),
          gradient: AppTheme.brandGradient(s),
          boxShadow: [BoxShadow(color: s.primary.withValues(alpha: 0.35), blurRadius: 24, offset: const Offset(0, 10))],
        ),
        foregroundDecoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20), color: s.surface.withValues(alpha: 1 - opacity)),
        child: const Icon(Icons.apps_rounded, color: Colors.white, size: 34),
      );

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          SizedBox(
            width: 220,
            height: 200,
            child: AnimatedBuilder(
              animation: _c,
              builder: (context, _) {
                final t = _c.value * 2 * pi;
                return Stack(alignment: Alignment.center, children: [
                  for (var i = 0; i < 5; i++)
                    Transform.translate(
                      offset: Offset(cos(t + i * 1.25) * 95, sin(t + i * 1.25) * 40),
                      child: Container(
                        width: 8.0 + i % 3 * 3,
                        height: 8.0 + i % 3 * 3,
                        decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: (i.isEven ? s.primary : const Color(0xFF00E5FF)).withValues(alpha: 0.7)),
                      ),
                    ),
                  Transform.translate(
                    offset: Offset(28 + sin(t) * 6, -6 + cos(t) * 6),
                    child: Transform.rotate(angle: 0.18, child: _phone(s, 0.45)),
                  ),
                  Transform.translate(
                    offset: Offset(-18, sin(t) * 8),
                    child: Transform.rotate(angle: -0.08, child: _phone(s, 1)),
                  ),
                ]);
              },
            ),
          ),
          const SizedBox(height: 24),
          Text('还没有分身', style: tt.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          Text('同一个应用，开启多个账号\n互不干扰，数据隔离',
              textAlign: TextAlign.center, style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant, height: 1.5)),
          const SizedBox(height: 28),
          Pressable(
            onTap: widget.onAdd,
            radius: 30,
            child: Ink(
              padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 16),
              decoration: BoxDecoration(borderRadius: BorderRadius.circular(30), gradient: AppTheme.brandGradient(s)),
              child: const Row(mainAxisSize: MainAxisSize.min, children: [
                Icon(Icons.add_rounded, color: Colors.white),
                SizedBox(width: 8),
                Text('添加你的第一个分身', style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 16)),
              ]),
            ),
          ),
        ]),
      ),
    );
  }
}
