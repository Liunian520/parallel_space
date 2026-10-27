import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 全局设置（持久化）。读取时容忍类型错误（旧版本 / 损坏数据），不让启动崩溃。
class SettingsStore extends ChangeNotifier {
  SettingsStore(this._prefs) {
    T? get<T>(String k) {
      final v = _prefs.get(k);
      return v is T ? v : null;
    }

    spaceTitle = get<String>('s.title') ?? spaceTitle;
    final ti = get<int>('s.theme') ?? 0;
    themeMode = ThemeMode.values[ti.clamp(0, ThemeMode.values.length - 1)];
    useDynamicColor = get<bool>('s.dyn') ?? useDynamicColor;
    final seedV = get<int>('s.seed');
    if (seedV != null) seed = Color(seedV);
    reduceEffects = get<bool>('s.fx') ?? reduceEffects;
    autoCheckUpdate = get<bool>('s.updAuto') ?? autoCheckUpdate;
    toolboxOverlay = get<bool>('s.toolbox') ?? toolboxOverlay;
    // 1.1.x 的模拟通知 / 免打扰设置已移除
    for (final k in const ['s.nt', 's.badge', 's.silent', 's.dnd', 's.dndS', 's.dndE']) {
      _prefs.remove(k);
    }
  }

  final SharedPreferences _prefs;
  String spaceTitle = '平行空间';
  ThemeMode themeMode = ThemeMode.system;
  bool useDynamicColor = true;
  Color seed = seedOptions.first;

  /// 精简特效（默认开启）：不使用实时模糊、背景静止，适合中低端设备与省电。
  bool reduceEffects = true;

  /// 启动时自动检查更新（最多 12 小时一次）。
  bool autoCheckUpdate = true;

  /// 分身在前台时显示工具箱悬浮窗。默认关闭，避免没授权就拉起前台服务。
  bool toolboxOverlay = false;

  static const seedOptions = <Color>[
    Color(0xFF2F7CF6), // 蓝
    Color(0xFF00BCD4), // 青
    Color(0xFF7C4DFF), // 紫
    Color(0xFF00C853), // 绿
    Color(0xFFFF6D00), // 橙
    Color(0xFFE91E63), // 粉
  ];

  void _persist() {
    _prefs
      ..setString('s.title', spaceTitle)
      ..setInt('s.theme', themeMode.index)
      ..setBool('s.dyn', useDynamicColor)
      ..setInt('s.seed', seed.toARGB32())
      ..setBool('s.fx', reduceEffects)
      ..setBool('s.updAuto', autoCheckUpdate)
      ..setBool('s.toolbox', toolboxOverlay);
  }

  void set(void Function(SettingsStore s) fn) {
    fn(this);
    _persist();
    notifyListeners();
  }

  /// 备份（键与 BackupCodec.settingKeys 对应，不含任何 PIN 信息）。
  Map<String, dynamic> toBackup() => {
        'title': spaceTitle,
        'theme': themeMode.index,
        'dyn': useDynamicColor,
        'seed': seed.toARGB32(),
        'fx': reduceEffects,
        'updAuto': autoCheckUpdate,
        'toolbox': toolboxOverlay,
      };

  void applyBackup(Map<String, dynamic> m) => set((s) {
        if (m['title'] is String && (m['title'] as String).trim().isNotEmpty) {
          final t = (m['title'] as String).trim();
          s.spaceTitle = t.length > 10 ? t.substring(0, 10) : t;
        }
        if (m['theme'] is int) s.themeMode = ThemeMode.values[(m['theme'] as int).clamp(0, ThemeMode.values.length - 1)];
        if (m['dyn'] is bool) s.useDynamicColor = m['dyn'] as bool;
        if (m['seed'] is int) s.seed = Color(m['seed'] as int);
        if (m['fx'] is bool) s.reduceEffects = m['fx'] as bool;
        if (m['updAuto'] is bool) s.autoCheckUpdate = m['updAuto'] as bool;
        if (m['toolbox'] is bool) s.toolboxOverlay = m['toolbox'] as bool;
      });
}
