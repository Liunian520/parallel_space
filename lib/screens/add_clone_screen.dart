import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/app_info.dart';
import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icon.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';
import '../widgets/pressable.dart';

/// 添加分身：列出个人空间中真实安装的可启动应用（版本、分类来自系统），复制到平行空间的工作资料。
class AddCloneScreen extends StatefulWidget {
  const AddCloneScreen({super.key, this.loader});

  /// 测试注入。
  final Future<List<AppInfo>> Function()? loader;

  @override
  State<AddCloneScreen> createState() => _AddCloneScreenState();
}

/// 列表行：分类标题或应用。
sealed class _Row {}

class _Header extends _Row {
  _Header(this.title);
  final String title;
}

class _AppRow extends _Row {
  _AppRow(this.app);
  final AppInfo app;
}

class _AddCloneScreenState extends State<AddCloneScreen> {
  String _q = '';
  List<AppInfo>? _apps;
  bool _showSystem = false;
  final Set<String> _cloning = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final apps = await (widget.loader ?? NativeBridge.listPersonalApps)();
    if (mounted) setState(() => _apps = apps);
  }

  List<_Row> _rows(CloneStore store) {
    final q = _q.trim().toLowerCase();
    final map = <AppCategory, List<AppInfo>>{};
    for (final a in _apps ?? const <AppInfo>[]) {
      if (a.system && !_showSystem) continue;
      if (q.isNotEmpty &&
          !a.name.toLowerCase().contains(q) &&
          !a.packageName.toLowerCase().contains(q) &&
          !a.category.label.contains(q)) {
        continue;
      }
      map.putIfAbsent(a.category, () => []).add(a);
    }
    final rows = <_Row>[];
    for (final cat in AppCategory.values) {
      final l = map[cat];
      if (l == null) continue;
      rows.add(_Header('${cat.label} · ${l.length}'));
      rows.addAll(l.map(_AppRow.new));
    }
    return rows;
  }

  bool _added(CloneStore store, AppInfo a) => a.inWork || store.byPackage(a.packageName) != null;

  Future<void> _clone(AppInfo a) async {
    final store = context.read<CloneStore>();
    setState(() => _cloning.add(a.packageName));
    final r = await cloneAppIntoWork(a.packageName);
    if (!mounted) return;
    setState(() => _cloning.remove(a.packageName));
    if (r != 'ok') {
      await _showCloneError(a, r);
      return;
    }
    store.addFromApp(a);
    await store.refresh();
    if (!mounted) return;
    showGlassSnack(context, '已创建分身「${a.name}」');
    Navigator.of(context).pop();
  }

  Future<void> _showCloneError(AppInfo a, String r) async {
    final msg = cloneErrorText(r);
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('无法创建分身'),
        content: Text('「${a.name}」$msg'),
        actions: [TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('知道了'))],
      ),
    );
  }

  void _showSheet(AppInfo app) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => _AppSheet(app: app, onClone: () {
        Navigator.pop(ctx);
        _clone(app);
      }),
    );
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;
    final store = context.watch<CloneStore>();
    final rows = _rows(store);
    final hasSystem = _apps?.any((a) => a.system) ?? false;

    return Scaffold(
      body: BlobBackground(
        child: CustomScrollView(
          physics: const BouncingScrollPhysics(),
          slivers: [
            SliverAppBar(
              pinned: true,
              floating: false,
              backgroundColor: s.surface.withValues(alpha: 0.94),
              surfaceTintColor: Colors.transparent,
              title: const Text('添加分身'),
              actions: [
                if (hasSystem)
                  IconButton(
                    tooltip: _showSystem ? '隐藏系统应用' : '显示系统应用',
                    onPressed: () => setState(() => _showSystem = !_showSystem),
                    icon: Icon(_showSystem ? Icons.android_rounded : Icons.apps_rounded),
                  ),
              ],
            ),
            SliverPersistentHeader(
              pinned: true,
              delegate: _SearchHeader(
                background: s.surface.withValues(alpha: 0.94),
                onChanged: (v) => setState(() => _q = v),
              ),
            ),
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(28, 8, 28, 8),
                child: Text(
                    context.watch<CloneStore>().engineAvailable
                        ? '选择个人空间中已安装的应用，在平行空间里再开一份，账号和数据与原应用分开'
                        : '选择个人空间中已安装的应用，复制到工作资料后即可登录另一个账号',
                    style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
              ),
            ),
            if (_apps == null)
              const SliverFillRemaining(hasScrollBody: false, child: Center(child: CircularProgressIndicator()))
            else if (rows.isEmpty)
              SliverFillRemaining(
                hasScrollBody: false,
                child: Center(
                  child: Text(_q.isEmpty ? '没有可添加的应用' : '没有找到「$_q」相关应用',
                      style: tt.bodyLarge?.copyWith(color: s.onSurfaceVariant)),
                ),
              )
            else
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 40),
                sliver: SliverList.builder(
                  itemCount: rows.length,
                  itemBuilder: (context, i) => switch (rows[i]) {
                    _Header(:final title) => Padding(
                        padding: const EdgeInsets.fromLTRB(12, 18, 12, 8),
                        child: Text(title, style: tt.labelLarge?.copyWith(color: s.primary, letterSpacing: 1)),
                      ),
                    _AppRow(:final app) => _tile(app, _added(store, app)),
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _tile(AppInfo a, bool added) {
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;
    final cloning = _cloning.contains(a.packageName);
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Glass(
        radius: 22,
        child: Pressable(
          radius: 22,
          scale: 0.98,
          onTap: added || cloning ? null : () => _showSheet(a),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            child: Row(children: [
              AppIcon(packageName: a.packageName, label: a.name, work: false, size: 44),
              const SizedBox(width: 14),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(a.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: tt.titleMedium),
                  Text(a.version.isEmpty ? a.packageName : '版本 ${a.version}',
                      maxLines: 1, overflow: TextOverflow.ellipsis, style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
                ]),
              ),
              if (cloning)
                const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
              else if (added)
                Tooltip(message: '已添加', child: Icon(Icons.check_circle_rounded, color: s.primary))
              else
                Icon(Icons.add_circle_outline_rounded, color: s.primary),
            ]),
          ),
        ),
      ),
    );
  }
}

