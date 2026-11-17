import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/app_info.dart';
import '../models/clone_app.dart';
import '../services/backup_codec.dart';
import '../services/default_clone.dart';
import '../services/native_bridge.dart';

/// 未指定具体分身时的决定。点名打开不走这里。
class PackageLaunchDecision {
  const PackageLaunchDecision(this.kind, {this.clone, this.stale = false});

  /// launch | chooser | absent
  final String kind;
  final CloneApp? clone;

  /// 记下的默认已经没了，界面要说明不会自动改开另一份。
  final bool stale;
}

/// 真实分身状态：分身 = Alter Ego 工作资料中可启动的应用；这里额外保存每个分身的设置（锁、定位、私密、冻结…）。
///
/// 数据仍存于 `clones.v1`（原生快捷方式跳板也读取它）。旧版本的“模拟分身”（没有包名）在加载时丢弃。
class CloneStore extends ChangeNotifier {
  CloneStore(this._prefs) {
    final raw = _prefs.getString(_key);
    if (raw != null) _loadFrom(raw);
    mockingUid = _prefs.getString('$_key.mocking');
    if (mockingUid != null && byId(mockingUid!) == null) {
      mockingUid = null;
      _prefs.remove('$_key.mocking');
    }
    // 每次启动都清洗：预装 / 非 userAdded 不得出现在列表（删资料重建也不该带回来）
    if (_scrubPresets()) _migrated = true;
    _prefs.setBool('$_key.userOnly', true);
    _prefs.setInt('$_key.listPolicy', 3); // 1.2.3
    _loadDefaultPrefs();
    final defaultsChanged = _pruneDefaults();
    if (_migrated) {
      _save();
    } else if (defaultsChanged) {
      _persistDefaultPrefs();
    }
  }

  static const _key = 'clones.v1';
  static const _defaultsKey = 'clone.defaults.v1';
  static const _askKey = 'clone.defaults.ask';

  /// 工作资料常见预装，不应出现在「我的分身」里（用户可通过添加分身显式加入非商店类应用）。
  static const _presetWorkPackages = {
    'com.android.vending',
    'com.xiaomi.market',
    'com.xiaomi.mipicks',
    'com.android.contacts',
    'com.google.android.contacts',
    'com.miui.contacts',
    'com.android.dialer',
    'com.google.android.dialer',
    'com.android.phone',
    'com.android.server.telecom',
    'com.android.documentsui',
    'com.google.android.apps.nbu.files',
    'com.android.fileexplorer',
    'com.mi.android.globalFileexplorer',
    'com.xiaomi.fileexplorer',
    'com.android.filemanager',
    'com.mi.android.globalminusscreen', // 不是文件，保险
    'com.android.providers.downloads.ui',
    'com.android.settings',
    'com.google.android.gms',
    'com.google.android.packageinstaller',
    'com.google.android.permissioncontroller',
    'com.android.chrome',
    'com.google.android.apps.maps',
    'com.google.android.gm',
    'com.google.android.calendar',
    'com.google.android.apps.photos',
    'com.google.android.youtube',
    'com.android.mms',
    'com.google.android.apps.messaging',
    'com.miui.gallery',
    'com.miui.notes',
    'com.miui.player',
    'com.miui.video',
    'com.miui.weather2',
    'com.android.camera',
    'com.android.camera2',
    'com.android.gallery3d',
    'com.android.music',
  };

  static const _presetLabels = {'文件', '电话', '联系人', 'Play 商店', 'Play商店', '应用商店', '信息', '短信', '图库', '相机', '设置', '下载', '下载内容'};

  static bool isPresetPackage(String pkg, {String? label}) {
    final p = pkg.toLowerCase();
    if (_presetWorkPackages.contains(pkg)) return true;
    if (p.contains('documentsui') || p.contains('fileexplorer') || p.contains('filemanager') || p.contains('file.explorer')) return true;
    if (p.contains('contacts') || p.contains('dialer') || p.contains('vending') || p.endsWith('.mms')) return true;
    if (label != null && _presetLabels.contains(label.trim())) return true;
    return false;
  }

