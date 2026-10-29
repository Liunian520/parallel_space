import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../data/mock_locations.dart';
import '../models/clone_app.dart';
import '../models/location_models.dart';
import '../screens/route_editor_sheet.dart';
import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import '../state/location_store.dart';
import '../theme/app_theme.dart';
import 'geo_canvas.dart';
import 'glass.dart';
import 'text_input_dialog.dart';
import 'pressable.dart';
import 'section_tile.dart';

/// 分身详情中的“虚拟定位”分组：定点 / 收藏 / 路线模拟。
class LocationSection extends StatefulWidget {
  const LocationSection({super.key, required this.clone});
  final CloneApp clone;

  @override
  State<LocationSection> createState() => _LocationSectionState();
}

class _LocationSectionState extends State<LocationSection> {
  bool _routeMode = false;
  String? _routeId;
  SpeedMode? _speed;
  bool? _loop;

  CloneApp get clone => widget.clone;

  @override
  void initState() {
    super.initState();
    final loc = context.read<LocationStore>();
    if (loc.activeCloneUid == clone.uid) {
      _routeMode = true;
      _routeId = loc.activeRoute?.id;
    }
  }

  // ---------------- 定点 ----------------
  Future<void> _start(CloneStore store, LocationStore loc) async {
    if (loc.isActive) loc.stopRoute();
    final r = await NativeBridge.startMock(clone.mockLat, clone.mockLng);
    if (!mounted) return;
    switch (r) {
      case MockResult.ok:
        store.setMocking(clone.uid);
        showGlassSnack(context, '已开始模拟：${clone.mockName}（系统全局生效）');
      case MockResult.notMockApp:
        showMockGuideSheet(context);
      case MockResult.error:
        showGlassSnack(context, '启动失败：当前设备不支持或非 Android 环境');
    }
  }

  Future<void> _stop(CloneStore store, LocationStore loc) async {
    loc.stopRoute();
    await NativeBridge.stopMock();
    store.setMocking(null);
    if (mounted) showGlassSnack(context, '已停止虚拟定位，系统恢复真实位置');
  }

  void _set(CloneStore store, LocationStore loc, String name, double lat, double lng) {
    store.update(clone, (c) {
      c.mockName = name;
      c.mockLat = lat;
      c.mockLng = lng;
    });
    // 运行中（定点模式）切换位置时立即推送新坐标；路线回放中不打断路线。
    if (store.mockingUid == clone.uid && !loc.isActive) NativeBridge.updateMock(lat, lng);
  }

