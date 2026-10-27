/// 应用内更新配置。
library;

/// 更新清单短链（Link 盾：直连可能返回挑战 HTML，需 WebView 跟随到最终 .txt/.json）。
const String kUpdateManifestUrl = 'https://link.liunianai.cn/n8afhu';

/// 自动检查最小间隔。
const Duration kUpdateAutoCheckInterval = Duration(hours: 12);

/// 下载超时（整包）。
const Duration kUpdateDownloadTimeout = Duration(minutes: 15);

/// Manifest 直连超时。
const Duration kUpdateManifestTimeout = Duration(seconds: 20);

/// WebView 过盾最长等待。
const Duration kUpdateManifestWebTimeout = Duration(seconds: 45);

/// 将「1.2.4」编码为清单用 versionCode：1*10000+2*100+4 → 10204。
int encodeVersionCode(int major, int minor, int patch) => major * 10000 + minor * 100 + patch;

/// 解析 versionName（如 1.3.0 / 2.0.0）为可比整数；失败返回 null。
int? versionCodeFromName(String name) {
  final m = RegExp(r'^(\d+)\.(\d+)\.(\d+)').firstMatch(name.trim());
  if (m == null) return null;
  return encodeVersionCode(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!));
}
