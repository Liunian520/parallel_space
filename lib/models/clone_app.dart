import 'app_info.dart';

/// 一个分身实例：虚拟引擎里某个用户（userId）下的应用。同一应用可有多个实例，数据互相独立。
class CloneApp {
  CloneApp({
    required this.uid,
    required this.packageName,
    required this.appName,
    this.userId = 0,
    this.version = '',
    this.category = AppCategory.other,
    this.customLabel,
    this.fingerprintLock = false,
    this.passwordLock = false,
    this.mockEnabled = false,
    this.mockName = '北京天安门',
    this.mockLat = 39.908722,
    this.mockLng = 116.397499,
    this.isPrivate = false,
    this.frozen = false,
    this.autoFreezeMin = 0,
    this.userAdded = false,
    int? lastActiveMs,
  }) : lastActiveMs = lastActiveMs ?? DateTime.now().millisecondsSinceEpoch;

  factory CloneApp.fromApp(AppInfo a) => CloneApp(
        uid: uidFor(a.packageName, a.userId),
        packageName: a.packageName,
        appName: a.name,
        userId: a.userId,
        version: a.version,
        category: a.category,
        frozen: a.suspended,
        userAdded: true,
      );

  /// 实例 id：第一个分身沿用旧格式 `w:包名`（老数据无需迁移），其余为 `w:包名#userId`。
  static String uidFor(String pkg, [int userId = 0]) => userId <= 0 ? 'w:$pkg' : 'w:$pkg#$userId';

  /// 从实例 id 解析 userId（无 # 后缀 = 0）。
  static int userIdOf(String id) {
    final i = id.lastIndexOf('#');
    if (i < 0) return 0;
    return (int.tryParse(id.substring(i + 1)) ?? 0).clamp(0, 999);
  }

  /// 从实例 id 解析包名。
  static String pkgOf(String id) {
    var body = id.startsWith('w:') ? id.substring(2) : id;
    final i = body.lastIndexOf('#');
    if (i >= 0) body = body.substring(0, i);
    return body;
  }

  final String uid;
  final String packageName;

  /// 虚拟引擎用户 id（0 = 该应用的第一个分身）。
  final int userId;
  String appName;
  String version;
  AppCategory category;

  /// 自定义名称（为空则使用应用名）。
  String? customLabel;

  bool fingerprintLock;
  bool passwordLock;

  // 虚拟定位
  bool mockEnabled;
  String mockName;
  double mockLat;
  double mockLng;

  // 私密空间
  bool isPrivate;

  // 省电：冻结（系统暂停应用）/ 自动冻结（0 = 关闭）
  bool frozen;
  int autoFreezeMin;
  int lastActiveMs;

  /// 仅「添加分身」写入为 true；同步绝不能把系统预装变成用户分身。
  bool userAdded;

  /// 默认名：第一个分身为应用名，其余为「应用名 分身N」（N = userId + 1）。
  String get defaultName => userId <= 0 ? appName : '$appName 分身${userId + 1}';

  /// 显示名（桌面快捷方式、最近任务、工具箱都用它）。
  String get customName => (customLabel?.trim().isNotEmpty ?? false) ? customLabel!.trim() : defaultName;
  bool get locked => fingerprintLock || passwordLock;

  Map<String, dynamic> toJson() => {
        'uid': uid,
        'pkg': packageName,
        'kind': 'work',
        if (userId > 0) 'u': userId,
        'appName': appName,
        'appVer': version,
        'cat': category.name,
        if (customLabel != null) 'name': customLabel,
        'fp': fingerprintLock,
        'pw': passwordLock,
        'mock': mockEnabled,
        'mockName': mockName,
        'lat': mockLat,
        'lng': mockLng,
        'priv': isPrivate,
        'frozen': frozen,
        'af': autoFreezeMin,
        'last': lastActiveMs,
        'ua': userAdded,
      };

  /// 宽容解析。没有包名的旧版“模拟分身”返回 null（迁移时丢弃）。
  static CloneApp? fromJson(Map<String, dynamic> j) {
    T? v<T>(String k) => j[k] is T ? j[k] as T : null;
    double? d(String k) => j[k] is num ? (j[k] as num).toDouble() : null;
    int? i(String k) => j[k] is num ? (j[k] as num).toInt() : null;

    final pkg = v<String>('pkg');
    if (pkg == null || pkg.trim().isEmpty) return null;
    final uid = v<String>('uid');
    final userId = (i('u') ?? (uid == null ? 0 : userIdOf(uid))).clamp(0, 999);
    final lat = d('lat');
    final lng = d('lng');
    final validLoc = lat != null && lng != null && lat.abs() <= 90 && lng.abs() <= 180;
    final name = v<String>('name')?.trim();
    final appName = v<String>('appName')?.trim();
    return CloneApp(
      uid: (uid == null || uid.isEmpty) ? uidFor(pkg, userId) : uid,
      packageName: pkg,
      userId: userId,
      appName: (appName == null || appName.isEmpty) ? pkg : appName,
      version: v<String>('appVer') ?? '',
      category: AppCategoryX.parse(j['cat']),
      // 旧版本把“应用名”也存进 name；与应用名相同则视为未自定义
      customLabel: (name == null || name.isEmpty || name == appName) ? null : name,
      fingerprintLock: v<bool>('fp') ?? false,
      passwordLock: v<bool>('pw') ?? false,
      mockEnabled: v<bool>('mock') ?? false,
      mockName: v<String>('mockName') ?? '自定义位置',
      mockLat: validLoc ? lat : 39.908722,
      mockLng: validLoc ? lng : 116.397499,
      isPrivate: v<bool>('priv') ?? false,
      frozen: v<bool>('frozen') ?? false,
      autoFreezeMin: (i('af') ?? 0).clamp(0, 24 * 60),
      lastActiveMs: i('last'),
      // 缺省 ua：旧数据视为未标记；加载后由 CloneStore 再按预装名单清洗
      userAdded: v<bool>('ua') ?? false,
    );
  }
}