  /// 去掉预装；旧数据无 ua 标记的非预装包视为用户添加。返回是否有变更。
  bool _scrubPresets() {
    var changed = false;
    for (final c in _clones) {
      if (!c.userAdded &&
          !isPresetPackage(c.packageName, label: c.appName) &&
          !isPresetPackage(c.packageName, label: c.customName)) {
        c.userAdded = true;
        changed = true;
      }
    }
    final before = _clones.length;
    _clones.removeWhere((c) =>
        isPresetPackage(c.packageName, label: c.appName) ||
        isPresetPackage(c.packageName, label: c.customName) ||
        !c.userAdded);
    return changed || _clones.length != before;
  }
  final SharedPreferences _prefs;
  final List<CloneApp> _clones = [];
  final Map<String, String> _defaults = {};
  final Set<String> _ask = {};
  bool _migrated = false;

  /// 持久化后回调（用于同步桌面小组件）。
  VoidCallback? onPersisted;

  /// 当前正在系统级模拟定位的分身（同一时间仅一个）。
  String? mockingUid;

  /// 工作资料状态（遗留；主路径已改为虚拟引擎）。
  WorkProfileStatus status = const WorkProfileStatus();
  WorkState get workState => status.state;

  /// NewBlackbox 虚拟引擎是否可用。
  bool engineAvailable = false;
  String? engineError;
  String engineName = '';

  /// 分身可用：虚拟引擎就绪（不再要求工作资料）。
  bool get ready => engineAvailable || status.state == WorkState.ready;
  bool _refreshing = false;
  bool get refreshing => _refreshing;

  void _loadFrom(String raw) {
    try {
      final list = jsonDecode(raw);
      if (list is! List) {
        _migrated = true;
        return;
      }
      final seen = <String>{};
      for (final e in list) {
        if (e is! Map<String, dynamic>) {
          _migrated = true;
          continue;
        }
        CloneApp? c;
        try {
          c = CloneApp.fromJson(e);
        } catch (_) {}
        // 模拟分身 / 重复包名：丢弃
        // 同一实例（包名 + userId）只保留一份；同包名不同 userId 是多开，都保留
        if (c == null || !seen.add('${c.packageName}#${c.userId}')) {
          _migrated = true;
          continue;
        }
        if (e['kind'] != 'work' || e.containsKey('emoji') || e.containsKey('index')) _migrated = true;
        _clones.add(c);
      }
    } catch (_) {
      // 数据损坏：保留原文以便排查，不让 App 启动崩溃。
      _prefs.setString('$_key.corrupt', raw);
      _migrated = true;
    }
  }

  List<CloneApp> get clones => List.unmodifiable(_clones);

  /// 主屏可见（不在私密空间中）。
  List<CloneApp> get visibleClones => _clones.where((c) => !c.isPrivate).toList(growable: false);
  List<CloneApp> get privateClones => _clones.where((c) => c.isPrivate).toList(growable: false);
  bool get isEmpty => visibleClones.isEmpty;

  CloneApp? byId(String uid) => _clones.where((c) => c.uid == uid).firstOrNull;
  /// 该应用的第一个分身（userId 最小）。
  CloneApp? byPackage(String pkg) {
    final l = instancesOf(pkg);
    return l.isEmpty ? null : l.first;
  }

  /// 同一应用的所有分身实例，按 userId 升序。
  List<CloneApp> instancesOf(String pkg) =>
      _clones.where((c) => c.packageName == pkg).toList()..sort((a, b) => a.userId.compareTo(b.userId));

  CloneApp? byInstance(String pkg, int userId) =>
      _clones.where((c) => c.packageName == pkg && c.userId == userId).firstOrNull;

  /// 快捷方式 / 小组件传入的 id：uid、'w:包名' 或 'w:包名#userId'。
  CloneApp? resolve(String id) {
    final hit = byId(id);
    if (hit != null) return hit;
    if (!id.startsWith('w:')) return null;
    return byInstance(CloneApp.pkgOf(id), CloneApp.userIdOf(id));
  }

