import 'dart:convert';

import '../models/clone_app.dart';
import '../models/location_models.dart';

class BackupFormatException implements Exception {
  BackupFormatException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// 备份内容。PIN / PIN 哈希 / 盐永远不会写入。
class BackupData {
  BackupData({
    required this.clones,
    required this.favorites,
    required this.routes,
    required this.settings,
    this.skipped = 0,
    this.exportedAt,
  });
  final List<CloneApp> clones;
  final List<FavoriteLocation> favorites;
  final List<MockRoute> routes;
  final Map<String, dynamic> settings;
  final int skipped;
  final DateTime? exportedAt;
}

class BackupCodec {
  static const format = 'parallel_space.backup';
  static const version = 3;
  static const maxBytes = 2 * 1024 * 1024;

  /// 设置中允许导出/导入的键（白名单，防止敏感键混入）。
  static const settingKeys = {
    'title', 'theme', 'dyn', 'seed', 'fx',
    'updAuto', 'toolbox',
  };

  static String encode(BackupData d, {DateTime? now}) {
    final settings = Map.fromEntries(d.settings.entries.where((e) => settingKeys.contains(e.key)));
    return const JsonEncoder.withIndent('  ').convert({
      'format': format,
      'version': version,
      'exportedAt': (now ?? DateTime.now()).toUtc().toIso8601String(),
      'clones': [for (final c in d.clones) c.toJson()],
      'favorites': [for (final f in d.favorites) f.toJson()],
      'routes': [for (final r in d.routes) r.toJson()],
      'settings': settings,
    });
  }

  static BackupData decode(String text) {
    if (text.length > maxBytes) throw BackupFormatException('文件过大（超过 2 MB），不是有效的备份');
    Object? root;
    try {
      root = jsonDecode(text);
    } on FormatException {
      throw BackupFormatException('文件不是有效的 JSON');
    }
    if (root is! Map) throw BackupFormatException('备份格式不正确');
    if (root['format'] != format) throw BackupFormatException('这不是平行空间的备份文件');
    final v = root['version'];
    if (v is! int || v < 1) throw BackupFormatException('备份版本无效');
    if (v > version) throw BackupFormatException('备份来自更新版本的平行空间（v$v），请先升级 App');

    var skipped = 0;
    List<T> list<T>(String key, T? Function(Object?) parse) {
      final raw = root is Map ? root[key] : null;
      if (raw == null) return [];
      if (raw is! List) throw BackupFormatException('字段 $key 格式不正确');
      final out = <T>[];
      for (final e in raw) {
        T? x;
        try {
          x = parse(e);
        } catch (_) {
          x = null;
        }
        if (x == null) {
          skipped++;
        } else {
          out.add(x);
        }
      }
      return out;
    }

    final clones = list<CloneApp>('clones', (e) => e is Map<String, dynamic> ? CloneApp.fromJson(e) : null);
    // 去重 uid
    final seen = <String>{};
    clones.retainWhere((c) {
      final ok = seen.add(c.uid);
      if (!ok) skipped++;
      return ok;
    });
    final favs = list<FavoriteLocation>('favorites', FavoriteLocation.fromJson);
    final routes = list<MockRoute>('routes', MockRoute.fromJson);
    final rawSettings = root['settings'];
    final settings = <String, dynamic>{};
    if (rawSettings is Map) {
      for (final e in rawSettings.entries) {
        if (settingKeys.contains(e.key)) settings[e.key as String] = e.value;
      }
    }
    return BackupData(
      clones: clones,
      favorites: favs,
      routes: routes,
      settings: settings,
      skipped: skipped,
      exportedAt: DateTime.tryParse(root['exportedAt'] is String ? root['exportedAt'] as String : ''),
    );
  }
}
