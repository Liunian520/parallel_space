import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../models/clone_app.dart';
import '../models/device_identity.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import 'glass.dart';
import 'section_tile.dart';

/// 每个分身单独的 Android ID。默认关闭，避免把已经登录的分身换成新标识。
class DeviceIdentitySection extends StatefulWidget {
  const DeviceIdentitySection({super.key, required this.clone});

  final CloneApp clone;

  @override
  State<DeviceIdentitySection> createState() => _DeviceIdentitySectionState();
}

class _DeviceIdentitySectionState extends State<DeviceIdentitySection> {
  DeviceIdentity? _remote;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(DeviceIdentitySection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.clone.uid != widget.clone.uid) _load();
  }

  Future<void> _load() async {
    final m = await NativeBridge.getCloneIdentity(widget.clone.packageName, userId: widget.clone.userId);
    if (!mounted || m == null) return;
    final id = DeviceIdentity.fromMap(m);
    setState(() => _remote = id);
    if (widget.clone.deviceIdIsolated != id.enabled) {
      context.read<CloneStore>().update(widget.clone, (c) => c.deviceIdIsolated = id.enabled);
    }
  }

  bool get _enabled => _remote?.enabled ?? widget.clone.deviceIdIsolated;

  Future<void> _setEnabled(bool value) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(value ? '打开独立设备标识' : '关闭独立设备标识'),
        content: const Text(DeviceIdentity.relaunchNote),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: Text(value ? '打开' : '关闭')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await _apply(() => NativeBridge.setCloneIdentityEnabled(widget.clone.packageName, enabled: value, userId: widget.clone.userId));
  }

  Future<void> _regenerate() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('更换设备标识'),
        content: const Text('更换后，该分身里已登录的应用可能失效，需要重新登录。\n\n${DeviceIdentity.relaunchNote}'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('更换')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    await _apply(() => NativeBridge.regenerateCloneIdentity(widget.clone.packageName, userId: widget.clone.userId));
  }

  Future<void> _apply(Future<Map<String, dynamic>> Function() call) async {
    setState(() => _busy = true);
    final m = await call();
    if (!mounted) return;
    setState(() => _busy = false);
    if (m['r'] != 'ok') {
      showGlassSnack(context, m['detail']?.toString() ?? '没有写入设备标识');
      return;
    }
    final id = DeviceIdentity.fromMap(m);
    setState(() => _remote = id);
    context.read<CloneStore>().update(widget.clone, (c) => c.deviceIdIsolated = id.enabled);
    showGlassSnack(context, '已保存。请重新打开该分身后再使用。');
  }

  @override
  Widget build(BuildContext context) {
    final id = _remote;
    final shown = (id != null && id.showId) ? id.androidId : null;
    return SectionCard(title: '登录隔离', children: [
      GlassTile(
        icon: Icons.phonelink_lock_rounded,
        title: '独立设备标识',
        subtitle: _enabled ? '这个分身使用单独保存的 Android ID' : '关闭时读到的是本机 Android ID，和现在一样',
        trailing: Switch(
          value: _enabled,
          onChanged: _busy ? null : _setEnabled,
        ),
      ),
      if (_enabled)
        GlassTile(
          icon: Icons.tag_rounded,
          title: '当前标识',
          subtitle: shown ?? '正在读取…',
          onTap: shown == null
              ? null
              : () async {
                  await Clipboard.setData(ClipboardData(text: shown!));
                  if (context.mounted) showGlassSnack(context, '已复制设备标识');
                },
        ),
      if (_enabled)
        GlassTile(
          icon: Icons.autorenew_rounded,
          title: '更换标识',
          subtitle: '只影响这个分身。应用可能要求重新登录',
          onTap: _busy ? null : _regenerate,
        ),
      const Padding(
        padding: EdgeInsets.fromLTRB(18, 4, 18, 12),
        child: Text(DeviceIdentity.relaunchNote, style: TextStyle(fontSize: 12, height: 1.4)),
      ),
    ]);
  }
}
