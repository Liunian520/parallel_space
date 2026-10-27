import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../config/update_config.dart';
import 'native_bridge.dart';
import 'update_manifest.dart';

enum UpdateCheckResultKind { upToDate, available, skippedInsideWork, error }

class UpdateCheckResult {
  const UpdateCheckResult(this.kind, {this.manifest, this.message, this.currentCode, this.currentName});
  final UpdateCheckResultKind kind;
  final UpdateManifest? manifest;
  final String? message;
  final int? currentCode;
  final String? currentName;
}

/// 应用内更新：拉清单 → 比对 versionCode → 下载校验 → 唤起系统安装。
///
/// 清单与 APK 均优先走无盾直链；分享短链仅作浏览器回退。
class UpdateService {
  UpdateService(this._prefs);
  final SharedPreferences _prefs;

  static const _kAuto = 'upd.auto';
  static const _kLastCheck = 'upd.lastCheckMs';

  bool get autoCheck => _prefs.getBool(_kAuto) ?? true;
  set autoCheck(bool v) => _prefs.setBool(_kAuto, v);

  int get lastCheckMs => _prefs.getInt(_kLastCheck) ?? 0;

  bool get dueForAutoCheck {
    if (!autoCheck) return false;
    final elapsed = DateTime.now().millisecondsSinceEpoch - lastCheckMs;
    return elapsed >= kUpdateAutoCheckInterval.inMilliseconds;
  }

  Future<({int code, String name})> currentVersion() async {
    final info = await PackageInfo.fromPlatform();
    return (code: int.tryParse(info.buildNumber) ?? 0, name: info.version);
  }

  /// [force] 为 true 时忽略 12h 节流（设置页「检查更新」）。
  Future<UpdateCheckResult> check({bool force = false}) async {
    if (await NativeBridge.isInsideWorkProfile()) {
      return const UpdateCheckResult(UpdateCheckResultKind.skippedInsideWork, message: '请在主空间检查更新');
    }
    if (!force && !dueForAutoCheck) {
      return const UpdateCheckResult(UpdateCheckResultKind.upToDate, message: '跳过（未到自动检查间隔）');
    }
    final cur = await currentVersion();
    try {
      final raw = await _fetchManifestBody();
      final decoded = jsonDecode(raw);
      final m = UpdateManifest.tryParse(decoded);
      if (m == null) {
        return UpdateCheckResult(UpdateCheckResultKind.error,
            message: '清单无效或缺少必填字段', currentCode: cur.code, currentName: cur.name);
      }
      await _prefs.setInt(_kLastCheck, DateTime.now().millisecondsSinceEpoch);
      if (!m.isNewerThanApp(currentCode: cur.code, currentName: cur.name)) {
        return UpdateCheckResult(UpdateCheckResultKind.upToDate,
            manifest: m, currentCode: cur.code, currentName: cur.name, message: '已是最新版本');
      }
      return UpdateCheckResult(UpdateCheckResultKind.available,
          manifest: m, currentCode: cur.code, currentName: cur.name);
    } on TimeoutException {
      return UpdateCheckResult(UpdateCheckResultKind.error, message: '清单请求超时', currentCode: cur.code, currentName: cur.name);
    } on SocketException catch (e) {
      return UpdateCheckResult(UpdateCheckResultKind.error, message: '网络错误：${e.message}', currentCode: cur.code, currentName: cur.name);
    } on FormatException {
      return UpdateCheckResult(UpdateCheckResultKind.error, message: '清单不是合法 JSON', currentCode: cur.code, currentName: cur.name);
    } catch (e) {
      return UpdateCheckResult(UpdateCheckResultKind.error, message: '$e', currentCode: cur.code, currentName: cur.name);
    }
  }

