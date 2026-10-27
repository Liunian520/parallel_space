import 'dart:async';

import 'package:flutter/foundation.dart';

import 'native_bridge.dart';

/// 真实应用图标缓存：
/// - 原生层把 Drawable（含 AdaptiveIconDrawable）渲染为 PNG 并缓存到磁盘；
/// - 这里按 (空间, 包名) 缓存在内存，同一帧内的请求合并成一次批量调用。
class AppIcons {
  AppIcons._();

  static const size = 144;
  static final Map<String, Future<Uint8List?>> _cache = {};
  static final Map<String, Completer<Uint8List?>> _pendingWork = {};
  static final Map<String, Completer<Uint8List?>> _pendingPersonal = {};
  static bool _scheduled = false;

  static String _key(String pkg, bool work) => '${work ? 'w' : 'p'}:$pkg';

  /// 已加载完成的图标（同步读取，用于避免首帧闪烁）。
  static final Map<String, Uint8List?> _ready = {};
  static Uint8List? peek(String pkg, {required bool work}) => _ready[_key(pkg, work)];
  static bool isLoaded(String pkg, {required bool work}) => _ready.containsKey(_key(pkg, work));

  static Future<Uint8List?> get(String pkg, {required bool work}) {
    final k = _key(pkg, work);
    return _cache.putIfAbsent(k, () {
      final c = Completer<Uint8List?>();
      (work ? _pendingWork : _pendingPersonal)[pkg] = c;
      if (!_scheduled) {
        _scheduled = true;
        scheduleMicrotask(_flush);
      }
      return c.future.then((v) {
        _ready[k] = v;
        return v;
      });
    });
  }

  static Future<void> _flush() async {
    _scheduled = false;
    await Future.wait([_load(_pendingWork, true), _load(_pendingPersonal, false)]);
  }

  static Future<void> _load(Map<String, Completer<Uint8List?>> pending, bool work) async {
    if (pending.isEmpty) return;
    final batch = Map.of(pending);
    pending.clear();
    Map<String, Uint8List> r = const {};
    try {
      r = await NativeBridge.icons(batch.keys.toList(), work: work, size: size);
    } catch (_) {}
    for (final e in batch.entries) {
      e.value.complete(r[e.key]);
    }
    // 失败（例如工作资料暂停）不永久缓存，下次重新请求
    for (final k in batch.keys) {
      if (r[k] == null) _cache.remove(_key(k, work));
    }
  }

  /// 应用更新 / 工作资料状态变化后清空。
  static void clear() {
    _cache.clear();
    _ready.clear();
  }
}
