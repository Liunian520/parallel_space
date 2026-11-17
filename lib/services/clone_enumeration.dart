import '../models/app_info.dart';

/// 一次虚拟分身列举。只有 [authoritative] 为真时，缺席的分身才可以删。
class CloneEnumeration {
  const CloneEnumeration({required this.authoritative, required this.apps, this.error});

  final bool authoritative;
  final List<AppInfo> apps;
  final String? error;

  /// 通道失败、引擎未就绪、某一 user 列举失败、或没有 complete 标记，都不是权威结果。
  /// 权威的空列表（complete 且没有 error）表示引擎确认一台都没装。
  static CloneEnumeration parse(Object? raw) {
    if (raw == null) {
      return const CloneEnumeration(authoritative: false, apps: [], error: 'channel');
    }
    if (raw is List) {
      return CloneEnumeration(authoritative: false, apps: _apps(raw), error: 'missing_complete');
    }
    if (raw is! Map) {
      return const CloneEnumeration(authoritative: false, apps: [], error: 'bad_type');
    }
    final err = raw['error'];
    final error = err is String && err.isNotEmpty ? err : null;
    final apps = _apps(raw['apps']);
    final authoritative = raw['complete'] == true && error == null;
    return CloneEnumeration(authoritative: authoritative, apps: apps, error: error);
  }

  static List<AppInfo> _apps(Object? raw) {
    if (raw is! List) return const [];
    return [for (final e in raw) if (AppInfo.fromMap(e) case final AppInfo a) a];
  }
}
