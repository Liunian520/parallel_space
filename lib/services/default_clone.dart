import 'dart:math';

import '../models/clone_app.dart';

/// 一个分身在「默认启动」决策里的样子。真正的身份是 [launchKey]，不是数字 userId。
class CloneLaunchRef {
  const CloneLaunchRef({
    required this.uid,
    required this.packageName,
    required this.userId,
    required this.launchKey,
    required this.label,
    this.available = true,
  });

  final String uid;
  final String packageName;
  final int userId;

  /// 用户把这一份设为默认时生成。空字符串表示还没设过。
  final String launchKey;
  final String label;
  final bool available;
}

/// 某个包的默认分身。只保存 launchKey，不把数字 userId 当依据。
class DefaultCloneChoice {
  const DefaultCloneChoice({required this.packageName, required this.launchKey});

  final String packageName;
  final String launchKey;

  Map<String, String> toJson() => {'pkg': packageName, 'lk': launchKey};

  /// 只有合法 launchKey 才接受。仅有 userId 的旧条目或损坏条目返回 null。
  static DefaultCloneChoice? tryParse(Object? raw) {
    if (raw is! Map) return null;
    final pkg = raw['pkg'];
    final lk = raw['lk'];
    if (pkg is! String || pkg.trim().isEmpty) return null;
    if (lk is! String || !isLaunchKey(lk)) return null;
    return DefaultCloneChoice(packageName: pkg.trim(), launchKey: lk);
  }
}

bool isLaunchKey(String value) => CloneApp.isLaunchKey(value);

String newLaunchKey([Random? random]) {
  final r = random ?? Random.secure();
  return List<int>.generate(16, (_) => r.nextInt(256)).map((b) => b.toRadixString(16).padLeft(2, '0')).join();
}

class GenericLaunchDecision {
  const GenericLaunchDecision({
    required this.kind,
    this.uid,
    this.userId,
    this.pruned = false,
    required this.askPackages,
  });

  /// launch | chooser | absent
  final String kind;
  final String? uid;
  final int? userId;

  /// 这次发现记下的默认已经对不上任何可用分身，调用方应删掉这条偏好。
  final bool pruned;

  /// 这些包在用户明确再选一次之前，不要自动打开剩下的分身。
  final Set<String> askPackages;
}

class ExplicitLaunchDecision {
  const ExplicitLaunchDecision({required this.kind, this.uid, this.userId});

  /// launch | chooser | absent
  final String kind;
  final String? uid;
  final int? userId;
}

class DataRestoreDefaultEffect {
  const DataRestoreDefaultEffect({
    required this.remainingKey,
    required this.ask,
    required this.stripTargetKey,
    required this.retargeted,
  });

  /// 仍指向原来的 launchKey；对不上时为 null。不会改成目标 userId 的另一份。
  final String? remainingKey;

  /// 默认已经失效，接下来的未指定启动要先问用户。
  final bool ask;

  /// 「新建」出来的那一份如果带上了原来的 launchKey，要清掉。
  final bool stripTargetKey;

  /// 始终为 false：恢复映射不把默认改到新的数字 id。
  final bool retargeted;
}

/// 未指定具体分身时该打开谁。
///
/// 没有默认、且这个包只有一份分身：打开那一份（和现在点开唯一分身一样）。
/// 没有默认、有多份：询问，不挑 userId 0，也不挑最近打开的。
/// 默认还在：打开那一份。
/// 默认被删、不可用、或 userId 已被另一份复用：删掉偏好，只要还剩任意一份就询问，不自动改开。
GenericLaunchDecision resolveGenericLaunch({
  required String packageName,
  required List<CloneLaunchRef> clones,
  DefaultCloneChoice? choice,
  Set<String> askPackages = const {},
}) {
  final ask = {...askPackages};
  final available = clones.where((c) => c.packageName == packageName && c.available).toList();
  final forPkg = choice != null && choice.packageName == packageName && isLaunchKey(choice.launchKey);
  CloneLaunchRef? matched;
  if (forPkg) {
    final hits = available.where((c) => c.launchKey == choice.launchKey).toList();
    if (hits.length == 1) matched = hits.single;
  }
  if (matched != null) {
    ask.remove(packageName);
    return GenericLaunchDecision(
      kind: 'launch',
      uid: matched.uid,
      userId: matched.userId,
      askPackages: ask,
    );
  }
  if (forPkg) ask.add(packageName);
  if (available.isEmpty) {
    return GenericLaunchDecision(kind: 'absent', pruned: forPkg, askPackages: ask);
  }
  if (forPkg || ask.contains(packageName)) {
    return GenericLaunchDecision(kind: 'chooser', pruned: forPkg, askPackages: ask);
  }
  if (available.length == 1) {
    final only = available.single;
    return GenericLaunchDecision(kind: 'launch', uid: only.uid, userId: only.userId, askPackages: ask);
  }
  return GenericLaunchDecision(kind: 'chooser', askPackages: ask);
}

