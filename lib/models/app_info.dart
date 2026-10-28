/// Android ApplicationInfo.category 的分类。
enum AppCategory { social, game, audio, video, image, news, maps, productivity, accessibility, other }

extension AppCategoryX on AppCategory {
  String get label => const {
        AppCategory.social: '社交通讯',
        AppCategory.game: '游戏',
        AppCategory.audio: '音乐音频',
        AppCategory.video: '视频',
        AppCategory.image: '图片摄影',
        AppCategory.news: '新闻阅读',
        AppCategory.maps: '地图导航',
        AppCategory.productivity: '效率办公',
        AppCategory.accessibility: '无障碍',
        AppCategory.other: '其他',
      }[this]!;

  static AppCategory parse(Object? v) =>
      AppCategory.values.where((c) => c.name == v).firstOrNull ?? AppCategory.other;
}

/// 设备上真实安装的应用（由原生层返回）。
class AppInfo {
  const AppInfo({
    required this.packageName,
    required this.name,
    this.version = '',
    this.category = AppCategory.other,
    this.system = false,
    this.suspended = false,
    this.inWork = false,
    this.userId = 0,
  });

  final String packageName;
  final String name;
  final String version;
  final AppCategory category;
  final bool system;

  /// 在工作资料中被系统暂停（冻结）。
  final bool suspended;

  /// 个人空间应用：是否已存在于平行空间的工作资料中。
  final bool inWork;

  /// 虚拟引擎用户 id：同一应用的第 N 个分身（0 = 第一个）。
  final int userId;

  static AppInfo? fromMap(Object? e) {
    if (e is! Map || e['pkg'] is! String || (e['pkg'] as String).isEmpty) return null;
    final pkg = e['pkg'] as String;
    final label = e['label'] is String && (e['label'] as String).trim().isNotEmpty ? e['label'] as String : pkg;
    return AppInfo(
      packageName: pkg,
      name: label,
      version: e['ver'] is String ? e['ver'] as String : '',
      category: AppCategoryX.parse(e['cat']),
      system: e['system'] == true,
      suspended: e['suspended'] == true,
      inWork: e['inWork'] == true,
      userId: e['userId'] is num ? (e['userId'] as num).toInt().clamp(0, 999) : 0,
    );
  }

  Map<String, Object?> toMap() => {
        'pkg': packageName,
        'label': name,
        'ver': version,
        'cat': category.name,
        'system': system,
        'suspended': suspended,
        'inWork': inWork,
      };
}
