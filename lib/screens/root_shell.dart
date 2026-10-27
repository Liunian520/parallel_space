import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import '../state/location_store.dart';
import '../widgets/blob_background.dart';
import '../widgets/floating_nav_bar.dart';
import 'home_screen.dart';
import 'settings_screen.dart';
import 'vault_screen.dart';
import '../services/update_service.dart';
import '../state/settings_store.dart';
import '../widgets/update_sheet.dart';

class RootShell extends StatefulWidget {
  const RootShell({super.key, required this.navigatorKey});
  final GlobalKey<NavigatorState> navigatorKey;

  @override
  State<RootShell> createState() => _RootShellState();
}

class _RootShellState extends State<RootShell> with WidgetsBindingObserver {
  int _index = 0;
  Timer? _freezeTimer;
  bool _freezing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    NativeBridge.listen(
      onOpenClone: _openFromShortcut,
      onMockChanged: _syncMock,
      onPinned: PinWatcher.notifyPinned,
      onPermChanged: (overlay, _) async {
        if (!mounted) return;
        final settings = context.read<SettingsStore>();
        if (settings.toolboxOverlay && overlay) {
          await NativeBridge.setToolboxEnabled(true);
        }
      },
      onOpenVault: _openVault,
    );
    _freezeTimer = Timer.periodic(const Duration(seconds: 30), (_) => _autoFreeze());
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted) return;
      final store = context.read<CloneStore>();
      await store.refresh();
      if (!mounted) return;
      NativeBridge.updateWidget(store.widgetItems());
      await _syncMock();
      final id = await NativeBridge.launchCloneId();
      if (id != null) _openFromShortcut(id);
      final vault = await NativeBridge.launchVault();
      if (vault != null && vault['open'] == true) {
        _openVault(vault['pkg'] is String ? vault['pkg'] as String : '');
      }
      _autoFreeze();
      if (!mounted) return;
      final settings = context.read<SettingsStore>();
      await NativeBridge.setToolboxEnabled(settings.toolboxOverlay);
      if (!mounted) return;
      final upd = context.read<UpdateService>();
      upd.autoCheck = settings.autoCheckUpdate;
      if (settings.autoCheckUpdate && upd.dueForAutoCheck) {
        final r = await upd.check(force: false);
        if (!mounted) return;
        if (r.kind == UpdateCheckResultKind.available && r.manifest != null) {
          await showUpdateSheet(context, upd, r.manifest!);
        }
      }
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _freezeTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _syncMock(); // 可能在快捷开关 / 通知中被停止
      // 系统设置中可能开关了工作应用 / 卸载了应用
      context.read<CloneStore>().refresh().then((_) => _autoFreeze());
      final settings = context.read<SettingsStore>();
      NativeBridge.setToolboxEnabled(settings.toolboxOverlay);
    }
  }

  /// 原生服务状态与界面状态对齐。
  Future<void> _syncMock() async {
    final running = await NativeBridge.isMocking();
    if (!mounted) return;
    final store = context.read<CloneStore>();
    final loc = context.read<LocationStore>();
    if (!running) {
      if (loc.isActive) loc.stopRoute();
      if (store.mockingUid != null) store.setMocking(null);
    }
  }

  Future<void> _autoFreeze() async {
    if (_freezing || !mounted) return;
    _freezing = true;
    try {
      final store = context.read<CloneStore>();
      if (!store.ready) return;
      for (final c in store.dueForAutoFreeze(DateTime.now())) {
        if (!mounted) return;
        final ok = await setCloneFrozen(context, c, true, quiet: true);
        // 真实分身冻结失败（非资料所有者等）：顺延，避免每 30 秒重复请求系统
        if (!ok && mounted) store.update(c, (c) => c.lastActiveMs = DateTime.now().millisecondsSinceEpoch);
      }
    } finally {
      _freezing = false;
    }
  }

  /// 工具箱「去密码本添加」→ 打开密码本（可预填当前分身）。
  void _openVault(String pkg) {
    final nav = widget.navigatorKey.currentState;
    if (nav == null || !mounted) return;
    nav.popUntil((r) => r.isFirst);
    openVaultScreen(nav.context, initialPackage: pkg, openEditor: pkg.isNotEmpty);
  }

  /// 桌面快捷方式 / 小组件 → 打开对应分身。
  void _openFromShortcut(String id) {
    final nav = widget.navigatorKey.currentState;
    if (nav == null || !mounted) return;
    final c = context.read<CloneStore>().resolve(id);
    nav.popUntil((r) => r.isFirst);
    if (c == null) {
      showGlassSnack(context, '找不到这个分身，快捷方式已失效');
      return;
    }
    setState(() => _index = 0);
    openClone(nav, c);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBody: true,
      body: BlobBackground(
        child: AnimatedSwitcher(
          duration: const Duration(milliseconds: 350),
          transitionBuilder: (child, a) => FadeTransition(
            opacity: a,
            child: SlideTransition(
              position: Tween(begin: const Offset(0, 0.03), end: Offset.zero).animate(a),
              child: child,
            ),
          ),
          child: _index == 0
              ? HomeScreen(key: const ValueKey('home'), onOpenSettings: () => setState(() => _index = 1))
              : const SettingsScreen(key: ValueKey('settings'), embedded: true),
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.only(left: 20, bottom: 12),
          child: Align(
            alignment: Alignment.bottomLeft,
            heightFactor: 1,
            child: FloatingNavBar(
              index: _index,
              onChanged: (i) => setState(() => _index = i),
              items: const [
                NavItem(Icons.grid_view_outlined, Icons.grid_view_rounded, '分身'),
                NavItem(Icons.settings_outlined, Icons.settings_rounded, '设置'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
