/// 应用内更新配置。
///
/// 清单多源拉取（国内 GitHub raw 常被缓存或拦截）：
/// 1. GitHub raw（带 `?t=` 防缓存）
/// 2. jsDelivr CDN 镜像
/// 3. ghproxy 代理
/// 4. 网盘永久直链 `/f/...`（发版脚本每次上传后回写本文件的 [kUpdateManifestDiskUrl]）
/// 5. 分享短链 WebView 过盾（最后兜底）
///
/// APK：网盘永久直链入口 `https://link.liunianai.cn/f/{objectKey}`
/// （服务端 302 现签短 TTL CDN；上传脚本写入清单，App 不含任何网盘密钥）
library;

/// 更新清单（GitHub raw，跟随 main；上传脚本每次发版覆盖同名文件）。
const String kUpdateManifestUrl =
    'https://raw.githubusercontent.com/Liunian520/parallel_space/main/update_manifest.txt';

/// jsDelivr 对 GitHub 的镜像（国内通常比 raw 更稳）。
const String kUpdateManifestJsDelivrUrl =
    'https://cdn.jsdelivr.net/gh/Liunian520/parallel_space@main/update_manifest.txt';

/// ghproxy 反代 raw（国内备用）。
const String kUpdateManifestGhProxyUrl =
    'https://ghproxy.net/https://raw.githubusercontent.com/Liunian520/parallel_space/main/update_manifest.txt';

/// 网盘清单永久直链（每次发版由 upload_apk.py 回写为最新 manifestPublicUrl）。
/// 注意：上一版硬编码的地址在旧清单被删后会 404，属预期；其它源仍可用。
const String kUpdateManifestDiskUrl =
    'https://link.liunianai.cn/f/disk/8afolhkaqv3ejhcbmniehy7mik/parallel_space_manifest.txt';

/// 清单 / 发版页的分享短链（可能过盾；检查失败或用户选择时用浏览器打开）。
const String kUpdateShareFallbackUrl = 'https://link.liunianai.cn/n8afhu';

/// HTTP 直连候选（不含 WebView）。顺序即优先级。
List<String> get kUpdateManifestHttpCandidates => [
      kUpdateManifestUrl,
      kUpdateManifestJsDelivrUrl,
      kUpdateManifestGhProxyUrl,
      kUpdateManifestDiskUrl,
    ];

/// 给清单 URL 加上毫秒时间戳，绕过中间缓存。
String cacheBustUrl(String url, {int? epochMs}) {
  final t = epochMs ?? DateTime.now().millisecondsSinceEpoch;
  return url.contains('?') ? '$url&t=$t' : '$url?t=$t';
}

/// 自动检查最小间隔。
const Duration kUpdateAutoCheckInterval = Duration(hours: 12);

/// 下载超时（整包）。
const Duration kUpdateDownloadTimeout = Duration(minutes: 15);

/// Manifest 直连超时（单源）。
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
