/// 应用内更新配置。
///
/// 清单与 APK 都走「可直连、无 Link 盾」的地址：
/// - 清单：仓库内固定的 `update_manifest.txt`（GitHub raw，版本无关、永久有效）
/// - APK：网盘永久直链入口 `https://link.liunianai.cn/f/{objectKey}`
///   （服务端 302 现签短 TTL CDN；上传脚本写入清单，App 不含任何网盘密钥）
/// 分享短链仍可能被 Link 盾 / 地区拦截，仅作浏览器回退。
library;

/// 更新清单（GitHub raw，跟随 main；上传脚本每次发版覆盖同名文件）。
const String kUpdateManifestUrl =
    'https://raw.githubusercontent.com/Liunian520/parallel_space/main/update_manifest.txt';

/// 清单 / 发版页的分享短链（可能过盾；检查失败或用户选择时用浏览器打开）。
const String kUpdateShareFallbackUrl = 'https://link.liunianai.cn/n8afhu';

/// 自动检查最小间隔。
const Duration kUpdateAutoCheckInterval = Duration(hours: 12);

/// 下载超时（整包）。
const Duration kUpdateDownloadTimeout = Duration(minutes: 15);

/// Manifest 直连超时。
const Duration kUpdateManifestTimeout = Duration(seconds: 20);

/// WebView 过盾最长等待。
const Duration kUpdateManifestWebTimeout = Duration(seconds: 45);

/// 编码 versionCode。
///
/// 1.3.x 使用 `12300 + patch`（1.3.1→12301…1.3.7→12307），避开曾误用的 10303–10305 降号。
/// 其他主次版本仍用 major×10000 + minor×100 + patch。
int encodeVersionCode(int major, int minor, int patch) {
  if (major == 1 && minor == 3) return 12300 + patch;
  return major * 10000 + minor * 100 + patch;
}

/// 解析 versionName（如 1.3.0 / 2.0.0）为可比整数；失败返回 null。
int? versionCodeFromName(String name) {
  final m = RegExp(r'^(\d+)\.(\d+)\.(\d+)').firstMatch(name.trim());
  if (m == null) return null;
  return encodeVersionCode(int.parse(m[1]!), int.parse(m[2]!), int.parse(m[3]!));
}
