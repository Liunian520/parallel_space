import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../data/mock_locations.dart';
import '../models/location_models.dart';
import '../state/location_store.dart';
import '../widgets/geo_canvas.dart';
import '../widgets/glass.dart';

/// 多点路线编辑：在可缩放网格地图上依次点击添加途经点。
class RouteEditorSheet extends StatefulWidget {
  const RouteEditorSheet({super.key, this.initial});
  final MockRoute? initial;

  @override
  State<RouteEditorSheet> createState() => _RouteEditorSheetState();
}

class _RouteEditorSheetState extends State<RouteEditorSheet> {
  late final _name = TextEditingController(text: widget.initial?.name ?? '我的路线');
  late final List<GeoPoint> _pts = [...?widget.initial?.points];
  late bool _loop = widget.initial?.loop ?? false;
  late SpeedMode _speed = widget.initial?.speed ?? SpeedMode.walk;
  late GeoViewport _vp = GeoViewport(
    _pts.isNotEmpty ? _pts.first : GeoPoint(presetLocations.first.lat, presetLocations.first.lng),
    0.05,
  );

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    final km = RouteMath.totalLength(_pts, loop: _loop) / 1000;
    final h = (MediaQuery.sizeOf(context).height * 0.36).clamp(160.0, 380.0);
    return GlassSheet(
      scrollable: false,
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(widget.initial == null ? '新建路线' : '编辑路线', style: tt.titleLarge)),
          IconButton(tooltip: '撤销', onPressed: _pts.isEmpty ? null : () => setState(_pts.removeLast), icon: const Icon(Icons.undo_rounded)),
          IconButton(tooltip: '清空', onPressed: _pts.isEmpty ? null : () => setState(_pts.clear), icon: const Icon(Icons.delete_sweep_rounded)),
        ]),
        Text('点击地图依次添加途经点（至少 2 个），拖动平移，双指或按钮缩放',
            style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
        const SizedBox(height: 8),
        SizedBox(
          height: 36,
          child: ListView(scrollDirection: Axis.horizontal, children: [
            for (final p in presetLocations)
              Padding(
                padding: const EdgeInsets.only(right: 6),
                child: ActionChip(
                  visualDensity: VisualDensity.compact,
                  label: Text(p.name),
                  onPressed: () => setState(() => _vp = GeoViewport(GeoPoint(p.lat, p.lng), 0.05)),
                ),
              ),
          ]),
        ),
        const SizedBox(height: 8),
        Flexible(
          child: SizedBox(
            height: h,
            child: Stack(children: [
              Positioned.fill(
                child: GeoCanvas(
                  viewport: _vp,
                  onViewport: (v) => setState(() => _vp = v),
                  route: List.unmodifiable(_pts),
                  loop: _loop,
                  onTap: (p) => setState(() => _pts.add(p)),
                ),
              ),
              Positioned(right: 8, top: 8, child: ZoomButtons(viewport: _vp, onViewport: (v) => setState(() => _vp = v))),
            ]),
          ),
        ),
        const SizedBox(height: 8),
        Text('${_pts.length} 个点 · ${km.toStringAsFixed(2)} km', style: tt.titleSmall),
        const SizedBox(height: 8),
        TextField(
          controller: _name,
          maxLength: 16,
          decoration: const InputDecoration(labelText: '路线名称', isDense: true, counterText: ''),
        ),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(
            child: SegmentedButton<SpeedMode>(
              showSelectedIcon: false,
              segments: [for (final m in SpeedMode.values) ButtonSegment(value: m, label: Text(m.label))],
              selected: {_speed},
              onSelectionChanged: (v) => setState(() => _speed = v.first),
            ),
          ),
          const SizedBox(width: 8),
          FilterChip(label: const Text('循环'), selected: _loop, onSelected: (v) => setState(() => _loop = v)),
        ]),
        const SizedBox(height: 14),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            style: FilledButton.styleFrom(
              padding: const EdgeInsets.symmetric(vertical: 16),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
            ),
            onPressed: _pts.length < 2
                ? null
                : () {
                    final r = context.read<LocationStore>().saveRoute(
                          id: widget.initial?.id,
                          name: _name.text,
                          points: List.of(_pts),
                          loop: _loop,
                          speed: _speed,
                        );
                    Navigator.pop(context, r);
                  },
            child: Text(_pts.length < 2 ? '至少添加 2 个点' : '保存路线'),
          ),
        ),
      ]),
    );
  }
}
