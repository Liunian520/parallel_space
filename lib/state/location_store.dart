import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../data/mock_locations.dart';
import '../models/location_models.dart';
import '../services/native_bridge.dart';

/// 收藏定位点 + 用户路线 + 路线回放引擎。
///
/// 回放在 Dart 侧以计时器推进（与 Kotlin 共用相同的 RouteMath 插值），
/// 每一步通过 NativeBridge.startMock 把插值坐标推给系统级模拟定位服务；
/// 该前台服务会持续重推最后坐标，因此 App 退到后台时位置会停在当前点。
class LocationStore extends ChangeNotifier {
  LocationStore(this._prefs, {DateTime Function()? now}) : _now = now ?? DateTime.now {
    _load();
  }

  final DateTime Function() _now;

  static const _favKey = 'loc.favorites.v1';
  static const _routeKey = 'loc.routes.v1';
  final SharedPreferences _prefs;

  final List<FavoriteLocation> _favorites = [];
  final List<MockRoute> _routes = [];

  List<FavoriteLocation> get favorites => List.unmodifiable(_favorites);

  /// 内置预设 + 用户自建路线。
  List<MockRoute> get routes => [...presetRoutes(), ..._routes];
  List<MockRoute> get userRoutes => List.unmodifiable(_routes);

  // ---- 回放状态 ----
  Timer? _timer;
  DateTime? _segStart; // 当前播放段开始时间（暂停时为 null）
  double _baseMeters = 0; // 之前各段累计
  MockRoute? _route;
  String? _cloneUid; // 回放绑定的分身
  RouteSample? _sample;

  bool get isPlaying => _route != null && _timer != null;
  bool get isPaused => _route != null && _timer == null;
  bool get isActive => _route != null;
  MockRoute? get activeRoute => _route;
  String? get activeCloneUid => _cloneUid;
  RouteSample? get sample => _sample;

  double get _traveled {
    final st = _segStart;
    if (st == null) return _baseMeters;
    return _baseMeters + _now().difference(st).inMilliseconds / 1000.0 * (_route?.speed.mps ?? 0);
  }
  double get progress => _sample?.progress ?? 0;

  void _load() {
    _favorites.clear();
    _routes.clear();
    final f = _prefs.getString(_favKey);
    if (f != null) {
      try {
        for (final e in jsonDecode(f) as List) {
          final x = FavoriteLocation.fromJson(e);
          if (x != null) _favorites.add(x);
        }
      } catch (_) {}
    }
    final r = _prefs.getString(_routeKey);
    if (r != null) {
      try {
        for (final e in jsonDecode(r) as List) {
          final x = MockRoute.fromJson(e);
          if (x != null) _routes.add(x);
        }
      } catch (_) {}
    }
  }

  void _saveFav() => _prefs.setString(_favKey, jsonEncode([for (final f in _favorites) f.toJson()]));
  void _saveRoutes() => _prefs.setString(_routeKey, jsonEncode([for (final r in _routes) r.toJson()]));

  // ---- 收藏 ----
  FavoriteLocation addFavorite(String name, double lat, double lng) {
    final f = FavoriteLocation(
      id: 'f${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}',
      name: name.trim().isEmpty ? '未命名' : name.trim(),
      lat: lat,
      lng: lng,
    );
    _favorites.insert(0, f);
    _saveFav();
    notifyListeners();
    return f;
  }

  void renameFavorite(String id, String name) {
    final f = _favorites.where((e) => e.id == id).firstOrNull;
    if (f != null && name.trim().isNotEmpty) {
      f.name = name.trim();
      _saveFav();
      notifyListeners();
    }
  }

  void removeFavorite(String id) {
    _favorites.removeWhere((e) => e.id == id);
    _saveFav();
    notifyListeners();
  }

  // ---- 用户路线 ----
  MockRoute saveRoute({String? id, required String name, required List<GeoPoint> points, bool loop = false, SpeedMode speed = SpeedMode.walk}) {
    final rid = id ?? 'r${DateTime.now().microsecondsSinceEpoch.toRadixString(36)}';
    final route = MockRoute(id: rid, name: name.trim().isEmpty ? '未命名路线' : name.trim(), points: points, loop: loop, speed: speed);
    final i = _routes.indexWhere((e) => e.id == rid);
    if (i == -1) {
      _routes.insert(0, route);
    } else {
      _routes[i] = route;
    }
    _saveRoutes();
    notifyListeners();
    return route;
  }

  /// 删除路线。正在回放的是独立副本，不受影响。
  void removeRoute(String id) {
    _routes.removeWhere((e) => e.id == id);
    _saveRoutes();
    notifyListeners();
  }

  /// 导入备份：replace 时清空后写入；否则按 id 合并（跳过已存在）。返回新增数量。
  int importData(List<FavoriteLocation> favs, List<MockRoute> routes, {required bool replace}) {
    if (replace) {
      stopRoute();
      _favorites.clear();
      _routes.clear();
    }
    var n = 0;
    for (final f in favs) {
      if (_favorites.any((e) => e.id == f.id)) continue;
      _favorites.add(f);
      n++;
    }
    for (final r in routes) {
      if (r.id.startsWith('preset:') || _routes.any((e) => e.id == r.id)) continue;
      _routes.add(r);
      n++;
    }
    _saveFav();
    _saveRoutes();
    notifyListeners();
    return n;
  }

  // ---- 回放 ----
  Future<bool> startRoute(MockRoute route, String cloneUid) async {
    if (route.points.length < 2) return false;
    final first = RouteMath.sample(route.points, 0, loop: route.loop).point;
    final r = await NativeBridge.startMock(first.lat, first.lng);
    if (r != MockResult.ok) return false;
    _route = route;
    _cloneUid = cloneUid;
    _baseMeters = 0;
    _segStart = _now();
    _sample = RouteMath.sample(route.points, 0, loop: route.loop);
    _startTimer();
    notifyListeners();
    return true;
  }

  void pauseRoute() {
    if (!isPlaying) return;
    _baseMeters = _traveled;
    _segStart = null;
    _timer?.cancel();
    _timer = null;
    notifyListeners();
  }

  void resumeRoute() {
    if (!isPaused) return;
    if (_sample?.finished ?? false) _baseMeters = 0; // 单程已到终点：从头再走
    _segStart = _now();
    _startTimer();
    notifyListeners();
  }

  void stopRoute() {
    _timer?.cancel();
    _timer = null;
    _segStart = null;
    _route = null;
    _cloneUid = null;
    _sample = null;
    _baseMeters = 0;
    notifyListeners();
  }

  void _startTimer() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(milliseconds: 500), (_) => _tick());
  }

  void _tick() {
    final route = _route;
    if (route == null) return;
    final s = RouteMath.sample(route.points, _traveled, loop: route.loop);
    _sample = s;
    NativeBridge.updateMock(s.point.lat, s.point.lng, bearing: s.bearing, speed: s.finished ? 0 : route.speed.mps);
    if (s.finished && !route.loop) {
      pauseRoute(); // 单程走完：停在终点并暂停
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}
