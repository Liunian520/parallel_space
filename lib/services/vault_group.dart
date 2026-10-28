/// 密码本条目。与原生 VaultStore 共用本机明文存储（filesDir/vault_plain.json）。
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

/// [cur] 可以是包名（旧）或实例 id（w:包名 / w:包名#N）。
bool _isCurrent(List<VaultEntry> entries, String? cur) {
  if (cur == null || cur.isEmpty) return false;
  if (!cur.startsWith('w:')) return entries.any((e) => e.packageName == cur);
  return entries.any((e) => vaultEntryMatchesInstance(e, cur));
}

/// 条目是否属于这个分身实例。旧条目（无实例 id 或只有包名）算作该应用的第一个分身。
bool vaultEntryMatchesInstance(VaultEntry e, String uid) {
  final hash = uid.lastIndexOf('#');
  final body = uid.substring(2);
  final pkg = hash < 0 ? body : uid.substring(2, hash);
  final first = hash < 0 || uid.endsWith('#0');
  if (e.cloneUid.isNotEmpty) return e.cloneUid == uid || (first && (e.cloneUid == pkg || e.cloneUid == 'w:$pkg'));
  return first && e.packageName == pkg;
}

/// 按关键字过滤（备注名 / 账号 / 包名 / 备注 / 分身 uid）。
List<VaultEntry> filterVaultEntries(List<VaultEntry> entries, String query) {
  final q = query.trim().toLowerCase();
  if (q.isEmpty) return entries;
  return [
    for (final e in entries)
      if (e.label.toLowerCase().contains(q) ||
          e.username.toLowerCase().contains(q) ||
          e.packageName.toLowerCase().contains(q) ||
          e.note.toLowerCase().contains(q) ||
          e.cloneUid.toLowerCase().contains(q))
        e,
  ];
}

/// 合并多源条目：同 id 时后者覆盖前者。对应原生 VaultStore.mergeById。
/// 用于验证「prefs + 文件」合并不会丢 1.3.11 独立进程写过的账号。
List<Map<String, String>> mergeVaultSources(List<List<Map<String, String>>> sources) {
  final byId = <String, Map<String, String>>{};
  for (final src in sources) {
    for (final e in src) {
      final id = e['id'];
      final pkg = e['pkg'];
      if (id == null || id.isEmpty || pkg == null || pkg.isEmpty) continue;
      byId[id] = Map<String, String>.from(e);
    }
  }
  return byId.values.toList();
}