  Future<void> _manual(CloneStore store, LocationStore loc) async {
    final lat = TextEditingController(text: clone.mockLat.toStringAsFixed(6));
    final lng = TextEditingController(text: clone.mockLng.toStringAsFixed(6));
    final name = TextEditingController(text: '自定义位置');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('输入经纬度'),
        content: SingleChildScrollView(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            TextField(controller: name, maxLength: 16, decoration: const InputDecoration(labelText: '名称')),
            TextField(
              controller: lat,
              keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
              decoration: const InputDecoration(labelText: '纬度（-90 ~ 90）'),
            ),
            TextField(
              controller: lng,
              keyboardType: const TextInputType.numberWithOptions(decimal: true, signed: true),
              decoration: const InputDecoration(labelText: '经度（-180 ~ 180）'),
            ),
          ]),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('确定')),
        ],
      ),
    );
    final la = double.tryParse(lat.text.trim());
    final lo = double.tryParse(lng.text.trim());
    final nm = name.text.trim();
    disposeAfterDialog([lat, lng, name]);
    if (ok != true || !mounted) return;
    if (la == null || lo == null || !la.isFinite || !lo.isFinite || la.abs() > 90 || lo.abs() > 180) {
      showGlassSnack(context, '坐标格式不正确');
      return;
    }
    _set(store, loc, nm.isEmpty ? '自定义位置' : nm, la, lo);
  }

  Future<void> _pick(CloneStore store, LocationStore loc) async {
    final r = await showModalBottomSheet<GeoPoint>(
      context: context,
      isScrollControlled: true,
      builder: (_) => PointPickerSheet(initial: GeoPoint(clone.mockLat, clone.mockLng)),
    );
    if (r != null && mounted) _set(store, loc, '地图选点', r.lat, r.lng);
  }

  Future<void> _saveFavorite(LocationStore loc) async {
    final ctrl = TextEditingController(text: clone.mockName);
    final name = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('收藏当前位置'),
        content: TextField(controller: ctrl, autofocus: true, maxLength: 16, decoration: const InputDecoration(labelText: '名称')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, ctrl.text.trim()), child: const Text('收藏')),
        ],
      ),
    );
    disposeAfterDialog([ctrl]);
    if (name == null || !mounted) return;
    loc.addFavorite(name, clone.mockLat, clone.mockLng);
    showGlassSnack(context, '已收藏「${name.isEmpty ? '未命名' : name}」');
  }

  Future<void> _manageFavorite(LocationStore loc, FavoriteLocation f) async {
    final ctrl = TextEditingController(text: f.name);
    final r = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('管理收藏'),
        content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          TextField(controller: ctrl, maxLength: 16, decoration: const InputDecoration(labelText: '名称')),
          Text('${f.lat.toStringAsFixed(5)}, ${f.lng.toStringAsFixed(5)}'),
        ]),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, '__delete'),
            style: TextButton.styleFrom(foregroundColor: Theme.of(ctx).colorScheme.error),
            child: const Text('删除'),
          ),
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, ctrl.text.trim()), child: const Text('保存')),
        ],
      ),
    );
    disposeAfterDialog([ctrl]);
    if (r == null) return;
    if (r == '__delete') {
      loc.removeFavorite(f.id);
    } else {
      loc.renameFavorite(f.id, r);
    }
  }

  // ---------------- 路线 ----------------
  MockRoute? _selected(LocationStore loc) {
    final all = loc.routes;
    return all.where((r) => r.id == _routeId).firstOrNull ?? (all.isEmpty ? null : all.first);
  }

  Future<void> _startRoute(CloneStore store, LocationStore loc, MockRoute base) async {
    final route = MockRoute(
      id: base.id,
      name: base.name,
      points: base.points,
      loop: _loop ?? base.loop,
      speed: _speed ?? base.speed,
    );
    // 不先 stopMock：服务直接切换坐标，避免测试 Provider 被移除又重建导致定位闪断
    loc.stopRoute();
    final ok = await loc.startRoute(route, clone.uid);
    if (!mounted) return;
    if (ok) {
      store.setMocking(clone.uid);
      showGlassSnack(context, '开始路线模拟：${route.name} · ${route.speed.label}');
    } else {
      final isMock = await NativeBridge.startMock(route.points.first.lat, route.points.first.lng);
      if (!mounted) return;
      if (isMock == MockResult.notMockApp) {
        showMockGuideSheet(context);
      } else {
        showGlassSnack(context, '启动失败：当前设备不支持或非 Android 环境');
      }
    }
  }

  Future<void> _editRoute(LocationStore loc, [MockRoute? r]) async {
    final saved = await showModalBottomSheet<MockRoute>(
      context: context,
      isScrollControlled: true,
      builder: (_) => RouteEditorSheet(initial: r),
    );
    if (saved != null && mounted) setState(() => _routeId = saved.id);
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<CloneStore>();
    final loc = context.watch<LocationStore>();
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final routeRunningHere = loc.activeCloneUid == clone.uid;
    final running = store.mockingUid == clone.uid && !routeRunningHere;
    final otherRunning = store.mockingUid != null && store.mockingUid != clone.uid;

    return SectionCard(title: '虚拟定位', children: [
      GlassTile(
        icon: Icons.location_on_rounded,
        title: '启用虚拟定位',
        subtitle: '为该分身保存模拟位置或路线',
        trailing: Switch(
          value: clone.mockEnabled,
          onChanged: (v) async {
            if (!v && store.mockingUid == clone.uid) await _stop(store, loc);
            store.update(clone, (c) => c.mockEnabled = v);
          },
        ),
      ),
      AnimatedSize(
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOutCubic,
        child: !clone.mockEnabled
            ? const SizedBox(width: double.infinity)
            : Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 14),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  SizedBox(
                    width: double.infinity,
                    child: SegmentedButton<bool>(
                      segments: const [
                        ButtonSegment(value: false, icon: Icon(Icons.place_rounded), label: Text('定点')),
                        ButtonSegment(value: true, icon: Icon(Icons.route_rounded), label: Text('路线')),
                      ],
                      selected: {_routeMode},
                      onSelectionChanged: (v) => setState(() => _routeMode = v.first),
                    ),
                  ),
                  const SizedBox(height: 12),
                  if (!_routeMode) ..._pointUi(store, loc, s, tt, running, otherRunning) else ..._routeUi(store, loc, s, tt, routeRunningHere),
                  const SizedBox(height: 10),
                  Text(
                    '说明：Android 无法只对单个分身伪装位置。开始模拟后，系统 GPS 与网络定位都会被替换，'
                    '手机上所有应用读取到的都是该位置，直到你点击“停止”。需在开发者选项中将“Alter Ego”设为模拟位置信息应用。'
                    '路线回放由 App 推进，App 被系统杀死后位置会停在最后一个点。',
                    style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant, height: 1.5),
                  ),
                ]),
              ),
      ),
    ]);
  }

  List<Widget> _pointUi(CloneStore store, LocationStore loc, ColorScheme s, TextTheme tt, bool running, bool otherRunning) => [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.fromLTRB(14, 8, 4, 8),
          decoration: BoxDecoration(borderRadius: BorderRadius.circular(20), color: s.primaryContainer.withValues(alpha: 0.5)),
          child: Row(children: [
            Icon(running ? Icons.my_location_rounded : Icons.place_outlined, color: s.primary),
            const SizedBox(width: 10),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(clone.mockName, maxLines: 1, overflow: TextOverflow.ellipsis, style: tt.titleMedium),
                Text('${clone.mockLat.toStringAsFixed(5)}, ${clone.mockLng.toStringAsFixed(5)}',
                    style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
              ]),
            ),
            if (running) Text('模拟中', style: tt.labelMedium?.copyWith(color: s.tertiary, fontWeight: FontWeight.w700)),
            IconButton(tooltip: '收藏当前位置', onPressed: () => _saveFavorite(loc), icon: const Icon(Icons.star_border_rounded)),
          ]),
        ),
        if (loc.favorites.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text('我的收藏 · 点按应用，长按管理', style: tt.labelMedium?.copyWith(color: s.onSurfaceVariant)),
          const SizedBox(height: 6),
          Wrap(spacing: 8, runSpacing: 8, children: [
            for (final f in loc.favorites)
              GestureDetector(
                onLongPress: () => _manageFavorite(loc, f),
                child: ActionChip(
                  avatar: Icon(Icons.star_rounded, size: 16, color: s.tertiary),
                  label: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 140), child: Text(f.name, overflow: TextOverflow.ellipsis)),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  onPressed: () => _set(store, loc, f.name, f.lat, f.lng),
                ),
              ),
          ]),
        ],
        const SizedBox(height: 12),
        Text('预设城市', style: tt.labelMedium?.copyWith(color: s.onSurfaceVariant)),
        const SizedBox(height: 6),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final p in presetLocations)
            ChoiceChip(
              label: Text(p.name),
              selected: clone.mockName == p.name && clone.mockLat == p.lat,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              onSelected: (_) => _set(store, loc, p.name, p.lat, p.lng),
            ),
        ]),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(
            child: OutlinedButton.icon(
              onPressed: () => _manual(store, loc),
              icon: const Icon(Icons.edit_location_alt_rounded),
              label: const FittedBox(child: Text('输入坐标')),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: () => _pick(store, loc),
              icon: const Icon(Icons.public_rounded),
              label: const FittedBox(child: Text('地图选点')),
            ),
          ),
        ]),
        const SizedBox(height: 14),
        _BigButton(
          label: running ? '停止' : (otherRunning ? '切换到此位置并开始模拟' : '开始模拟'),
          danger: running,
          onTap: () => running ? _stop(store, loc) : _start(store, loc),
        ),
      ];

  List<Widget> _routeUi(CloneStore store, LocationStore loc, ColorScheme s, TextTheme tt, bool here) {
    final sel = here ? loc.activeRoute : _selected(loc);
    final speed = here ? loc.activeRoute!.speed : (_speed ?? sel?.speed ?? SpeedMode.walk);
    final loop = here ? loc.activeRoute!.loop : (_loop ?? sel?.loop ?? false);
    return [
      for (final r in loc.routes)
        _RouteTile(
          route: r,
          selected: sel?.id == r.id,
          enabled: !here,
          onTap: () => setState(() {
            _routeId = r.id;
            _speed = null;
            _loop = null;
          }),
          onEdit: r.id.startsWith('preset:') ? null : () => _editRoute(loc, r),
          onDelete: r.id.startsWith('preset:') ? null : () => loc.removeRoute(r.id),
        ),
      Align(
        alignment: Alignment.centerLeft,
        child: TextButton.icon(onPressed: () => _editRoute(loc), icon: const Icon(Icons.add_road_rounded), label: const Text('新建路线')),
      ),
      IgnorePointer(
        ignoring: here,
        child: Opacity(
          opacity: here ? 0.5 : 1,
          child: Column(children: [
            SizedBox(
              width: double.infinity,
              child: SegmentedButton<SpeedMode>(
                showSelectedIcon: false,
                segments: [
                  for (final m in SpeedMode.values)
                    ButtonSegment(value: m, label: FittedBox(child: Text('${m.label} ${(m.mps * 3.6).toStringAsFixed(0)}km/h'))),
                ],
                selected: {speed},
                onSelectionChanged: (v) => setState(() => _speed = v.first),
              ),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('循环往复'),
              subtitle: Text(loop ? '到达终点后回到起点继续' : '单程，到达终点后停在终点'),
              value: loop,
              onChanged: (v) => setState(() => _loop = v),
            ),
          ]),
        ),
      ),
      if (here && loc.sample != null) _PlaybackPanel(loc: loc),
      const SizedBox(height: 10),
      if (!here)
        _BigButton(
          label: sel == null ? '请先选择路线' : '开始路线模拟',
          onTap: sel == null ? null : () => _startRoute(store, loc, sel),
        )
      else
        Row(children: [
          Expanded(
            child: _BigButton(
              label: loc.isPaused ? ((loc.sample?.finished ?? false) ? '重新开始' : '继续') : '暂停',
              tonal: true,
              onTap: loc.isPaused ? loc.resumeRoute : loc.pauseRoute,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(child: _BigButton(label: '停止', danger: true, onTap: () => _stop(store, loc))),
        ]),
    ];
  }
}

class _RouteTile extends StatelessWidget {
  const _RouteTile({required this.route, required this.selected, required this.enabled, required this.onTap, this.onEdit, this.onDelete});
  final MockRoute route;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;
  final VoidCallback? onEdit;
  final VoidCallback? onDelete;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final km = route.lengthMeters / 1000;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Pressable(
        radius: 18,
        scale: 0.98,
        onTap: enabled ? onTap : null,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            color: selected ? s.primaryContainer.withValues(alpha: 0.6) : s.surfaceContainerHighest.withValues(alpha: 0.3),
            border: Border.all(color: selected ? s.primary : Colors.transparent, width: 1.5),
          ),
          child: Row(children: [
            Icon(route.loop ? Icons.loop_rounded : Icons.route_rounded, color: s.primary),
            const SizedBox(width: 10),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(route.name, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
                Text(
                  '${route.points.length} 个点 · ${km.toStringAsFixed(km < 10 ? 2 : 1)} km · ${route.speed.label}${route.id.startsWith('preset:') ? ' · 预设' : ''}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 12, color: s.onSurfaceVariant),
                ),
              ]),
            ),
            if (onEdit != null && enabled) IconButton(tooltip: '编辑', onPressed: onEdit, icon: const Icon(Icons.edit_rounded, size: 20)),
            if (onDelete != null && enabled) IconButton(tooltip: '删除', onPressed: onDelete, icon: const Icon(Icons.delete_outline_rounded, size: 20)),
          ]),
        ),
      ),
    );
  }
}

