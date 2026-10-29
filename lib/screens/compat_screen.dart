import 'package:flutter/material.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';

class CompatItem {
  const CompatItem({
    required this.id,
    required this.title,
    required this.status,
    required this.detail,
    required this.canOpen,
  });

  final String id;
  final String title;
  final String status;
  final String detail;
  final bool canOpen;

  static CompatItem? fromMap(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['id'];
    final title = raw['title'];
    if (id is! String || title is! String) return null;
    return CompatItem(
      id: id,
      title: title,
      status: raw['status'] is String ? raw['status'] as String : 'unknown',
      detail: raw['detail'] is String ? raw['detail'] as String : '',
      canOpen: raw['canOpen'] == true,
    );
  }
}

class CompatReport {
  const CompatReport({
    required this.romLabel,
    required this.brand,
    required this.romVersion,
    required this.model,
    required this.sdk,
    required this.release,
    required this.abi,
    required this.items,
    this.harmonyNext = false,
    this.harmonyNote = '',
    this.brandTip = '',
  });

  final String romLabel;
  final String brand;
  final String romVersion;
  final String model;
  final int sdk;
  final String release;
  final String abi;
  final List<CompatItem> items;

  /// 疑似纯血鸿蒙 NEXT（不能跑 Android APK）。
  final bool harmonyNext;

  /// 给用户看的鸿蒙说明（可空）。
  final String harmonyNote;

  /// 当前品牌建议打开的权限清单。
  final String brandTip;

  static CompatReport fromMap(Map? raw) {
    final itemsRaw = raw?['items'];
    return CompatReport(
      romLabel: raw?['romLabel'] is String ? raw!['romLabel'] as String : '未知机型',
      brand: raw?['brand'] is String ? raw!['brand'] as String : '',
      romVersion: raw?['romVersion'] is String ? raw!['romVersion'] as String : '',
      model: raw?['model'] is String ? raw!['model'] as String : '',
      sdk: (raw?['sdk'] as num?)?.toInt() ?? 0,
      release: raw?['release'] is String ? raw!['release'] as String : '',
      abi: raw?['abi'] is String ? raw!['abi'] as String : '',
      harmonyNext: raw?['harmonyNext'] == true,
      harmonyNote: raw?['harmonyNote'] is String ? raw!['harmonyNote'] as String : '',
      brandTip: raw?['brandTip'] is String ? raw!['brandTip'] as String : '',
      items: [
        if (itemsRaw is List) ...itemsRaw.map(CompatItem.fromMap).nonNulls,
      ],
    );
  }
}

/// 当前机型上和分身、工具箱有关的权限。每一项都能跳到对应设置，打不开时原生层会逐级退回。
class CompatScreen extends StatefulWidget {
  const CompatScreen({super.key});

  @override
  State<CompatScreen> createState() => _CompatScreenState();
}

class _CompatScreenState extends State<CompatScreen> with WidgetsBindingObserver {
  CompatReport? _report;
  var _loading = true;

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
    final raw = await NativeBridge.compatReport();
    if (!mounted) return;
    setState(() {
      _report = CompatReport.fromMap(raw);
      _loading = false;
    });
  }

  Future<void> _open(CompatItem item) async {
    if (!item.canOpen) {
      showGlassSnack(context, '这项没有单独的设置页');
      return;
    }
    await Future<void>.delayed(const Duration(milliseconds: 80));
    if (!mounted) return;
    final r = await NativeBridge.compatOpen(item.id);
    if (!mounted) return;
    final code = NativeBridge.compatOpenCode(r);
    final hint = NativeBridge.compatOpenHint(r);
    showGlassSnack(context, switch (code) {
      'rom' || 'list' => '已打开厂商 / 系统设置页',
      'details' => '已打开应用详情（可找权限与后台弹出）',
      'settings' => '已打开系统设置',
      'restricted' => hint.isEmpty ? '请先允许受限制的设置' : hint,
      'ok' || 'requested' || 'on' => '已跳转',
      'error' => hint.isEmpty ? '没有打开设置页，请按提示手动操作' : hint,
      _ => hint.isNotEmpty ? hint : '已尝试打开',
    });
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final report = _report;
    return Scaffold(
      body: BlobBackground(
        child: SafeArea(
          child: ListView(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
            children: [
              Row(children: [
                IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.arrow_back_rounded)),
                Expanded(child: Text('机型适配检查', style: tt.headlineMedium)),
                IconButton(onPressed: _load, icon: const Icon(Icons.refresh_rounded)),
              ]),
              if (_loading)
                const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()))
              else if (report == null || report.items.isEmpty)
                const Padding(padding: EdgeInsets.all(12), child: Text('暂时读不到这台机器的权限状态。'))
              else ...[
                Padding(
                  padding: const EdgeInsets.fromLTRB(4, 4, 4, 12),
                  child: Text(
                    [
                      if (report.brand.isNotEmpty) report.brand,
                      report.romLabel,
                      if (report.model.isNotEmpty) report.model,
                      'Android ${report.release.isEmpty ? '' : report.release}（API ${report.sdk}）',
                      if (report.abi.isNotEmpty) report.abi,
                    ].where((e) => e.trim().isNotEmpty).join(' · '),
                    style: tt.bodyMedium,
                  ),
                ),
                if (report.harmonyNote.isNotEmpty) ...[
                  Glass(
                    padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Icon(
                        report.harmonyNext ? Icons.error_outline_rounded : Icons.info_outline_rounded,
                        color: report.harmonyNext ? Theme.of(context).colorScheme.error : Theme.of(context).colorScheme.primary,
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          report.harmonyNote,
                          style: tt.bodyMedium?.copyWith(
                            color: report.harmonyNext ? Theme.of(context).colorScheme.error : null,
                          ),
                        ),
                      ),
                    ]),
                  ),
                  const SizedBox(height: 10),
                ],
                if (report.brandTip.isNotEmpty) ...[
                  Glass(
                    padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Icon(Icons.tips_and_updates_outlined, color: Theme.of(context).colorScheme.primary),
                      const SizedBox(width: 10),
                      Expanded(child: Text(report.brandTip, style: tt.bodyMedium)),
                    ]),
                  ),
                  const SizedBox(height: 10),
                ],
                Padding(
                  padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
                  child: Text(
                    '下面每一项都会智能跳到对应设置页；打不开时会提示手动路径。绿点已就绪，橙点建议处理，红点未开启。',
                    style: tt.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
                  ),
                ),
                for (final item in report.items) ...[
                  Glass(
                    padding: const EdgeInsets.fromLTRB(14, 12, 8, 12),
                    child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Expanded(
                        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Row(children: [
                            _StatusDot(status: item.status),
                            const SizedBox(width: 8),
                            Expanded(child: Text(item.title, style: tt.titleMedium)),
                          ]),
                          const SizedBox(height: 6),
                          Text(item.detail),
                        ]),
                      ),
                      if (item.canOpen)
                        TextButton.icon(
                          onPressed: () => _open(item),
                          icon: const Icon(Icons.open_in_new_rounded, size: 18),
                          label: const Text('去设置'),
                        ),
                    ]),
                  ),
                  const SizedBox(height: 10),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _StatusDot extends StatelessWidget {
  const _StatusDot({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final color = switch (status) {
      'ok' => const Color(0xFF2E7D32),
      'warn' => const Color(0xFFEF6C00),
      'off' => s.error,
      _ => s.outline,
    };
    final label = switch (status) {
      'ok' => '正常',
      'warn' => '建议处理',
      'off' => '未开启',
      _ => '无法读取',
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(99)),
      child: Text(label, style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w700)),
    );
  }
}