  /// 先直连清单 URL（GitHub raw / 网盘 `/f/` 永久直链）；若落到 Link 盾 HTML，再尝试 WebView；仍失败则抛错（UI 可开浏览器回退）。
  Future<String> _fetchManifestBody() async {
    final primary = await _httpGetBody(kUpdateManifestUrl);
    if (primary != null) return primary;

    // 回退：分享短链（可能过盾）
    final viaWeb = await NativeBridge.fetchUpdateManifestViaWeb(kUpdateShareFallbackUrl);
    if (viaWeb != null && viaWeb.trim().isNotEmpty) {
      final t = viaWeb.trimLeft();
      if (t.startsWith('{') || t.startsWith('[')) return viaWeb;
      final extracted = _extractJsonObject(viaWeb);
      if (extracted != null) return extracted;
    }
    throw StateError('无法获取更新清单，请稍后重试或用浏览器打开下载页');
  }

  /// GET 并跟随重定向；成功返回 JSON 正文，盾页 / 非 2xx 返回 null。
  Future<String?> _httpGetBody(String url) async {
    try {
      final res = await http.get(Uri.parse(url)).timeout(kUpdateManifestTimeout);
      if (res.statusCode < 200 || res.statusCode >= 300) return null;
      final body = utf8.decode(res.bodyBytes);
      final trimmed = body.trimLeft();
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) return body;
      if (trimmed.toLowerCase().contains('<html') || trimmed.toLowerCase().contains('<!doctype')) {
        return null; // Link 盾等
      }
      return _extractJsonObject(body);
    } on TimeoutException {
      return null;
    } on SocketException {
      return null;
    } on HttpException {
      return null;
    }
  }

  static String? _extractJsonObject(String body) {
    final start = body.indexOf('{');
    final end = body.lastIndexOf('}');
    if (start >= 0 && end > start) return body.substring(start, end + 1);
    return null;
  }

  /// 打开分享页（清单短链或清单里的 apkShareUrl）。
  Future<String> openShareFallback({UpdateManifest? manifest}) {
    final url = manifest?.apkShareUrl?.trim();
    final target = (url != null && url.isNotEmpty) ? url : kUpdateShareFallbackUrl;
    return NativeBridge.openUrl(target);
  }

  /// 下载并校验；[onProgress] 收到 0..1。返回本地 APK 路径。跟随重定向（永久直链 → CDN）。
  Future<File> download(UpdateManifest m, {void Function(double p)? onProgress, http.Client? client}) async {
    final dir = Directory('${(await getTemporaryDirectory()).path}/updates');
    if (!await dir.exists()) await dir.create(recursive: true);
    final out = File('${dir.path}/update_${m.versionCode}.apk');
    if (await out.exists()) await out.delete();

    final c = client ?? http.Client();
    try {
      final req = http.Request('GET', Uri.parse(m.apkUrl));
      final streamed = await c.send(req).timeout(kUpdateDownloadTimeout);
      if (streamed.statusCode < 200 || streamed.statusCode >= 400) {
        throw HttpException('下载失败 HTTP ${streamed.statusCode}', uri: Uri.parse(m.apkUrl));
      }
      // 若误下到 HTML 盾页，content-type 常为 text/html 且体积远小于 APK
      final ctype = streamed.headers['content-type'] ?? '';
      if (ctype.toLowerCase().contains('text/html')) {
        throw StateError('直链返回了网页而非 APK，请用浏览器打开下载页');
      }
      final sink = out.openWrite();
      var got = 0;
      final total = streamed.contentLength ?? m.size;
      await for (final chunk in streamed.stream.timeout(kUpdateDownloadTimeout)) {
        got += chunk.length;
        sink.add(chunk);
        if (total > 0) onProgress?.call((got / total).clamp(0.0, 1.0));
      }
      await sink.flush();
      await sink.close();
      final len = await out.length();
      if (len != m.size) {
        await out.delete();
        throw StateError('大小不符：期望 ${m.size}，实际 $len');
      }
      final hex = (await sha256.bind(out.openRead()).first).toString();
      if (hex != m.sha256) {
        await out.delete();
        throw StateError('SHA-256 不符');
      }
      onProgress?.call(1);
      return out;
    } finally {
      if (client == null) c.close();
    }
  }

  Future<String> install(File apk) => NativeBridge.installApk(apk.path);
}
