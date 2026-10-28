import 'dart:async';

import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'screens/root_shell.dart';
import 'services/native_bridge.dart';
import 'state/clone_store.dart';
import 'state/location_store.dart';
import 'state/private_space_store.dart';
import 'services/update_service.dart';
import 'state/settings_store.dart';
import 'theme/app_theme.dart';
import 'widgets/glass.dart';

final navigatorKey = GlobalKey<NavigatorState>();

Future<void> main() async {
  runZonedGuarded(() async {
    WidgetsFlutterBinding.ensureInitialized();
    installErrorGuards();
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    final prefs = await SharedPreferences.getInstance();
    runApp(buildProviders(prefs, child: const ParallelSpaceApp()));
  }, (e, st) => debugPrint('Uncaught: $e\n$st'));
}

/// 发布版默认的 ErrorWidget 是一整块灰色，无法排查；改为可读的错误卡片。
void installErrorGuards() {
  final prev = FlutterError.onError;
  FlutterError.onError = (d) {
    FlutterError.presentError(d);
    if (prev != null && prev != FlutterError.presentError) prev(d);
  };
  ErrorWidget.builder = (d) => ErrorCard(details: d);
}

class ErrorCard extends StatelessWidget {
  const ErrorCard({super.key, required this.details});
  final FlutterErrorDetails details;

  @override
  Widget build(BuildContext context) {
    final msg = details.exceptionAsString();
    return Directionality(
      textDirection: TextDirection.ltr,
      child: ColoredBox(
        color: const Color(0xFFFFF3F0),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
              const Text('页面出错了',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700, color: Color(0xFFB3261E), decoration: TextDecoration.none)),
              const SizedBox(height: 8),
              Text(msg.length > 1200 ? '${msg.substring(0, 1200)}…' : msg,
                  style: const TextStyle(fontSize: 13, color: Color(0xFF410E0B), decoration: TextDecoration.none)),
              const SizedBox(height: 12),
              if (details.library != null)
                Text('来源：${details.library}',
                    style: const TextStyle(fontSize: 12, color: Color(0xFF7A4A45), decoration: TextDecoration.none)),
              const SizedBox(height: 12),
              GestureDetector(
                onTap: () => Clipboard.setData(ClipboardData(text: '$msg\n${details.stack}')),
                child: const Text('点此复制错误详情',
                    style: TextStyle(fontSize: 14, color: Color(0xFF1565C0), decoration: TextDecoration.underline)),
              ),
            ]),
          ),
        ),
      ),
    );
  }
}

/// 构造状态；本地数据损坏导致构造失败时，清空本 App 的偏好设置后重建（安全降级）。
T _safe<T>(SharedPreferences prefs, T Function() make) {
  try {
    return make();
  } catch (e, st) {
    debugPrint('State init failed, resetting prefs: $e\n$st');
    prefs.clear();
    return make();
  }
}

/// 全局状态（测试中复用）。
Widget buildProviders(SharedPreferences prefs, {required Widget child}) => MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => _safe(prefs, () => CloneStore(prefs))),
        ChangeNotifierProvider(create: (_) => _safe(prefs, () => SettingsStore(prefs))),
        ChangeNotifierProvider(create: (_) => _safe(prefs, () => LocationStore(prefs))),
        ChangeNotifierProvider(create: (_) => _safe(prefs, () => PrivateSpaceStore(prefs)..attach())),
        Provider(create: (_) => UpdateService(prefs)),
      ],
      child: Builder(builder: (context) {
        final store = context.read<CloneStore>();
        // 分身变化后同步桌面小组件
        store.onPersisted ??= () => NativeBridge.updateWidget(store.widgetItems());
        // 分身名 → 原生（桌面快捷方式名、最近任务名）
        store.onNames ??= (items) => NativeBridge.syncCloneNames(items);
        NativeBridge.syncCloneNames(store.nameItems());
        return child;
      }),
    );

class ParallelSpaceApp extends StatelessWidget {
  const ParallelSpaceApp({super.key});

  @override
  Widget build(BuildContext context) {
    final st = context.watch<SettingsStore>();
    return DynamicColorBuilder(builder: (lightDynamic, darkDynamic) {
      final useDyn = st.useDynamicColor && lightDynamic != null && darkDynamic != null;
      // dynamic_color 2.x 返回 package:material_ui 的 ColorScheme，与 Flutter Material 的
      // ColorScheme 不是同一类型（强转会在 Android 12+ 抛异常 → 发布版整屏灰色）。
      // 只取系统取色的 primary 作为种子，再用 Flutter 自己的 ColorScheme 生成。
      final Color seed = useDyn ? Color(lightDynamic.primary.toARGB32()) : st.seed;
      final light = ColorScheme.fromSeed(seedColor: seed, tertiary: useDyn ? null : const Color(0xFF00BCD4));
      final dark = ColorScheme.fromSeed(
          seedColor: seed, brightness: Brightness.dark, tertiary: useDyn ? null : const Color(0xFF4DD0E1));
      return MaterialApp(
        title: '平行空间',
        debugShowCheckedModeBanner: false,
        themeMode: st.themeMode,
        theme: AppTheme.build(light),
        darkTheme: AppTheme.build(dark),
        navigatorKey: navigatorKey,
        home: RootShell(navigatorKey: navigatorKey),
        builder: (context, child) {
          final mq = MediaQuery.of(context);
          // 大字体适配：最大 1.3 倍，保证 320dp 小屏也不溢出
          return MediaQuery(
            data: mq.copyWith(textScaler: mq.textScaler.clamp(minScaleFactor: 0.85, maxScaleFactor: 1.3)),
            child: Effects(reduced: st.reduceEffects, child: child ?? const SizedBox.shrink()),
          );
        },
      );
    });
  }
}
