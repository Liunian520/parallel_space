import 'dart:math';

import 'package:flutter/material.dart';

import '../data/mock_locations.dart';
import '../models/location_models.dart';

/// 视口：中心点 + 横向经度跨度（等距圆柱投影）。
class GeoViewport {
  const GeoViewport(this.center, this.span);
  final GeoPoint center;
  final double span; // 视口宽度对应的经度跨度（度）

  static const minSpan = 0.002, maxSpan = 360.0;

  GeoViewport copyWith({GeoPoint? center, double? span}) {
    final sp = (span ?? this.span).clamp(minSpan, maxSpan);
    final c = center ?? this.center;
    var lng = c.lng;
    while (lng > 180) {
      lng -= 360;
    }
    while (lng < -180) {
      lng += 360;
    }
    return GeoViewport(GeoPoint(c.lat.clamp(-85.0, 85.0), lng), sp);
  }

  double degPerPx(Size s) => span / max(1.0, s.width);

  Offset toPx(GeoPoint p, Size s) {
    final k = degPerPx(s);
    var dLng = p.lng - center.lng;
    if (dLng > 180) dLng -= 360;
    if (dLng < -180) dLng += 360;
    return Offset(s.width / 2 + dLng / k, s.height / 2 - (p.lat - center.lat) / k);
  }

  GeoPoint toGeo(Offset o, Size s) {
    final k = degPerPx(s);
    var lng = center.lng + (o.dx - s.width / 2) * k;
    if (lng > 180) lng -= 360;
    if (lng < -180) lng += 360;
    final lat = (center.lat - (o.dy - s.height / 2) * k).clamp(-90.0, 90.0);
    return GeoPoint(lat, lng);
  }

  /// 根据跨度选择网格步长。
  static double gridStep(double span) {
    const steps = [90.0, 30, 10, 5, 2, 1, 0.5, 0.2, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002, 0.001, 0.0005, 0.0002];
    for (final s in steps) {
      if (span / s >= 3) return s.toDouble();
    }
    return steps.last.toDouble();
  }
}

/// 可拖动/缩放的经纬度网格。点击回调 [onTap]。
class GeoCanvas extends StatefulWidget {
  const GeoCanvas({
    super.key,
    required this.viewport,
    required this.onViewport,
    this.onTap,
    this.pin,
    this.route = const [],
    this.loop = false,
    this.marker,
  });

  final GeoViewport viewport;
  final ValueChanged<GeoViewport> onViewport;
  final ValueChanged<GeoPoint>? onTap;
  final GeoPoint? pin;
  final List<GeoPoint> route;
  final bool loop;
  final GeoPoint? marker; // 回放中的当前位置

  @override
  State<GeoCanvas> createState() => _GeoCanvasState();
}

class _GeoCanvasState extends State<GeoCanvas> {
  GeoViewport? _start;
  Offset _focal = Offset.zero;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    return LayoutBuilder(builder: (context, box) {
      final size = box.biggest;
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapUp: widget.onTap == null ? null : (d) => widget.onTap!(widget.viewport.toGeo(d.localPosition, size)),
        onScaleStart: (d) {
          _start = widget.viewport;
          _focal = d.localFocalPoint;
        },
        onScaleUpdate: (d) {
          final st = _start;
          if (st == null) return;
          final span = (st.span / d.scale).clamp(GeoViewport.minSpan, GeoViewport.maxSpan);
          // 以手势起点为锚点缩放，并叠加平移
          final anchor = st.toGeo(_focal, size);
          final tmp = GeoViewport(anchor, span);
          final shifted = tmp.toGeo(Offset(size.width - d.localFocalPoint.dx, size.height - d.localFocalPoint.dy), size);
          widget.onViewport(st.copyWith(center: shifted, span: span));
        },
        onScaleEnd: (_) => _start = null,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(20),
          child: CustomPaint(
            size: size,
            painter: _GeoPainter(s, widget.viewport, widget.pin, widget.route, widget.loop, widget.marker),
          ),
        ),
      );
    });
  }
}

class _GeoPainter extends CustomPainter {
  _GeoPainter(this.s, this.vp, this.pin, this.route, this.loop, this.marker);
  final ColorScheme s;
  final GeoViewport vp;
  final GeoPoint? pin;
  final List<GeoPoint> route;
  final bool loop;
  final GeoPoint? marker;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(
      Offset.zero & size,
      Paint()
        ..shader = LinearGradient(
          colors: [s.primaryContainer, s.tertiaryContainer],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ).createShader(Offset.zero & size),
    );
    final step = GeoViewport.gridStep(vp.span);
    final tl = vp.toGeo(Offset.zero, size), br = vp.toGeo(Offset(size.width, size.height), size);
    final line = Paint()..color = s.onPrimaryContainer.withValues(alpha: 0.13);
    final label = TextPainter(textDirection: TextDirection.ltr);
    String fmt(double v) => step >= 1 ? v.toStringAsFixed(0) : v.toStringAsFixed((-log(step) / ln10).ceil());