class _SearchHeader extends SliverPersistentHeaderDelegate {
  _SearchHeader({required this.onChanged, required this.background});
  final ValueChanged<String> onChanged;
  final Color background;

  @override
  double get minExtent => 76;
  @override
  double get maxExtent => 76;

  @override
  Widget build(BuildContext context, double shrinkOffset, bool overlapsContent) {
    return ColoredBox(
      color: background,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 12),
        child: Glass(
          radius: 24,
          opacity: Theme.of(context).brightness == Brightness.dark ? 0.5 : 0.9,
          child: TextField(
            onChanged: onChanged,
            textInputAction: TextInputAction.search,
            decoration: const InputDecoration(
              hintText: '搜索已安装应用',
              prefixIcon: Icon(Icons.search_rounded),
              border: InputBorder.none,
              contentPadding: EdgeInsets.symmetric(vertical: 16),
            ),
          ),
        ),
      ),
    );
  }

  @override
  bool shouldRebuild(covariant _SearchHeader old) => old.background != background;
}

class _AppSheet extends StatelessWidget {
  const _AppSheet({required this.app, required this.onClone});
  final AppInfo app;
  final VoidCallback onClone;

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;

    Widget stat(String k, String v) => Expanded(
          child: Column(children: [
            Text(v, maxLines: 1, overflow: TextOverflow.ellipsis, style: tt.titleMedium),
            const SizedBox(height: 2),
            Text(k, style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
          ]),
        );

    return GlassSheet(
      padding: const EdgeInsets.fromLTRB(24, 12, 24, 24),
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Container(width: 40, height: 4, decoration: BoxDecoration(color: s.outlineVariant, borderRadius: BorderRadius.circular(2))),
        const SizedBox(height: 20),
        AppIcon(packageName: app.packageName, label: app.name, work: false, size: 80),
        const SizedBox(height: 14),
        Text(app.name, textAlign: TextAlign.center, style: tt.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        Text(app.packageName, style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
        const SizedBox(height: 20),
        Row(children: [
          stat('版本', app.version.isEmpty ? '—' : app.version),
          stat('分类', app.category.label),
        ]),
        const SizedBox(height: 16),
        Text(
            context.watch<CloneStore>().engineAvailable
                ? '在平行空间内安装同一份应用（不重新下载），使用原应用图标。分身与原应用的账号和数据完全隔离。'
                : '将在工作资料中安装同一份应用（不重新下载），带公文包角标。分身与原应用的账号和数据完全隔离。',
            textAlign: TextAlign.center, style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant, height: 1.5)),
        const SizedBox(height: 20),
        SizedBox(
          width: double.infinity,
          child: Pressable(
            radius: 22,
            onTap: onClone,
            child: Ink(
              padding: const EdgeInsets.symmetric(vertical: 18),
              decoration: BoxDecoration(borderRadius: BorderRadius.circular(22), gradient: AppTheme.brandGradient(s)),
              child: const Center(
                child: Text('创建分身', style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.w700)),
              ),
            ),
          ),
        ),
      ]),
    );
  }
}
