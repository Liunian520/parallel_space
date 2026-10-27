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
    this.apkShareUrl,
  });

  final String versionName;
  final int versionCode;

  /// APK 永久直链（`https://link.liunianai.cn/f/...`），跟随 302 即可下载。
  final String apkUrl;

  /// 可选：分享短链，供浏览器回退（可能过 Link 盾）。
  final String? apkShareUrl;

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
    String? share;
    final su = m['apkShareUrl'];
    if (su is String && (su.startsWith('https://') || su.startsWith('http://'))) {
      share = su.trim();
    }
    return UpdateManifest(
      versionName: name.trim(),
      versionCode: vc,
      apkUrl: url.trim(),
      sha256: hex,
      size: sz,
      notes: notes.trim(),
      publishedAt: published,
      apkShareUrl: share,
    );
  }

  bool isNewerThan(int currentVersionCode) => versionCode > currentVersionCode;

  /// 是否比本机更新。
  ///
  /// 1. **优先 versionName**（1.3.x → 12300+patch）：Flutter `--split-per-abi` 会给
  ///    arm64 加上 2000（12311→14311），若只用 versionCode 会把「清单 12312」判成
  ///    不如本机，永远「已是最新」。
  /// 2. 再比清单 versionCode 与本机 buildNumber；并尝试剥掉 1000/2000/3000 的 ABI 偏移。
  bool isNewerThanApp({required int currentCode, required String currentName}) {
    final curName = _encodeName(currentName);
    final remoteName = _encodeName(versionName);
    // versionName 是主判据，可免疫 Flutter split-per-abi 的 +1000/+2000/+3000。
    if (curName != null && remoteName != null) {
      return remoteName > curName;
    }
    if (versionCode > currentCode) return true;
    // 名字解析失败时，尝试剥掉 ABI 偏移再比。
    for (final abi in const [1, 2, 3]) {
      final base = currentCode - abi * 1000;
      if (base >= 10000 && versionCode > base) return true;
    }
    return false;
  }

  /// 与 [encodeVersionCode] 一致：1.3.x 用 12300+patch。
  static int? _encodeName(String name) {
    final m = RegExp(r'^(\d+)\.(\d+)\.(\d+)').firstMatch(name.trim());
    if (m == null) return null;
    final major = int.parse(m[1]!);
    final minor = int.parse(m[2]!);
    final patch = int.parse(m[3]!);
    if (major == 1 && minor == 3) return 12300 + patch;
    return major * 10000 + minor * 100 + patch;
  }

  String get sizeLabel {
    if (size >= 1024 * 1024) return '${(size / (1024 * 1024)).toStringAsFixed(1)} MB';
    if (size >= 1024) return '${(size / 1024).toStringAsFixed(0)} KB';
    return '$size B';
  }
}