    final halfLng = vp.span / 2;
    for (var lng = ((vp.center.lng - halfLng) / step).floor() * step; lng <= vp.center.lng + halfLng; lng += step) {
      final x = vp.toPx(GeoPoint(0, lng), size).dx;
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), line..strokeWidth = lng.abs() < 1e-9 ? 2 : 1);
      label
        ..text = TextSpan(text: fmt(lng), style: TextStyle(fontSize: 9, color: s.onPrimaryContainer.withValues(alpha: 0.45)))
        ..layout();
      label.paint(canvas, Offset(x + 2, size.height - 12));
    }
    for (var lat = (br.lat / step).floor() * step; lat <= tl.lat; lat += step) {
      final y = vp.toPx(GeoPoint(lat, vp.center.lng), size).dy;
      canvas.drawLine(Offset(0, y), Offset(size.width, y), line..strokeWidth = lat.abs() < 1e-9 ? 2 : 1);
      label
        ..text = TextSpan(text: fmt(lat), style: TextStyle(fontSize: 9, color: s.onPrimaryContainer.withValues(alpha: 0.45)))
        ..layout();
      label.paint(canvas, Offset(2, y + 1));
    }

    // 预设城市
    final dot = Paint()..color = s.primary.withValues(alpha: 0.6);
    for (final c in presetLocations) {
      final o = vp.toPx(GeoPoint(c.lat, c.lng), size);
      if (!(Offset.zero & size).inflate(20).contains(o)) continue;
      canvas.drawCircle(o, 3, dot);
      if (vp.span < 60) {
        label
          ..text = TextSpan(text: c.name, style: TextStyle(fontSize: 10, color: s.onPrimaryContainer.withValues(alpha: 0.7)))
          ..layout();
        label.paint(canvas, o + const Offset(5, -6));
      }
    }

    // 路线
    if (route.isNotEmpty) {
      final pts = [for (final p in route) vp.toPx(p, size)];
      final path = Path()..moveTo(pts.first.dx, pts.first.dy);
      for (final p in pts.skip(1)) {
        path.lineTo(p.dx, p.dy);
      }
      if (loop && pts.length > 2) path.close();
      canvas.drawPath(
        path,
        Paint()
          ..color = s.primary
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3.5
          ..strokeJoin = StrokeJoin.round
          ..strokeCap = StrokeCap.round,
      );
      for (var i = 0; i < pts.length; i++) {
        canvas.drawCircle(pts[i], 9, Paint()..color = i == 0 ? s.tertiary : s.primary);
        label
          ..text = TextSpan(text: '${i + 1}', style: TextStyle(fontSize: 10, color: s.onPrimary, fontWeight: FontWeight.w700))
          ..layout();
        label.paint(canvas, pts[i] - Offset(label.width / 2, label.height / 2));
      }
    }

    void drawPin(GeoPoint p, Color c) {
      final o = vp.toPx(p, size);
      canvas.drawCircle(o, 14, Paint()..color = c.withValues(alpha: 0.2));
      canvas.drawCircle(o, 6, Paint()..color = c);
      canvas.drawCircle(o, 6, Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2);
    }

    if (pin != null) drawPin(pin!, s.error);
    if (marker != null) drawPin(marker!, s.tertiary);
  }

  @override
  bool shouldRepaint(covariant _GeoPainter o) =>
      o.vp != vp || o.pin != pin || o.route != route || o.loop != loop || o.marker != marker || o.s != s;
}

/// 缩放按钮组。
class ZoomButtons extends StatelessWidget {
  const ZoomButtons({super.key, required this.viewport, required this.onViewport});
  final GeoViewport viewport;
  final ValueChanged<GeoViewport> onViewport;

  @override
  Widget build(BuildContext context) {
    return Column(mainAxisSize: MainAxisSize.min, children: [
      IconButton.filledTonal(
        tooltip: '放大',
        onPressed: () => onViewport(viewport.copyWith(span: viewport.span / 2.5)),
        icon: const Icon(Icons.add_rounded),
      ),
      IconButton.filledTonal(
        tooltip: '缩小',
        onPressed: () => onViewport(viewport.copyWith(span: viewport.span * 2.5)),
        icon: const Icon(Icons.remove_rounded),
      ),
    ]);
  }
}
