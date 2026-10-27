import 'dart:math';

/// 收藏的定位点。
class FavoriteLocation {
  FavoriteLocation({required this.id, required this.name, required this.lat, required this.lng});
  final String id;
  String name;
  final double lat;
  final double lng;

  Map<String, dynamic> toJson() => {'id': id, 'name': name, 'lat': lat, 'lng': lng};

  static FavoriteLocation? fromJson(Object? o) {
    if (o is! Map) return null;
    final lat = o['lat'], lng = o['lng'], name = o['name'];
    if (lat is! num || lng is! num || name is! String || name.trim().isEmpty) return null;
    if (lat.abs() > 90 || lng.abs() > 180) return null;
    final id = o['id'] is String ? o['id'] as String : 'f${name.hashCode.abs()}_${lat}_$lng';
    return FavoriteLocation(id: id, name: name.trim(), lat: lat.toDouble(), lng: lng.toDouble());
  }
}

/// 速度模式。
enum SpeedMode { walk, bike, drive }

extension SpeedModeX on SpeedMode {
  String get label => const {SpeedMode.walk: '步行', SpeedMode.bike: '骑行', SpeedMode.drive: '驾车'}[this]!;

  /// 米/秒
  double get mps => const {SpeedMode.walk: 1.4, SpeedMode.bike: 4.5, SpeedMode.drive: 13.9}[this]!;

  static SpeedMode parse(Object? s) => SpeedMode.values.where((e) => e.name == s).firstOrNull ?? SpeedMode.walk;
}

/// 一条路线（≥2 个点），[loop] = 循环，否则单程。
class MockRoute {
  MockRoute({required this.id, required this.name, required this.points, this.loop = false, this.speed = SpeedMode.walk});
  final String id;
  String name;
  final List<GeoPoint> points;
  bool loop;
  SpeedMode speed;

  double get lengthMeters => RouteMath.totalLength(points, loop: loop);

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'points': [for (final p in points) [p.lat, p.lng]],
        'loop': loop,
        'speed': speed.name,
      };

  static MockRoute? fromJson(Object? o) {
    if (o is! Map || o['name'] is! String || o['points'] is! List) return null;
    final pts = <GeoPoint>[];
    for (final p in o['points'] as List) {
      if (p is List && p.length >= 2 && p[0] is num && p[1] is num) {
        final la = (p[0] as num).toDouble(), lo = (p[1] as num).toDouble();
        if (la.abs() <= 90 && lo.abs() <= 180) pts.add(GeoPoint(la, lo));
      }
    }
    if (pts.length < 2) return null;
    return MockRoute(
      id: o['id'] is String ? o['id'] as String : 'r${(o['name'] as String).hashCode.abs()}',
      name: o['name'] as String,
      points: pts,
      loop: o['loop'] == true,
      speed: SpeedModeX.parse(o['speed']),
    );
  }
}

class GeoPoint {
  const GeoPoint(this.lat, this.lng);
  final double lat;
  final double lng;
  @override
  bool operator ==(Object other) => other is GeoPoint && other.lat == lat && other.lng == lng;
  @override
  int get hashCode => Object.hash(lat, lng);
  @override
  String toString() => 'GeoPoint($lat, $lng)';
}

/// 路线某一时刻的状态。
class RouteSample {
  const RouteSample(this.point, this.bearing, this.progress, this.finished);
  final GeoPoint point;
  final double bearing; // 0..360
  final double progress; // 0..1（循环时为当前圈进度）
  final bool finished;
}

/// 与 Kotlin MockLocationService 中的实现保持一致（用于预览与单元测试）。
class RouteMath {
  static const earthR = 6371000.0;

  static double distance(GeoPoint a, GeoPoint b) {
    final p1 = a.lat * pi / 180, p2 = b.lat * pi / 180;
    final dp = p2 - p1, dl = (b.lng - a.lng) * pi / 180;
    final h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2);
    return 2 * earthR * atan2(sqrt(h), sqrt(max(0.0, 1 - h)));
  }

  static double bearing(GeoPoint a, GeoPoint b) {
    final p1 = a.lat * pi / 180, p2 = b.lat * pi / 180, dl = (b.lng - a.lng) * pi / 180;
    final y = sin(dl) * cos(p2);
    final x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl);
    return (atan2(y, x) * 180 / pi + 360) % 360;
  }

  static List<GeoPoint> _path(List<GeoPoint> pts, bool loop) =>
      loop && pts.length > 2 && pts.first != pts.last ? [...pts, pts.first] : pts;

  static double totalLength(List<GeoPoint> pts, {bool loop = false}) {
    final p = _path(pts, loop);
    var t = 0.0;
    for (var i = 1; i < p.length; i++) {
      t += distance(p[i - 1], p[i]);
    }
    return t;
  }

  /// 走过 [traveled] 米后的位置。线性插值经纬度（短段误差可忽略），并处理经度跨 ±180。
  static RouteSample sample(List<GeoPoint> pts, double traveled, {bool loop = false}) {
    if (pts.isEmpty) return const RouteSample(GeoPoint(0, 0), 0, 1, true);
    if (pts.length == 1) return RouteSample(pts.first, 0, 1, true);
    final p = _path(pts, loop);
    final total = totalLength(pts, loop: loop);
    if (total <= 0) return RouteSample(p.first, 0, 1, !loop);
    var d = max(0.0, traveled);
    var finished = false;
    if (loop) {
      d = d % total;
    } else if (d >= total) {
      d = total;
      finished = true;
    }
    for (var i = 1; i < p.length; i++) {
      final seg = distance(p[i - 1], p[i]);
      if (d <= seg || i == p.length - 1) {
        final f = seg <= 0 ? 1.0 : (d / seg).clamp(0.0, 1.0);
        final a = p[i - 1], b = p[i];
        var dLng = b.lng - a.lng;
        if (dLng > 180) dLng -= 360;
        if (dLng < -180) dLng += 360;
        var lng = a.lng + dLng * f;
        if (lng > 180) lng -= 360;
        if (lng < -180) lng += 360;
        return RouteSample(GeoPoint(a.lat + (b.lat - a.lat) * f, lng), bearing(a, b), (traveled >= total && !loop) ? 1 : d / total, finished);
      }
      d -= seg;
    }
    return RouteSample(p.last, 0, 1, true);
  }
}
