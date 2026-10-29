import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/clone_app.dart';
import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import '../state/location_store.dart';
import '../state/settings_store.dart';
import '../theme/app_theme.dart';
import '../widgets/empty_state.dart';
import '../widgets/glass.dart';
import '../widgets/pressable.dart';
import '../widgets/reorderable_clone_grid.dart';
import '../widgets/text_input_dialog.dart';
import '../widgets/work_onboarding.dart';
import 'add_clone_screen.dart';
import 'clone_detail_screen.dart';
import 'private_space_screen.dart';
import 'vault_screen.dart';

/// 移除分身：在工作资料中隐藏该应用（数据保留，可再次复制恢复）。若正在模拟定位则先停止。
Future<bool> deleteClone(BuildContext context, CloneApp c) async {
  final store = context.read<CloneStore>();
  final loc = context.read<LocationStore>();
  final vs = await NativeBridge.virtualStatus();
  String r;
  String? detail;
  if (vs['available'] == true) {
    // 只删这个实例（userId）的数据；同一应用的其他分身不受影响
    final m = await NativeBridge.deleteVirtual(c.packageName, userId: c.userId);
    r = m['r'] as String? ?? 'error';
    detail = m['detail'] as String?;
  } else {
    r = await NativeBridge.hideWorkApp(c.packageName);
  }
  if (!context.mounted) return false;
  if (r == 'ok') {
    if (store.mockingUid == c.uid) {
      if (loc.activeCloneUid == c.uid) loc.stopRoute();
      await NativeBridge.stopMock();
      if (!context.mounted) return false;
    }
    store.remove(c.uid);
    return true;
  }
  showGlassSnack(context, '移除失败：${detail ?? r}');
  return false;
}

Future<bool> confirmDelete(BuildContext context, CloneApp c) async =>
    await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除分身'),
        content: Text('「${c.customName}」将从虚拟空间中删除，不会影响个人空间中的原应用。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Theme.of(ctx).colorScheme.error),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('删除'),
          ),
        ],
      ),
    ) ??
    false;

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.onOpenSettings});
  final VoidCallback onOpenSettings;

  void _add(BuildContext context) =>
      Navigator.of(context).push(CupertinoPageRoute(builder: (_) => const AddCloneScreen()));

  Future<void> _rename(BuildContext context) async {
    final settings = context.read<SettingsStore>();
    final v = await showTextInputDialog(context, title: '自定义标题', initial: settings.spaceTitle, maxLength: 10);
    if (v != null && v.isNotEmpty) settings.set((s) => s.spaceTitle = v);
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<CloneStore>();
    final title = context.select<SettingsStore, String>((s) => s.spaceTitle);
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;
    final visible = store.visibleClones;
    final ready = store.ready;

    Widget headerButton(IconData icon, String tip, VoidCallback onTap) => Padding(
          padding: const EdgeInsets.only(left: 8),
          child: Glass(
            radius: 20,
            child: Tooltip(
              message: tip,
              child: Pressable(
                radius: 20,
                onTap: onTap,
                child: Padding(padding: const EdgeInsets.all(12), child: Icon(icon)),
              ),
            ),
          ),
        );

    final Widget body;
    if (!store.ready) {
      // 引擎未就绪且工作资料也非 ready：说明虚拟化 / 遗留工作资料状态
      body = const WorkOnboarding(key: ValueKey('onboarding'));
    } else if (store.isEmpty) {
      body = EmptyState(key: const ValueKey('empty'), onAdd: () => _add(context));
    } else {
      body = ReorderableCloneGrid(
        key: const ValueKey('grid'),
        clones: visible,
        onReorder: store.reorderByUid,
        onTap: (c) => Navigator.of(context).push(CupertinoPageRoute(builder: (_) => CloneDetailScreen(uid: c.uid))),
        onOpen: (c) => openClone(Navigator.of(context), c),
        onPin: (c) => pinCloneToHome(context, c),
        onRename: (c) => renameClone(context, c),
        onAnother: (c) => cloneAnotherInstance(context, c),
        mockingUid: store.mockingUid,
        onDelete: (c) async {
          if (!await confirmDelete(context, c) || !context.mounted) return;
          if (await deleteClone(context, c) && context.mounted) showGlassSnack(context, '已移除「${c.customName}」');
        },
      );
    }

    return Stack(children: [
      SafeArea(
        bottom: false,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 20, 16, 4),
            child: Row(children: [
              Expanded(
                child: GestureDetector(
                  onLongPress: () => _rename(context),
                  onDoubleTap: () => _rename(context),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    ShaderMask(
                      shaderCallback: (r) => AppTheme.brandGradient(s).createShader(r),
                      child: Text(title,
                          maxLines: 1, overflow: TextOverflow.ellipsis, style: tt.headlineMedium?.copyWith(color: Colors.white)),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      ready ? '${visible.length} 个分身 · 长按拖拽排序' : '进程内虚拟引擎分身（不依赖工作资料）',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant),
                    ),
                  ]),
                ),
              ),
              if (ready)
                headerButton(Icons.key_rounded, '密码本', () => openVaultScreen(context)),
              if (ready)
                headerButton(Icons.lock_person_rounded, '私密空间',
                    () => Navigator.of(context).push(CupertinoPageRoute(builder: (_) => const PrivateSpaceScreen()))),
              headerButton(Icons.tune_rounded, '设置', onOpenSettings),
            ]),
          ),
          if (!store.engineAvailable && ready && (!store.status.linked || !store.status.canInteract))
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 0, 20, 8),
              child: _SetupBanner(),
            )
          else if (!store.engineAvailable && ready)
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
              child: _EngineBanner(error: store.engineError),
            ),
          const SizedBox(height: 4),
          Expanded(child: AnimatedSwitcher(duration: const Duration(milliseconds: 250), child: body)),
        ]),
      ),
      if (ready && !store.isEmpty)
        Positioned(
          right: 20,
          bottom: 16 + MediaQuery.paddingOf(context).bottom,
          child: FloatingActionButton.extended(
            heroTag: 'fab',
            onPressed: () => _add(context),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
            icon: const Icon(Icons.add_rounded),
            label: const Text('添加分身', style: TextStyle(fontWeight: FontWeight.w700)),
          ),
        ),
    ]);
  }
}