  /// 原生侧（快捷方式名、最近任务名）需要的显示名映射。
  List<Map<String, Object?>> nameItems() => [
        for (final c in _clones) {'pkg': c.packageName, 'userId': c.userId, 'uid': c.uid, 'label': c.customName},
      ];

  /// 名称同步回调（默认推给原生；测试可替换）。
  void Function(List<Map<String, Object?>> items)? onNames;

  void _save() {
    _pruneDefaults();
    _prefs.setString(_key, jsonEncode(_clones.map((c) => c.toJson()).toList()));
    _persistDefaultPrefs();
    onPersisted?.call();
    final cb = onNames;
    if (cb != null) {
      try {
        cb(nameItems());
      } catch (_) {}
    }
  }

  void _loadDefaultPrefs() {
    final raw = _prefs.getString(_defaultsKey);
    if (raw != null) {
      try {
        final decoded = jsonDecode(raw);
        if (decoded is Map) {
          for (final e in decoded.entries) {
            final key = e.key;
            final value = e.value;
            if (key is String && value is String && isLaunchKey(value)) _defaults[key] = value;
          }
        }
      } catch (_) {}
    }
    final askRaw = _prefs.getString(_askKey);
    if (askRaw == null) return;
    try {
      final decoded = jsonDecode(askRaw);
      if (decoded is List) {
        for (final e in decoded) {
          if (e is String && e.isNotEmpty) _ask.add(e);
        }
      }
    } catch (_) {}
  }

  /// 对不上 launchKey 的默认删掉，并记下来：下次未指定启动要先问，不能改开剩下的那一份。
  bool _pruneDefaults() {
    final beforeDefaults = _defaults.length;
    final beforeAsk = _ask.length;
    final stale = <String>[];
    _defaults.removeWhere((pkg, key) {
      final hits = _clones.where((c) => c.packageName == pkg && c.launchKey == key).length;
      if (hits == 1) return false;
      stale.add(pkg);
      return true;
    });
    _ask.addAll(stale);
    return _defaults.length != beforeDefaults || _ask.length != beforeAsk;
  }

  void _persistDefaultPrefs() {
    if (_defaults.isEmpty) {
      _prefs.remove(_defaultsKey);
    } else {
      _prefs.setString(_defaultsKey, jsonEncode(_defaults));
    }
    if (_ask.isEmpty) {
      _prefs.remove(_askKey);
    } else {
      _prefs.setString(_askKey, jsonEncode(_ask.toList()));
    }
  }

  List<CloneLaunchRef> _launchRefs() => [
        for (final c in _clones)
          CloneLaunchRef(
            uid: c.uid,
            packageName: c.packageName,
            userId: c.userId,
            launchKey: c.launchKey ?? '',
            label: c.customName,
          ),
      ];

  DefaultCloneChoice? _choiceFor(String pkg) {
    final key = _defaults[pkg];
    if (key == null || !isLaunchKey(key)) return null;
    return DefaultCloneChoice(packageName: pkg, launchKey: key);
  }

  /// 这个包当前的默认分身。对不上标记时返回 null，不按 userId 凑。
  CloneApp? defaultClone(String pkg) {
    final key = _defaults[pkg];
    if (key == null) return null;
    final hits = _clones.where((c) => c.packageName == pkg && c.launchKey == key).toList();
    return hits.length == 1 ? hits.single : null;
  }

  bool isDefaultClone(CloneApp c) => defaultClone(c.packageName)?.uid == c.uid;

  /// 默认分身现在的自定义名。改名后这里跟着变，不另存一份会过期的名字。
  String? defaultLabel(String pkg) => defaultClone(pkg)?.customName;

  List<DefaultCloneChoice> exportLaunchDefaults() => [
        for (final e in _defaults.entries)
          if (isLaunchKey(e.value)) DefaultCloneChoice(packageName: e.key, launchKey: e.value),
      ];

  void setDefaultClone(CloneApp c, {String? launchKey}) {
    if (!_clones.contains(c)) return;
    final existing = c.launchKey;
    final key = existing != null && isLaunchKey(existing)
        ? existing
        : (launchKey != null && isLaunchKey(launchKey) ? launchKey : newLaunchKey());
    c.launchKey = key;
    _defaults[c.packageName] = key;
    _ask.remove(c.packageName);
    _changed();
  }

