import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import '../theme/app_theme.dart';
import 'glass.dart';
import 'pressable.dart';

/// 工作资料不可用时的首页引导：创建 / 开启 / 不支持的原因。
class WorkOnboarding extends StatefulWidget {
  const WorkOnboarding({super.key});

  @override
  State<WorkOnboarding> createState() => _WorkOnboardingState();
}

class _WorkOnboardingState extends State<WorkOnboarding> {
  bool _busy = false;

  static String provisionMessage(String r, WorkProfileStatus st) => switch (r) {
        'ok' => '工作资料已创建，正在加载…',
        'cancelled' => '已取消创建（或系统向导失败）。${st.romNote}',
        'exists' => '工作资料已存在',
        'foreign' => '本机已有其他工作资料（公司设备管理、其他分身软件或三星安全文件夹），Android 只允许一个',
        'not_allowed' => '系统不允许创建工作资料。${st.romNote.isEmpty ? '可能已被设备管理策略禁止。' : st.romNote}',
        'unsupported' => '此设备不支持工作资料',
        _ => '无法启动系统向导，可能被当前系统禁用。${st.romNote}',
      };

  // ignore: unused_element
  Future<void> _provision(CloneStore store) async {
    setState(() => _busy = true);
    final r = await NativeBridge.provisionWorkProfile();
    if (!mounted) return;
    showGlassSnack(context, provisionMessage(r, store.status));
    // 资料所有者在新资料中完成初始化需要片刻
    for (var i = 0; i < (r == 'ok' ? 8 : 1); i++) {
      await store.refresh();
      if (store.ready || !mounted) break;
      await Future<void>.delayed(const Duration(seconds: 1));
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _unpause(CloneStore store) async {
    final r = await NativeBridge.unpauseWork();
    if (!mounted) return;
    if (r == 'unsupported') showGlassSnack(context, '请在系统设置中开启“工作应用”');
    await Future<void>.delayed(const Duration(milliseconds: 800));
    await store.refresh();
  }

  Future<void> _retryEngine(CloneStore store) async {
    setState(() => _busy = true);
    await store.retryEngine();
    if (mounted) setState(() => _busy = false);
  }

  String _engineFailureBody(CloneStore store) {
    final raw = store.engineError?.trim();
    final err = (raw == null || raw.isEmpty) ? '引擎返回不可用（available = false），没有附带具体原因。' : raw;
    final rom = store.status.romNote.isEmpty ? '' : '\n\n${store.status.romLabel}：${store.status.romNote}';
    return 'Alter Ego 使用进程内虚拟引擎（NewBlackbox）创建分身，不依赖系统工作资料。'
        '\n\n初始化失败：$err'
        '\n\n点「重新初始化」会再次加载引擎。若仍失败，请完全退出应用后重开。'
        '$rom';
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<CloneStore>();
    final st = store.status;
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;

    final engineFailure = (
      Icons.error_outline_rounded,
      '虚拟分身引擎未就绪',
      _engineFailureBody(store),
      '重新初始化',
      _busy ? null : () => _retryEngine(store),
    );
    final (IconData icon, String title, String body, String? button, VoidCallback? onTap) = switch (st.state) {
      WorkState.unknown => store.engineError == null
          ? (Icons.hourglass_top_rounded, '正在检测虚拟引擎…', '', null, null)
          : engineFailure,
      WorkState.none => engineFailure,
      WorkState.paused => (
          Icons.pause_circle_outline_rounded,
          '工作应用已关闭',
          '工作资料中的应用目前处于暂停状态，开启后才能打开分身。',
          '开启工作应用',
          () => _unpause(store),
        ),
      WorkState.foreign => (
          Icons.block_rounded,
          '已存在其他工作资料',
          '本机已有由公司设备管理、其他分身软件或系统（如三星安全文件夹）创建的工作资料。Android 只允许一个，'
              'Alter Ego 无法管理它。如需使用，请先在系统设置中删除现有工作资料，然后点“重新检测”。',
          '打开系统设置',
          () => NativeBridge.openWorkProfileSettings(),
        ),
      WorkState.unsupported => (
          Icons.phonelink_erase_rounded,
          '此设备不支持工作资料',
          '系统未提供工作资料功能，无法创建真实分身。${st.romNote.isEmpty ? '' : '\n\n${st.romLabel}：${st.romNote}'}',
          null,
          null,
        ),
      WorkState.inside => (
          Icons.work_history_rounded,
          '这是工作资料中的 Alter Ego',
          '请在主空间打开 Alter Ego 进行管理（不带公文包角标的图标）。这里不能再创建工作资料。',
          '切换到主空间',
          () => NativeBridge.openPersonalSpace(),
        ),
      WorkState.ready => (Icons.check_circle_rounded, '工作资料可用', '', null, null),
    };

    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 12, 24, 140),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Glass(
            padding: const EdgeInsets.all(24),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(borderRadius: BorderRadius.circular(20), gradient: AppTheme.brandGradient(s)),
                child: st.state == WorkState.unknown || _busy
                    ? const SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 3, color: Colors.white))
                    : Icon(icon, color: Colors.white, size: 28),
              ),
              const SizedBox(height: 16),
              Text(title, style: tt.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
              if (body.isNotEmpty) ...[
                const SizedBox(height: 10),
                Text(body, style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant, height: 1.55)),
              ],
              if (button != null) ...[
                const SizedBox(height: 20),
                SizedBox(
                  width: double.infinity,
                  child: Pressable(
                    radius: 20,
                    onTap: onTap,
                    child: Ink(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(20),
                        gradient: onTap != null ? AppTheme.brandGradient(s) : null,
                        color: onTap != null ? null : s.surfaceContainerHighest,
                      ),
                      child: Center(
                        child: Text(button,
                            style: TextStyle(
                                color: onTap != null ? Colors.white : s.onSurfaceVariant,
                                fontWeight: FontWeight.w700,
                                fontSize: 16)),
                      ),
                    ),
                  ),
                ),
              ],
              if (st.state != WorkState.unknown) ...[
                const SizedBox(height: 8),
                Center(child: TextButton(onPressed: _busy ? null : () => _retryEngine(store), child: const Text('重新检测'))),
              ],
            ]),
          ),
        ),
      ),
    );
  }
}
