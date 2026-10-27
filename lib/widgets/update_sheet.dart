import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../services/update_manifest.dart';
import '../services/update_service.dart';
import 'glass.dart';

Future<void> checkForUpdates(BuildContext context, {bool force = true}) async {
  final svc = context.read<UpdateService>();
  showGlassSnack(context, '正在检查更新…');
  final r = await svc.check(force: force);
  if (!context.mounted) return;
  switch (r.kind) {
    case UpdateCheckResultKind.upToDate:
      if (force) showGlassSnack(context, '已是最新版本');
    case UpdateCheckResultKind.skippedInsideWork:
      showGlassSnack(context, r.message ?? '请在主空间检查更新');
    case UpdateCheckResultKind.error:
      // 直链失败时提供浏览器回退
      showGlassSnack(context, r.message ?? '检查更新失败');
      if (force) {
        final open = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('检查更新失败'),
            content: Text(r.message ?? '无法获取更新清单'),
            actions: [
              TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
              FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('浏览器打开')),
            ],
          ),
        );
        if (open == true && context.mounted) {
          await svc.openShareFallback();
        }
      }
    case UpdateCheckResultKind.available:
      await showUpdateSheet(context, svc, r.manifest!);
  }
}

Future<void> showUpdateSheet(BuildContext context, UpdateService svc, UpdateManifest m) async {
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => _UpdateSheet(svc: svc, manifest: m),
  );
}

class _UpdateSheet extends StatefulWidget {
  const _UpdateSheet({required this.svc, required this.manifest});
  final UpdateService svc;
  final UpdateManifest manifest;

  @override
  State<_UpdateSheet> createState() => _UpdateSheetState();
}

class _UpdateSheetState extends State<_UpdateSheet> {
  double? _progress;
  bool _busy = false;
  String? _err;

  Future<void> _go() async {
    setState(() {
      _busy = true;
      _err = null;
      _progress = 0;
    });
    try {
      if (!await NativeBridge.canInstallPackages()) {
        if (!mounted) return;
        showGlassSnack(context, '请允许安装未知应用后返回继续');
        await NativeBridge.openUnknownAppSources();
        setState(() => _busy = false);
        return;
      }
      final file = await widget.svc.download(widget.manifest, onProgress: (p) {
        if (mounted) setState(() => _progress = p);
      });
      final r = await widget.svc.install(file);
      if (!mounted) return;
      if (r == 'need_permission') {
        await NativeBridge.openUnknownAppSources();
        if (!mounted) return;
        showGlassSnack(context, '请允许安装未知应用；部分澎湃 OS 还会二次拦截安装');
      } else if (r == 'ok') {
        showGlassSnack(context, '已唤起系统安装器');
        Navigator.pop(context);
      } else {
        setState(() => _err = r);
      }
    } catch (e) {
      if (mounted) setState(() => _err = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _browser() async {
    await widget.svc.openShareFallback(manifest: widget.manifest);
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.manifest;
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return GlassSheet(
      padding: EdgeInsets.fromLTRB(24, 12, 24, 24 + MediaQuery.paddingOf(context).bottom),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Center(child: Container(width: 40, height: 4, decoration: BoxDecoration(color: s.outlineVariant, borderRadius: BorderRadius.circular(2)))),
        const SizedBox(height: 16),
        Text('发现新版本 ${m.versionName}', style: tt.titleLarge?.copyWith(fontWeight: FontWeight.w800)),
        const SizedBox(height: 6),
        Text('版本号 ${m.versionCode} · ${m.sizeLabel}', style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
        if (m.notes.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text(m.notes, style: tt.bodyMedium?.copyWith(height: 1.45)),
        ],
        if (_progress != null) ...[
          const SizedBox(height: 16),
          LinearProgressIndicator(value: _progress),
          const SizedBox(height: 6),
          Text('${((_progress ?? 0) * 100).toStringAsFixed(0)}%', style: tt.labelSmall),
        ],
        if (_err != null) ...[
          const SizedBox(height: 10),
          Text(_err!, style: tt.bodySmall?.copyWith(color: s.error)),
        ],
        const SizedBox(height: 18),
        Row(children: [
          TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('稍后')),
          TextButton(onPressed: _busy ? null : _browser, child: const Text('浏览器下载')),
          const Spacer(),
          FilledButton(onPressed: _busy ? null : _go, child: Text(_busy ? '处理中…' : '立即更新')),
        ]),
      ]),
    );
  }
}