class _PlaybackPanel extends StatelessWidget {
  const _PlaybackPanel({required this.loc});
  final LocationStore loc;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final r = loc.activeRoute!;
    final smp = loc.sample!;
    final total = r.lengthMeters;
    final done = total * smp.progress;
    final leftSec = r.speed.mps <= 0 ? 0 : ((total - done) / r.speed.mps).round();
    String dur(int sec) => sec >= 3600 ? '${sec ~/ 3600} 小时 ${(sec % 3600) ~/ 60} 分' : '${sec ~/ 60} 分 ${sec % 60} 秒';
    return Container(
      margin: const EdgeInsets.only(top: 4),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(20), color: s.tertiaryContainer.withValues(alpha: 0.5)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Icon(loc.isPaused ? Icons.pause_circle_rounded : Icons.navigation_rounded, color: s.tertiary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              smp.finished ? '已到达终点' : (loc.isPaused ? '已暂停' : '${r.speed.label}中 · 航向 ${smp.bearing.toStringAsFixed(0)}°'),
              style: tt.titleSmall,
            ),
          ),
          Text('${(smp.progress * 100).toStringAsFixed(0)}%', style: tt.titleSmall?.copyWith(color: s.tertiary)),
        ]),
        const SizedBox(height: 10),
        ClipRRect(
          borderRadius: BorderRadius.circular(6),
          child: TweenAnimationBuilder<double>(
            tween: Tween(end: smp.progress),
            duration: const Duration(milliseconds: 450),
            builder: (_, v, _) => LinearProgressIndicator(value: v, minHeight: 8),
          ),
        ),
        const SizedBox(height: 8),
        Text(
          '${smp.point.lat.toStringAsFixed(5)}, ${smp.point.lng.toStringAsFixed(5)}\n'
          '${(done / 1000).toStringAsFixed(2)} / ${(total / 1000).toStringAsFixed(2)} km'
          '${r.loop ? ' · 本圈' : ''} · 剩余约 ${dur(leftSec)}',
          style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant, height: 1.5),
        ),
      ]),
    );
  }
}

