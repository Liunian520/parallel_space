/// 远端更新清单。
class UpdateManifest {
  const UpdateManifest({
    required this.versionName,
    required this.versionCode,
    required this.apkUrl,
    required this.sha256,
    required this.size,
    required this.notes,
    this.publishedAt,
  });

  final String versionName;
  final int versionCode;
  final String apkUrl;
  final String sha256;
  final int size;
  final String notes;
  final DateTime? publishedAt;

  /// 宽容解析；缺必填字段返回 null。
  static UpdateManifest? tryParse(Object? raw) {
    if (raw is! Map) return null;
    final m = <String, dynamic>{for (final e in raw.entries) '${e.key}': e.value};
    final name = m['versionName'];
    final code = m['versionCode'];
    final url = m['apkUrl'];
    final sha = m['sha256'];
    final size = m['size'];
    if (name is! String || name.trim().isEmpty) return null;
    final vc = switch (code) {
      int i => i,
      num n => n.toInt(),
      String s => int.tryParse(s.trim()),
      _ => null,
    };
    if (vc == null || vc <= 0) return null;
    if (url is! String || !(url.startsWith('https://') || url.startsWith('http://'))) return null;
    if (sha is! String) return null;
    final hex = sha.trim().toLowerCase().replaceAll(RegExp(r'[^0-9a-f]'), '');
    if (hex.length != 64) return null;
    final sz = switch (size) {
      int i => i,
      num n => n.toInt(),
      String s => int.tryParse(s.trim()),
      _ => null,
    };
    if (sz == null || sz <= 0) return null;
    final notes = m['notes'] is String ? (m['notes'] as String) : '';
    DateTime? published;
    final pa = m['publishedAt'];
    if (pa is String && pa.trim().isNotEmpty) published = DateTime.tryParse(pa.trim());
    return UpdateManifest(
      versionName: name.trim(),
      versionCode: vc,
      apkUrl: url.trim(),
      sha256: hex,
      size: sz,
      notes: notes.trim(),
      publishedAt: published,
    );
  }

  bool isNewerThan(int currentVersionCode) => versionCode > currentVersionCode;

  /// 优先比 versionCode；若本机 buildNumber 不是 1xxyy 编码，再用 versionName 编码比较。
  bool isNewerThanApp({required int currentCode, required String currentName}) {
    if (currentCode >= 10000) return versionCode > currentCode;
    final encoded = _fromName(currentName);
    if (encoded != null) return versionCode > encoded;
    return versionCode > currentCode;
  }

  static int? _fromName(String name) {
    final m = RegExp(r'^(\d+)\.(\d+)\.(\d+)').firstMatch(name.trim());
    if (m == null) return null;
    return int.parse(m[1]!) * 10000 + int.parse(m[2]!) * 100 + int.parse(m[3]!);
  }

  String get sizeLabel {
    if (size >= 1024 * 1024) return '${(size / (1024 * 1024)).toStringAsFixed(1)} MB';
    if (size >= 1024) return '${(size / 1024).toStringAsFixed(0)} KB';
    return '$size B';
  }
}
