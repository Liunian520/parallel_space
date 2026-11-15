import 'dart:convert';

/// 分身数据备份归档的清单编解码，以及恢复决策（与 Android 端 [CloneDataPolicy] 对齐）。
class CloneDataFormatException implements Exception {
  CloneDataFormatException(this.message, {this.code = 'invalid'});
  final String message;

  /// invalid | need_upgrade
  final String code;
  @override
  String toString() => message;
}

class CloneDataEntry {
  CloneDataEntry({
    required this.packageName,
    required this.userId,
    this.customLabel,
    this.appName = '',
    this.hasApk = false,
    this.exists = false,
    this.dirs = const [],
  });

  final String packageName;
  final int userId;
  final String? customLabel;
  final String appName;
  final bool hasApk;
  final bool exists;
  final List<String> dirs;

  String get displayName {
    final n = customLabel?.trim() ?? '';
    return n.isNotEmpty ? n : (appName.isNotEmpty ? appName : packageName);
  }

  Map<String, dynamic> toJson() => {
        'packageName': packageName,
        'userId': userId,
        'customLabel': customLabel ?? '',
        'appName': appName,
        'hasApk': hasApk,
        if (dirs.isNotEmpty) 'dirs': dirs,
      };

  static CloneDataEntry? fromJson(Object? raw, {bool exists = false}) {
    if (raw is! Map) return null;
    final pkg = raw['packageName'];
    if (pkg is! String || pkg.trim().isEmpty) return null;
    final userId = raw['userId'] is num ? (raw['userId'] as num).toInt() : 0;
    final label = raw['customLabel'] is String ? (raw['customLabel'] as String).trim() : '';
    final dirs = <String>[];
    final rawDirs = raw['dirs'];
    if (rawDirs is List) {
      for (final e in rawDirs) {
        if (e is String && e.isNotEmpty) dirs.add(e);
      }
    }
    return CloneDataEntry(
      packageName: pkg.trim(),
      userId: userId.clamp(0, 999),
      customLabel: label.isEmpty ? null : label,
      appName: raw['appName'] is String ? raw['appName'] as String : '',
      hasApk: raw['hasApk'] == true,
      exists: exists,
      dirs: dirs,
    );
  }
}

class CloneDataManifest {
  CloneDataManifest({
    required this.clones,
    this.exportedAt,
    this.appVersion,
    this.version = CloneDataCodec.version,
  });
  final List<CloneDataEntry> clones;
  final DateTime? exportedAt;
  final String? appVersion;
  final int version;
}

class CloneDataPlanItem {
  CloneDataPlanItem({
    required this.index,
    required this.packageName,
    required this.sourceUserId,
    required this.targetUserId,
    required this.action,
    required this.label,
    required this.needsApk,
    this.error,
  });
  final int index;
  final String packageName;
  final int sourceUserId;
  final int targetUserId;

  /// overwrite | create | error
  final String action;
  final String label;
  final bool needsApk;
  final String? error;
}

class CloneDataCodec {
  static const format = 'alter_ego.clone_data';
  static const version = 1;
  static const maxUsers = 64;
  static const modeOverwrite = 'overwrite';
  static const modeCreateNew = 'createNew';

  static String checkFormat(String? formatName, int versionNumber) {
    if (formatName != format) return 'invalid';
    if (versionNumber < 1) return 'invalid';
    if (versionNumber > version) return 'need_upgrade';
    return 'ok';
  }

  static String normalizePath(String? relative) {
    if (relative == null) return '';
    var n = relative.replaceAll('\\', '/').trim();
    while (n.startsWith('./')) {
      n = n.substring(2);
    }
    while (n.startsWith('/')) {
      n = n.substring(1);
    }
    while (n.endsWith('/') && n.length > 1) {
      n = n.substring(0, n.length - 1);
    }
    return n;
  }

  static bool isSafeZipEntry(String? name) {
    if (name == null || name.isEmpty) return false;
    final n = name.replaceAll('\\', '/');
    if (n.startsWith('/') || n.startsWith('../')) return false;
    for (final p in n.split('/')) {
      if (p == '..' || p == '.') return false;
    }
    return true;
  }

  static bool shouldSkipDataEntry(String? relativeToDataRoot) {
    final n = normalizePath(relativeToDataRoot);
    if (n.isEmpty) return false;
    final parts = n.split('/');
    if (parts.isNotEmpty && parts[0] == 'lib') return true;
    return parts.contains('code_cache');
  }