class _BigButton extends StatelessWidget {
  const _BigButton({required this.label, this.onTap, this.danger = false, this.tonal = false});
  final String label;
  final VoidCallback? onTap;
  final bool danger;
  final bool tonal;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final disabled = onTap == null;
    final fg = danger ? s.onErrorContainer : (tonal ? s.onSecondaryContainer : Colors.white);
    return SizedBox(
      width: double.infinity,
      child: Opacity(
        opacity: disabled ? 0.5 : 1,
        child: Pressable(
          radius: 20,
          onTap: onTap,
          child: Ink(
            padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 8),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(20),
              gradient: danger || tonal ? null : AppTheme.brandGradient(s),
              color: danger ? s.errorContainer : (tonal ? s.secondaryContainer : null),
            ),
            child: Center(
              child: Text(label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: fg, fontWeight: FontWeight.w700, fontSize: 16)),
            ),
          ),
        ),
      ),
    );
  }
}

/// 未被选为模拟位置应用时的引导面板。
void showMockGuideSheet(BuildContext context) {
  showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    sheetAnimationStyle: const AnimationStyle(
      duration: Duration(milliseconds: 600),
      reverseDuration: Duration(milliseconds: 250),
      curve: Curves.elasticOut,
    ),
    builder: (ctx) {
      final s = Theme.of(ctx).colorScheme;
      final tt = Theme.of(ctx).textTheme;
      Widget step(int n, String t) => Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              CircleAvatar(
                radius: 12,
                backgroundColor: s.primary,
                child: Text('$n', style: TextStyle(color: s.onPrimary, fontSize: 12, fontWeight: FontWeight.w700)),
              ),
              const SizedBox(width: 12),
              Expanded(child: Text(t, style: tt.bodyMedium?.copyWith(height: 1.5))),
            ]),
          );
      return GlassSheet(
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(Icons.developer_mode_rounded, color: s.primary, size: 28),
            const SizedBox(width: 10),
            Expanded(child: Text('需要开启模拟位置权限', style: tt.titleLarge)),
          ]),
          const SizedBox(height: 12),
          step(1, '打开“设置 → 关于手机”，连续点击“版本号”7 次开启开发者选项'),
          step(2, '进入“开发者选项 → 选择模拟位置信息应用”'),
          step(3, '选择“Alter Ego”，返回本页再次点击“开始模拟”'),
          const SizedBox(height: 8),
          Text('开启后，模拟位置将对整台设备生效，停止后恢复真实定位。', style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
          const SizedBox(height: 20),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
              ),
              onPressed: () {
                Navigator.pop(ctx);
                NativeBridge.openDevSettings();
              },
              icon: const Icon(Icons.open_in_new_rounded),
              label: const Text('前往开发者选项'),
            ),
          ),
        ]),
      );
    },
  );
}

