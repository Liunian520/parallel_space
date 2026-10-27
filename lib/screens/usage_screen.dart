import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../widgets/blob_background.dart';
import '../widgets/section_tile.dart';

/// 今日应用用量（UsageStatsManager，需要“使用情况访问权限”）。
class UsageScreen extends StatefulWidget {
  const UsageScreen({super.key});

  @override
  State<UsageScreen> createState() => _UsageScreenState();
}

class _UsageScreenState extends State<UsageScreen> with WidgetsBindingObserver {
  bool? _granted;
  List<MapEntry<String, UsageInfo>> _items = const [];

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
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) _load();
  }

  Future<void> _load() async {
    final g = await NativeBridge.hasUsageAccess();
    final m = g ? await NativeBridge.usageToday() : const <String, UsageInfo>{};
    if (!mounted) return;
    final list = m.entries.where((e) => e.value.foregroundMs >= 60000).toList()
      ..sort((a, b) => b.value.foregroundMs.compareTo(a.value.foregroundMs));
    setState(() {
      _granted = g;
      _items = list.take(20).toList();
    });
  }

  String _fmt(int ms) {
    final m = ms ~/ 60000;
    return m >= 60 ? '${m ~/ 60} 小时 ${m % 60} 分' : '$m 分钟';
  }

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final max = _items.isEmpty ? 1 : _items.first.value.foregroundMs;
    return Scaffold(
      body: BlobBackground(
        child: CustomScrollView(physics: const BouncingScrollPhysics(), slivers: [
          SliverAppBar.large(backgroundColor: Colors.transparent, surfaceTintColor: Colors.transparent, title: const Text('今日用量')),
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
            sliver: SliverList.list(children: [
              if (_granted == null) const Center(child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator())),
              if (_granted == false)
                SectionCard(title: '需要权限', children: [
                  const GlassTile(
                    icon: Icons.query_stats_rounded,
                    title: '开启“使用情况访问权限”',
                    subtitle: '系统设置 → 应用 → 特殊应用权限 → 使用情况访问权限 → 平行空间 → 允许。返回后自动刷新。',
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 14),
                    child: SizedBox(
                      width: double.infinity,
                      child: FilledButton.icon(
                        onPressed: NativeBridge.openUsageAccessSettings,
                        icon: const Icon(Icons.open_in_new_rounded),
                        label: const Text('前往设置'),
                      ),
                    ),
                  ),
                ]),
              if (_granted == true)
                SectionCard(title: '个人空间 · 今日前台时长', children: [
                  if (_items.isEmpty) const GlassTile(icon: Icons.inbox_rounded, title: '今天还没有超过 1 分钟的使用记录'),
                  for (final e in _items)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Row(children: [
                          Expanded(child: Text(e.value.label, maxLines: 1, overflow: TextOverflow.ellipsis, style: tt.titleSmall)),
                          Text(_fmt(e.value.foregroundMs), style: tt.labelMedium?.copyWith(color: s.primary)),
                        ]),
                        const SizedBox(height: 6),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: LinearProgressIndicator(value: e.value.foregroundMs / max, minHeight: 6),
                        ),
                      ]),
                    ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
                    child: Text('数据来自系统 UsageStatsManager，仅包含个人空间；工作资料内的分身用量由系统隔离，无法读取。模拟分身没有真实用量。',
                        style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant, height: 1.5)),
                  ),
                ]),
            ]),
          ),
        ]),
      ),
    );
  }
}