  /// 用户明确取消默认。不留下「必须再问一次」的标记，唯一的那一份仍可直接打开。
  void clearDefault(String pkg) {
    _defaults.remove(pkg);
    _ask.remove(pkg);
    _persistDefaultPrefs();
    notifyListeners();
  }

  /// 用户在选择器里点了一份，但没有要求记住。之后不再因为旧默认失效而拦住唯一的分身。
  void acknowledgePackageChoice(String pkg) {
    if (!_ask.remove(pkg)) return;
    _persistDefaultPrefs();
    notifyListeners();
  }

  PackageLaunchDecision resolvePackageLaunch(String pkg) {
    final hadAsk = _ask.contains(pkg);
    final decision = resolveGenericLaunch(
      packageName: pkg,
      clones: _launchRefs(),
      choice: _choiceFor(pkg),
      askPackages: _ask,
    );
    _ask
      ..clear()
      ..addAll(decision.askPackages);
    if (decision.pruned) _defaults.remove(pkg);
    _persistDefaultPrefs();
    notifyListeners();
    final stale = decision.pruned || hadAsk;
    if (decision.kind == 'launch') {
      final c = decision.uid == null ? null : byId(decision.uid!);
      if (c != null) return PackageLaunchDecision('launch', clone: c);
    }
    if (decision.kind == 'absent' || instancesOf(pkg).isEmpty) {
      return PackageLaunchDecision('absent', stale: stale);
    }
    return PackageLaunchDecision('chooser', stale: stale);
  }

  /// 分身数据恢复给出了 source → target。不按数字 userId 把默认搬到新分身。
  void noteCloneDataRestore({
    required String packageName,
    required int sourceUserId,
    required int targetUserId,
    required String action,
  }) {
    final effect = effectOfDataRestore(
      currentKey: _defaults[packageName],
      packageName: packageName,
      sourceUserId: sourceUserId,
      targetUserId: targetUserId,
      action: action,
      clones: _launchRefs(),
    );
    var stripped = false;
    if (effect.stripTargetKey && targetUserId != sourceUserId) {
      final created = byInstance(packageName, targetUserId);
      final source = byInstance(packageName, sourceUserId);
      if (created != null && !identical(created, source)) {
        created.launchKey = null;
        stripped = true;
      }
    }
    if (effect.remainingKey == null) {
      if (_defaults.containsKey(packageName)) _defaults.remove(packageName);
      if (effect.ask) _ask.add(packageName);
    } else {
      _defaults[packageName] = effect.remainingKey!;
      _ask.remove(packageName);
    }
    if (stripped) {
      _changed();
    } else {
      _persistDefaultPrefs();
      notifyListeners();
    }
  }

  /// 合并时只补上对得上的默认；覆盖时先清空，再只恢复 launchKey 仍在本机的那些。
  void importLaunchDefaults(List<DefaultCloneChoice> incoming, {required bool replace}) {
    if (replace) {
      _defaults.clear();
      _ask.clear();
    }
    for (final choice in acceptBackupDefaults(incoming: incoming, localClones: _launchRefs())) {
      _defaults[choice.packageName] = choice.launchKey;
      _ask.remove(choice.packageName);
    }
    _persistDefaultPrefs();
    notifyListeners();
  }

  void _changed() {
    _save();
    notifyListeners();
  }

  /// 从系统读取工作资料状态与其中的应用，并同步分身列表。
  bool _relinkTried = false;

