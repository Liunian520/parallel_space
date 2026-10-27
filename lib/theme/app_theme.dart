import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

class AppTheme {
  static const radius = 28.0;

  static ThemeData build(ColorScheme scheme) {
    final base = ThemeData(useMaterial3: true, colorScheme: scheme);
    final t = base.textTheme;
    return base.copyWith(
      scaffoldBackgroundColor: scheme.surface,
      textTheme: t.copyWith(
        displaySmall: t.displaySmall?.copyWith(fontWeight: FontWeight.w800, letterSpacing: -0.5),
        headlineMedium: t.headlineMedium?.copyWith(fontWeight: FontWeight.w800, letterSpacing: -0.5),
        titleLarge: t.titleLarge?.copyWith(fontWeight: FontWeight.w700),
        titleMedium: t.titleMedium?.copyWith(fontWeight: FontWeight.w600),
      ),
      splashFactory: InkSparkle.splashFactory,
      pageTransitionsTheme: const PageTransitionsTheme(builders: {
        TargetPlatform.android: CupertinoPageTransitionsBuilder(),
        TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
      }),
      bottomSheetTheme: const BottomSheetThemeData(backgroundColor: Colors.transparent),
      switchTheme: SwitchThemeData(
        thumbIcon: WidgetStateProperty.resolveWith((s) =>
            s.contains(WidgetState.selected) ? const Icon(Icons.check_rounded) : null),
      ),
    );
  }

  /// 品牌蓝青渐变。
  static LinearGradient brandGradient(ColorScheme s) => LinearGradient(
        colors: [s.primary, Color.lerp(s.primary, const Color(0xFF00E5FF), 0.6)!],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      );
}
