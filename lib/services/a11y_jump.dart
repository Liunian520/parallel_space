import 'package:flutter/material.dart';

import '../widgets/glass.dart';
import 'clone_actions.dart' show showGlassSnack;
import 'native_bridge.dart';

/// 无障碍状态。
enum A11yState { on, off, restricted, unknown }

Future<A11yState> loadA11yState() async {
  final m = await NativeBridge.a11yStatus();
  if (m.isEmpty) return A11yState.unknown;
  if (m['on'] == true) return A11yState.on;
  if (m['restricted'] == true) return A11yState.restricted;
  return A11yState.off;
}

String a11yJumpMessage(String r) => switch (r) {
      'on' => '无障碍已开启，可一键填入',
      'restricted' => '先点右上角「⋮ → 允许受限制的设置」，验证后返回，再点一次「去开启」',
      'details' => '已打开「Alter Ego」无障碍开关页，打开开关即可',
      'list' => '请在列表（可能在「已下载的应用 / 已安装的服务」里）找到「Alter Ego」并开启',
      _ => '打不开设置页，请手动前往：设置 → 无障碍 → Alter Ego',
    };

/// 智能跳转：已开 → 提示；被「受限制的设置」拦着 → 先说明再跳应用详情；否则直达本服务开关页。
Future<String> openAccessibilitySmart(BuildContext context) async {
  final st = await loadA11yState();
  if (!context.mounted) return 'error';
  if (st == A11yState.on) {
    showGlassSnack(context, a11yJumpMessage('on'));
    return 'on';
  }
  if (st == A11yState.restricted) {
    final go = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('需要先「允许受限制的设置」'),
        content: const Text(
          'Android 13 及以上（含 HyperOS）对非应用商店安装的应用，会把无障碍开关置灰。\n\n'
          '1. 接下来会打开「Alter Ego」的应用详情页\n'
          '2. 点右上角「⋮」→「允许受限制的设置」，按提示验证\n'
          '3. 返回这里再点一次「去开启」，就会直达无障碍开关\n\n'
          '如果菜单里没有这一项，说明已允许，直接点「直接去开启」。',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('直接去开启')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('去应用详情')),
        ],
      ),
    );
    if (go == null || !context.mounted) return 'cancel';
    await Future<void>.delayed(const Duration(milliseconds: 80));
    final r = await NativeBridge.a11yOpen(force: !go);
    if (context.mounted) showGlassSnack(context, a11yJumpMessage(r));
    return r;
  }
  await Future<void>.delayed(const Duration(milliseconds: 80));
  final r = await NativeBridge.a11yOpen();
  if (context.mounted) showGlassSnack(context, a11yJumpMessage(r));
  return r;
}

/// 状态条：绿点「已开启」/ 橙点「未开启」+「去开启」按钮。回到前台自动刷新。
class A11yStatusBanner extends StatefulWidget {
  const A11yStatusBanner({super.key, this.compactWhenOn = true});

  /// 已开启时只显示一行小字。
  final bool compactWhenOn;

  @override
  State<A11yStatusBanner> createState() => _A11yStatusBannerState();
}

class _A11yStatusBannerState extends State<A11yStatusBanner> with WidgetsBindingObserver {
  A11yState? _st;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    final st = await loadA11yState();
    if (mounted) setState(() => _st = st);
  }

  @override
  Widget build(BuildContext context) {
    final st = _st;
    if (st == null || st == A11yState.unknown) return const SizedBox.shrink();
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final on = st == A11yState.on;
    final color = on ? Colors.green : Colors.orange;
    final text = switch (st) {
      A11yState.on => '无障碍已开启 · 工具箱可一键填入',
      A11yState.restricted => '无障碍未开启 · 需先「允许受限制的设置」',
      _ => '无障碍未开启 · 填入时只能复制后手动粘贴',
    };
    return Glass(
      radius: 16,
      padding: EdgeInsets.fromLTRB(14, on && widget.compactWhenOn ? 8 : 6, 6, on && widget.compactWhenOn ? 8 : 6),
      child: Row(children: [
        Container(width: 10, height: 10, decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        const SizedBox(width: 10),
        Expanded(child: Text(text, style: tt.bodyMedium?.copyWith(color: on ? s.onSurfaceVariant : null))),
        if (!on)
          FilledButton.tonal(
            onPressed: () async {
              await openAccessibilitySmart(context);
              await _load();
            },
            child: const Text('去开启'),
          ),
      ]),
    );
  }
}