/// 虚拟引擎不可用时的诚实提示（不假装已分身）。
class _EngineBanner extends StatelessWidget {
  const _EngineBanner({this.error});
  final String? error;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final detail = (error == null || error!.isEmpty) ? '引擎未就绪' : error!;
    return Glass(
      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Icon(Icons.warning_amber_rounded, color: s.error),
        const SizedBox(width: 10),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('虚拟分身引擎不可用', style: tt.titleSmall?.copyWith(fontWeight: FontWeight.w700)),
            const SizedBox(height: 4),
            Text(
              '当前无法创建或启动虚拟分身。$detail\n'
              '本版本以 NewBlackbox/Bcore 进程内虚拟化为主；工作资料路径已降级，澎湃 OS 上常因无法授权跨空间而不可用。',
              style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant, height: 1.4),
            ),
          ]),
        ),
      ]),
    );
  }
}

/// 跨资料授权 + 静默 ProfileOp 初始化（绝不打开工作资料 Flutter 页）。
class _SetupBanner extends StatefulWidget {
  const _SetupBanner();
  @override
  State<_SetupBanner> createState() => _SetupBannerState();
}

class _SetupBannerState extends State<_SetupBanner> {
  bool _busy = false;
  String? _lastDetail;

  Future<void> _authorize() async {
    setState(() => _busy = true);
    final r = await NativeBridge.requestInteractAcrossProfiles();
    if (!mounted) return;
    setState(() => _busy = false);
    showGlassSnack(
      context,
      switch (r) {
        'ok' => '已打开系统授权页，请允许「关联工作应用 / 跨空间」后返回',
        'already' => '已授权，可点「完成初始化」',
        'settings' => '请在应用详情中允许跨空间相关权限后返回',
        'unsupported' => '当前系统未提供标准授权页（澎湃 OS 可能限制工作资料管理）',
        _ => '无法打开授权页：$r',
      },
    );
    await Future<void>.delayed(const Duration(milliseconds: 400));
    if (mounted) await context.read<CloneStore>().refresh();
  }

  Future<void> _init() async {
    setState(() {
      _busy = true;
      _lastDetail = null;
    });
    final m = await NativeBridge.repairWorkLink();
    final r = m['r'] as String? ?? 'error';
    final detail = m['detail'] as String? ?? '';
    if (!mounted) return;
    if (r == 'need_permission' || r == 'security') {
      setState(() {
        _busy = false;
        _lastDetail = detail;
      });
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('需要跨空间授权'),
          content: Text(
            '系统未允许 Alter Ego 在个人空间与工作资料之间启动操作通道'
            '（CrossProfileApps.canInteractAcrossProfiles = false）。\n\n'
            '请先点「授权跨空间」，在系统页允许关联后返回再试。\n\n详情：$detail',
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
            FilledButton(
              onPressed: () {
                Navigator.pop(ctx);
                _authorize();
              },
              child: const Text('去授权'),
            ),
          ],
        ),
      );
      return;
    }
    if (r == 'ok') {
      await Future<void>.delayed(const Duration(milliseconds: 800));
      if (!mounted) return;
      await context.read<CloneStore>().refresh();
      if (!mounted) return;
      setState(() => _busy = false);
      showGlassSnack(context, '已请求完成初始化');
      return;
    }
    setState(() {
      _busy = false;
      _lastDetail = detail;
    });
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) {
        final st = context.read<CloneStore>().status;
        return AlertDialog(
          title: const Text('无法完成初始化'),
          content: Text(
            [
              if (st.isMiui) '澎湃 OS / HyperOS 可能限制了工作资料管理通道。' else '系统拒绝了跨资料启动。',
              '',
              '结果：$r',
              '详情：$detail',
              '',
              '可选：打开系统工作资料设置，或删除工作资料后重试。本应用不会使用 VirtualApp 等私有虚拟化引擎。',
            ].join('\n'),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('关闭')),
            TextButton(
              onPressed: () {
                Navigator.pop(ctx);
                NativeBridge.openWorkProfileSettings();
              },
              child: const Text('系统设置'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<CloneStore>();
    final st = store.status;
    final s = Theme.of(context).colorScheme;
    final needAuth = !st.canInteract;
    return Glass(
      padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(needAuth ? Icons.admin_panel_settings_rounded : Icons.link_rounded, color: s.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              needAuth
                  ? '需要系统授权「跨空间 / 关联工作应用」，否则无法在工作资料中静默安装或删除分身。'
                  : '已授权跨空间，但操作通道尚未完成初始化。',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: s.onSurfaceVariant),
            ),
          ),
        ]),
        if (_lastDetail != null) ...[
          const SizedBox(height: 6),
          Text(_lastDetail!, style: Theme.of(context).textTheme.labelSmall?.copyWith(color: s.error)),
        ],
        const SizedBox(height: 8),
        Row(children: [
          if (needAuth || st.canRequestInteract)
            TextButton(onPressed: _busy ? null : _authorize, child: const Text('授权跨空间')),
          TextButton(
            onPressed: _busy ? null : _init,
            child: _busy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('完成初始化'),
          ),
        ]),
      ]),
    );
  }
}