/// 单点选点面板（可拖动缩放）。
class PointPickerSheet extends StatefulWidget {
  const PointPickerSheet({super.key, required this.initial});
  final GeoPoint initial;

  @override
  State<PointPickerSheet> createState() => _PointPickerSheetState();
}

class _PointPickerSheetState extends State<PointPickerSheet> {
  late GeoPoint _p = widget.initial;
  late GeoViewport _vp = GeoViewport(widget.initial, 2);

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final h = (MediaQuery.sizeOf(context).height * 0.42).clamp(200.0, 420.0);
    return GlassSheet(
      scrollable: false,
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        Text('地图选点', style: tt.titleLarge),
        const SizedBox(height: 4),
        Text('点击放置定位针，拖动平移，双指或按钮缩放', style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
        const SizedBox(height: 14),
        Flexible(
          child: SizedBox(
          height: h,
          child: Stack(children: [
            Positioned.fill(
              child: GeoCanvas(viewport: _vp, onViewport: (v) => setState(() => _vp = v), pin: _p, onTap: (p) => setState(() => _p = p)),
            ),
            Positioned(right: 8, top: 8, child: ZoomButtons(viewport: _vp, onViewport: (v) => setState(() => _vp = v))),
          ]),
        ),
        ),
        const SizedBox(height: 12),
        Text('纬度 ${_p.lat.toStringAsFixed(5)}   经度 ${_p.lng.toStringAsFixed(5)}', style: tt.titleMedium),
        const SizedBox(height: 16),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 16),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
            ),
            onPressed: () => Navigator.pop(context, _p),
            child: const Text('使用此位置'),
          ),
        ),
      ]),
    );
  }
}
