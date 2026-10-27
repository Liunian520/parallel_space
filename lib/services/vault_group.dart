/// 密码本条目。明文只在验证通过后出现在内存里，落盘由原生层加密。
class VaultEntry {
  const VaultEntry({
    required this.id,
    required this.packageName,
    required this.cloneUid,
    required this.label,
    required this.username,
    required this.password,
    this.note = '',
  });

  final String id;
  final String packageName;
  final String cloneUid;
  final String label;
  final String username;
  final String password;
  final String note;

  /// 有分身 uid 时按分身分组，否则按包名。
  String get groupKey => cloneUid.isNotEmpty ? cloneUid : packageName;

  static VaultEntry? fromMap(Object? raw) {
    if (raw is! Map) return null;
    final id = raw['id'];
    final pkg = raw['pkg'];
    if (id is! String || pkg is! String || id.isEmpty || pkg.isEmpty) return null;
    final label = raw['label'];
    return VaultEntry(
      id: id,
      packageName: pkg,
      cloneUid: raw['clone'] is String ? raw['clone'] as String : '',
      label: label is String && label.trim().isNotEmpty ? label.trim() : '未命名',
      username: raw['user'] is String ? raw['user'] as String : '',
      password: raw['pass'] is String ? raw['pass'] as String : '',
      note: raw['note'] is String ? raw['note'] as String : '',
    );
  }
}

class VaultGroup {
  const VaultGroup({required this.key, required this.title, required this.entries, required this.current});

  final String key;
  final String title;
  final List<VaultEntry> entries;
  final bool current;
}

/// 当前分身排在最前，其余按分组键排序。组内按名称排序。
List<VaultGroup> groupVaultEntries(
  List<VaultEntry> entries, {
  String? currentPackage,
  Map<String, String> titles = const {},
}) {
  final map = <String, List<VaultEntry>>{};
  for (final e in entries) {
    map.putIfAbsent(e.groupKey, () => []).add(e);
  }
  final keys = map.keys.toList()
    ..sort((a, b) {
      final ac = _isCurrent(map[a]!, currentPackage);
      final bc = _isCurrent(map[b]!, currentPackage);
      if (ac != bc) return ac ? -1 : 1;
      return a.compareTo(b);
    });
  return [
    for (final k in keys)
      VaultGroup(
        key: k,
        title: titles[k] ?? map[k]!.first.packageName,
        current: _isCurrent(map[k]!, currentPackage),
        entries: [...map[k]!]..sort((a, b) => a.label.compareTo(b.label)),
      ),
  ];
}

bool _isCurrent(List<VaultEntry> entries, String? pkg) =>
    pkg != null && pkg.isNotEmpty && entries.any((e) => e.packageName == pkg);