  Future<void> refresh() async {
    if (_refreshing) return;
    _refreshing = true;
    try {
      final vs = await NativeBridge.virtualStatus();
      _applyVirtual(vs);

      if (engineAvailable) {
        final listed = await NativeBridge.listVirtualClones();
        // 不完整或失败的列举不能当成「这些分身已经没了」。
        if (listed.authoritative) sync(listed.apps);
        // 仍探测工作资料状态（设置页展示），但不依赖它
        status = (await NativeBridge.workSnapshot()).status;
      } else {
        final snap = await NativeBridge.workSnapshot();
        status = snap.status;
        if (snap.status.state == WorkState.ready) {
          if (!snap.status.linked && snap.status.canInteract && !_relinkTried) {
            _relinkTried = true;
            unawaited(NativeBridge.repairWorkLink());
          }
          sync(snap.apps);
        } else {
          notifyListeners();
        }
      }
    } catch (e) {
      engineAvailable = false;
      engineError = 'refresh: $e';
      notifyListeners();
    } finally {
      _refreshing = false;
    }
  }

  /// 再次初始化虚拟引擎，然后重新读取状态。给「重新初始化 / 重新检测」用。
  Future<void> retryEngine() async {
    try {
      _applyVirtual(await NativeBridge.retryVirtualEngine());
    } catch (e) {
      engineAvailable = false;
      engineError = 'retry: $e';
      notifyListeners();
    }
    await refresh();
  }

  void _applyVirtual(Map<String, dynamic> vs) {
    engineAvailable = vs['available'] == true;
    engineError = vs['error'] is String ? vs['error'] as String : null;
    if (vs['engine'] is String && (vs['engine'] as String).isNotEmpty) {
      engineName = vs['engine'] as String;
    }
  }

  /// 同步：只维护用户通过「添加分身」加入的条目。
  /// - 更新仍在工作资料中的分身的名称/版本/冻结状态
  /// - 移除已不在工作资料中的分身
  /// - 绝不把系统预装（联系人 / Play 商店 / 文件 / 电话等）自动加进列表
  @visibleForTesting
  void sync(List<AppInfo> apps) {
    String k(String pkg, int u) => '$pkg#$u';
    final byPkg = {for (final a in apps) k(a.packageName, a.userId): a};
    var changed = _scrubPresets();
    final before = _clones.length;
    // 不在工作资料中的用户分身 → 移除；预装即使还在工作资料也绝不保留
    _clones.removeWhere((c) => !c.userAdded || isPresetPackage(c.packageName, label: c.appName) || !byPkg.containsKey(k(c.packageName, c.userId)));
    if (_clones.length != before) changed = true;
    if (mockingUid != null && byId(mockingUid!) == null) {
      mockingUid = null;
      _prefs.remove('$_key.mocking');
    }
    for (final c in _clones) {
      final a = byPkg[k(c.packageName, c.userId)];
      if (a == null) continue;
      if (c.appName != a.name || c.version != a.version || c.category != a.category || c.frozen != a.suspended) {
        c
          ..appName = a.name
          ..version = a.version
          ..category = a.category
          ..frozen = a.suspended;
        changed = true;
      }
    }
    if (changed) {
      _changed();
    } else {
      notifyListeners();
    }
  }

  /// 测试 / 预览用：直接设置状态。
  @visibleForTesting
  void debugSetStatus(WorkProfileStatus s) {
    status = s;
    notifyListeners();
  }

  /// 系统中新增了应用（复制成功后，不必等待完整刷新）。
  /// 恢复分身数据后写入主屏列表，并套上原来的备注名。
  void adoptRestored({
    required String packageName,
    required int userId,
    required String appName,
    String? customLabel,
    String version = '',
    bool? deviceIdIsolated,
  }) {
    if (isPresetPackage(packageName, label: appName)) return;
    var c = byInstance(packageName, userId);
    if (c == null) {
      c = CloneApp(
        uid: CloneApp.uidFor(packageName, userId),
        packageName: packageName,
        appName: appName.isEmpty ? packageName : appName,
        userId: userId,
        version: version,
        customLabel: customLabel,
        userAdded: true,
        deviceIdIsolated: deviceIdIsolated ?? false,
      );
      _clones.add(c);
    } else {
      if (appName.isNotEmpty) c.appName = appName;
      if (customLabel != null && customLabel.trim().isNotEmpty) c.customLabel = customLabel.trim();
      if (deviceIdIsolated != null) c.deviceIdIsolated = deviceIdIsolated;
      c.userAdded = true;
    }
    _changed();
  }

