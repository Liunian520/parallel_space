import 'dart:ui';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// 特效等级（由 App 根部根据“精简特效”设置提供）。
class Effects extends InheritedWidget {
  const Effects({super.key, required this.reduced, required super.child});
  final bool reduced;

  /// 未提供时按精简处理（性能优先）。
  static bool reducedOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<Effects>()?.reduced ?? true;

  @override
  bool updateShouldNotify(Effects old) => old.reduced != reduced;
}

/// 玻璃质感容器。
///
/// 性能：默认**不**使用 BackdropFilter（实时模糊需要每帧读取并模糊背后的像素，
/// 列表 / 网格里每张卡片一个会让中端机明显掉帧），而是用半透明渐变 + 描边模拟。
/// 只有悬浮层（底部弹窗、导航栏）在关闭“精简特效”时才设置 [blur] 使用真实模糊。
class Glass extends StatelessWidget {
  const Glass({
    super.key,
    required this.child,
    this.radius = AppTheme.radius,
    this.padding = EdgeInsets.zero,
    this.blur = false,
    this.opacity,
  });

  final Widget child;
  final double radius;
  final EdgeInsetsGeometry padding;
  final bool blur;
  final double? opacity;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final s = Theme.of(context).colorScheme;
    final br = BorderRadius.circular(radius);
    final live = blur && !Effects.reducedOf(context);
    // 没有实时模糊时提高不透明度，保证文字可读
    final a = opacity ?? (live ? (dark ? 0.10 : 0.55) : (dark ? 0.16 : 0.72));
    final box = DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: br,
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            (dark ? s.surfaceContainerHigh : Colors.white).withValues(alpha: dark ? a + 0.3 : a),
            (dark ? s.surfaceContainer : s.primaryContainer).withValues(alpha: dark ? a + 0.2 : a * 0.6),
          ],
        ),
        border: Border.all(color: Colors.white.withValues(alpha: dark ? 0.10 : 0.6), width: 1),
      ),
      child: Padding(padding: padding, child: child),
    );
    if (!live) return ClipRRect(borderRadius: br, child: box);
    return ClipRRect(
      borderRadius: br,
      child: BackdropFilter(filter: ImageFilter.blur(sigmaX: 18, sigmaY: 18), child: box),
    );
  }
}

/// 底部弹出面板的统一外壳（自适应键盘/安全区，内容过高时可滚动）。
class GlassSheet extends StatelessWidget {
  const GlassSheet({super.key, required this.child, this.padding = const EdgeInsets.fromLTRB(24, 20, 24, 24), this.scrollable = true});
  final Widget child;
  final bool scrollable;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final mq = MediaQuery.of(context);
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Padding(
      padding: EdgeInsets.fromLTRB(12, 0, 12, 12 + mq.viewInsets.bottom),
      child: Center(
        heightFactor: 1,
        child: ConstrainedBox(
          // 平板 / 折叠屏展开时不要铺满整个宽度
          constraints: BoxConstraints(maxWidth: 560, maxHeight: mq.size.height - mq.padding.top - 24 - mq.viewInsets.bottom),
          child: Glass(
            radius: 32,
            blur: true,
            opacity: Effects.reducedOf(context) ? (dark ? 0.62 : 0.95) : (dark ? 0.14 : 0.85),
            padding: padding,
            child: SafeArea(
              top: false,
              child: scrollable ? SingleChildScrollView(physics: const ClampingScrollPhysics(), child: child) : child,
            ),
          ),
        ),
      ),
    );
  }
}