/// 点名打开：快捷方式、小组件、通知和卡片上的「打开」都走这里。
///
/// [choice] 和 [askPackages] 故意不参与结果。请求的那一份不在了就返回 absent，
/// 不会改去打开默认分身。userId 0 也是明确请求（旧快捷方式），不是「没指定」。
ExplicitLaunchDecision resolveExplicitLaunch({
  required String packageName,
  String? requestedUid,
  int? requestedUserId,
  required List<CloneLaunchRef> clones,
  DefaultCloneChoice? choice,
  Set<String> askPackages = const {},
}) {
  final named = (requestedUid != null && requestedUid.isNotEmpty) || requestedUserId != null;
  if (!named) {
    final generic = resolveGenericLaunch(
      packageName: packageName,
      clones: clones,
      choice: choice,
      askPackages: askPackages,
    );
    return ExplicitLaunchDecision(kind: generic.kind, uid: generic.uid, userId: generic.userId);
  }
  final available = clones.where((c) => c.packageName == packageName && c.available);
  CloneLaunchRef? hit;
  if (requestedUid != null && requestedUid.isNotEmpty) {
    for (final c in available) {
      if (c.uid == requestedUid) {
        hit = c;
        break;
      }
    }
  } else if (requestedUserId != null) {
    for (final c in available) {
      if (c.userId == requestedUserId) {
        hit = c;
        break;
      }
    }
  }
  if (hit == null) return const ExplicitLaunchDecision(kind: 'absent');
  return ExplicitLaunchDecision(kind: 'launch', uid: hit.uid, userId: hit.userId);
}

/// 配置备份里的默认，只在本机已有同一 launchKey 时保留。数字 userId 对上也不够。
List<DefaultCloneChoice> acceptBackupDefaults({
  required List<DefaultCloneChoice> incoming,
  required List<CloneLaunchRef> localClones,
}) {
  final out = <DefaultCloneChoice>[];
  final seen = <String>{};
  for (final choice in incoming) {
    if (!isLaunchKey(choice.launchKey)) continue;
    final hits = localClones
        .where((c) => c.packageName == choice.packageName && c.available && c.launchKey == choice.launchKey)
        .toList();
    if (hits.length != 1 || !seen.add(choice.packageName)) continue;
    out.add(choice);
  }
  return out;
}

/// 分身数据恢复之后怎么处理默认。
///
/// 覆盖仍是原来的那一份（launchKey 还在）就保持。新建的 userId 即使等于备份里的数字，
/// 也不把默认搬过去。新建的那一份如果复制了 launchKey，清掉，避免两份共用。
DataRestoreDefaultEffect effectOfDataRestore({
  required String? currentKey,
  required String packageName,
  required int sourceUserId,
  required int targetUserId,
  required String action,
  required List<CloneLaunchRef> clones,
}) {
  final mine = clones.where((c) => c.packageName == packageName && c.available).toList();
  final source = mine.where((c) => c.userId == sourceUserId).toList();
  final target = mine.where((c) => c.userId == targetUserId).toList();
  final creatingNew = action == 'create' && targetUserId != sourceUserId && target.length == 1;
  var strip = false;
  if (creatingNew) {
    final created = target.single;
    final sourceKey = source.length == 1 ? source.single.launchKey : currentKey;
    final copied = created.launchKey.isNotEmpty &&
        (created.launchKey == currentKey || (sourceKey != null && sourceKey.isNotEmpty && created.launchKey == sourceKey));
    if (copied) strip = true;
  }
  bool counts(CloneLaunchRef c) {
    if (strip && c.uid == target.single.uid) return false;
    return currentKey != null && c.launchKey == currentKey;
  }

  if (currentKey == null || !isLaunchKey(currentKey)) {
    return DataRestoreDefaultEffect(remainingKey: null, ask: false, stripTargetKey: strip, retargeted: false);
  }
  final hits = mine.where(counts).toList();
  if (hits.length == 1) {
    return DataRestoreDefaultEffect(remainingKey: currentKey, ask: false, stripTargetKey: strip, retargeted: false);
  }
  return DataRestoreDefaultEffect(remainingKey: null, ask: true, stripTargetKey: strip, retargeted: false);
}