  void addFromApp(AppInfo a) {
    if (isPresetPackage(a.packageName, label: a.name)) return;
    if (byInstance(a.packageName, a.userId) != null) return;
    _clones.add(CloneApp.fromApp(a)..userAdded = true);
    _changed();
  }

  void remove(String uid) {
    _clones.removeWhere((c) => c.uid == uid);
    if (mockingUid == uid) setMocking(null);
    _changed();
  }

  /// 按 uid 排序（主屏只显示非私密分身，不能用可见列表的下标直接操作完整列表）。
  void reorderByUid(String fromUid, String toUid) {
    if (fromUid == toUid) return;
    final from = _clones.indexWhere((c) => c.uid == fromUid);
    final to = _clones.indexWhere((c) => c.uid == toUid);
    if (from == -1 || to == -1) return;
    final item = _clones.removeAt(from);
    _clones.insert(to, item);
    _changed();
  }

  void update(CloneApp c, void Function(CloneApp c) fn) {
    if (!_clones.contains(c)) return; // 已被删除的分身（例如异步回调晚到）
    fn(c);
    _changed();
  }

  void setMocking(String? uid) {
    mockingUid = uid;
    if (uid == null) {
      _prefs.remove('$_key.mocking');
    } else {
      _prefs.setString('$_key.mocking', uid);
    }
    notifyListeners();
  }

  /// 打开分身：记录活跃时间。
  void markOpened(CloneApp c, {DateTime? now}) => update(c, (c) {
        c.lastActiveMs = (now ?? DateTime.now()).millisecondsSinceEpoch;
        c.frozen = false;
      });

  /// 已开启自动冻结且闲置超时、尚未冻结的分身。
  List<CloneApp> dueForAutoFreeze(DateTime now) => _clones
      .where((c) =>
          c.autoFreezeMin > 0 && !c.frozen && now.millisecondsSinceEpoch - c.lastActiveMs >= c.autoFreezeMin * 60000)
      .toList();

  /// 导入备份：把备份中的分身设置应用到本机工作资料中已存在的同包名应用。
  /// replace=true 时先把所有分身设置恢复默认。备份中本机工作资料里没有的应用计入 missing。
  ({int applied, int missing}) importClones(List<CloneApp> incoming, {required bool replace}) {
    if (replace) {
      for (var i = 0; i < _clones.length; i++) {
        final c = _clones[i];
        _clones[i] = CloneApp(
          uid: c.uid,
          packageName: c.packageName,
          appName: c.appName,
          userId: c.userId,
          version: c.version,
          category: c.category,
          frozen: c.frozen,
          // launchKey 是这一份分身的身份，不是可被覆盖重置的开关。
          launchKey: c.launchKey,
        );
      }
      setMocking(null);
    }
    var applied = 0, missing = 0;
    for (final b in incoming) {
      final c = byInstance(b.packageName, b.userId) ?? (b.userId == 0 ? byPackage(b.packageName) : null);
      if (c == null) {
        missing++;
        continue;
      }
      // 不把备份里的 launchKey 写到本机分身上。只按 userId 对上的可能是后来新建的另一份。
      c
        ..customLabel = b.customLabel
        ..fingerprintLock = b.fingerprintLock
        ..passwordLock = b.passwordLock
        ..mockEnabled = b.mockEnabled
        ..mockName = b.mockName
        ..mockLat = b.mockLat
        ..mockLng = b.mockLng
        ..isPrivate = b.isPrivate
        ..autoFreezeMin = b.autoFreezeMin;
      applied++;
    }
    _changed();
    return (applied: applied, missing: missing);
  }

  BackupData backupPart() => BackupData(
        clones: clones,
        favorites: const [],
        routes: const [],
        settings: const {},
        defaults: exportLaunchDefaults(),
      );

  /// 桌面小组件数据：主屏前 4 个（私密分身不会出现在桌面）。
  List<Map<String, Object?>> widgetItems() => [
        for (final c in visibleClones.take(4)) {'id': c.uid, 'pkg': c.packageName, 'label': c.customName},
      ];
}