  static String? uniqueLabel(String? desired, Iterable<String> taken) {
    if (desired == null || desired.isEmpty) return desired;
    final used = taken.toSet();
    if (!used.contains(desired)) return desired;
    final first = '$desired 复制';
    if (!used.contains(first)) return first;
    var i = 2;
    while (used.contains('$desired 复制$i') && i <= 999) {
      i++;
    }
    return '$desired 复制$i';
  }

  static int nextFreeUser(String pkg, Set<String> usedKeys) {
    for (var i = 0; i < maxUsers; i++) {
      if (!usedKeys.contains('$pkg#$i')) return i;
    }
    return maxUsers;
  }

  static List<CloneDataPlanItem> planRestore({
    required List<CloneDataEntry> backup,
    required String mode,
    required Set<String> installedKeys,
    required Iterable<String> existingLabels,
  }) {
    final out = <CloneDataPlanItem>[];
    final used = {...installedKeys};
    final labels = existingLabels.toSet();
    final createNew = mode == modeCreateNew;
    for (var i = 0; i < backup.length; i++) {
      final b = backup[i];
      if (b.packageName.isEmpty) {
        out.add(CloneDataPlanItem(
          index: i,
          packageName: b.packageName,
          sourceUserId: b.userId,
          targetUserId: -1,
          action: 'error',
          label: '',
          needsApk: false,
          error: '缺少包名',
        ));
        continue;
      }
      final srcKey = '${b.packageName}#${b.userId}';
      final exists = used.contains(srcKey);
      late final String action;
      late final int target;
      if (!createNew && exists) {
        action = 'overwrite';
        target = b.userId;
      } else if (createNew && exists) {
        action = 'create';
        target = nextFreeUser(b.packageName, used);
      } else if (!used.contains(srcKey) && b.userId >= 0 && b.userId < maxUsers) {
        action = 'create';
        target = b.userId;
      } else {
        action = 'create';
        target = nextFreeUser(b.packageName, used);
      }
      if (target < 0 || target >= maxUsers) {
        out.add(CloneDataPlanItem(
          index: i,
          packageName: b.packageName,
          sourceUserId: b.userId,
          targetUserId: target,
          action: 'error',
          label: b.customLabel ?? '',
          needsApk: false,
          error: '分身数量已达上限',
        ));
        continue;
      }
      final needsApk = !used.contains('${b.packageName}#$target');
      used.add('${b.packageName}#$target');
      final desired = b.customLabel?.trim() ?? '';
      var label = desired;
      if (desired.isNotEmpty && action == 'create') {
        label = uniqueLabel(desired, labels) ?? desired;
      }
      if (label.isNotEmpty) labels.add(label);
      out.add(CloneDataPlanItem(
        index: i,
        packageName: b.packageName,
        sourceUserId: b.userId,
        targetUserId: target,
        action: action,
        label: label,
        needsApk: needsApk,
      ));
    }
    return out;
  }

  static String encode(CloneDataManifest m, {DateTime? now}) {
    return const JsonEncoder.withIndent('  ').convert({
      'format': format,
      'version': version,
      'exportedAt': (now ?? DateTime.now()).toUtc().toIso8601String(),
      'appVersion': m.appVersion,
      'clones': [for (final c in m.clones) c.toJson()],
    });
  }

  static CloneDataManifest decode(String text) {
    Object? root;
    try {
      root = jsonDecode(text);
    } on FormatException {
      throw CloneDataFormatException('文件不是有效的 JSON 清单');
    }
    if (root is! Map) throw CloneDataFormatException('备份格式不正确');
    final check = checkFormat(root['format'] is String ? root['format'] as String : null,
        root['version'] is int ? root['version'] as int : -1);
    if (check == 'need_upgrade') {
      throw CloneDataFormatException('这份备份来自更新版本的 Alter Ego，请先升级应用再恢复', code: 'need_upgrade');
    }
    if (check != 'ok') throw CloneDataFormatException('这不是有效的分身数据备份');
    final raw = root['clones'];
    if (raw is! List) throw CloneDataFormatException('备份清单里没有分身列表');
    final clones = <CloneDataEntry>[];
    for (final e in raw) {
      final c = CloneDataEntry.fromJson(e);
      if (c != null) clones.add(c);
    }
    return CloneDataManifest(
      clones: clones,
      exportedAt: DateTime.tryParse(root['exportedAt'] is String ? root['exportedAt'] as String : ''),
      appVersion: root['appVersion'] is String ? root['appVersion'] as String : null,
      version: root['version'] is int ? root['version'] as int : version,
    );
  }
}
