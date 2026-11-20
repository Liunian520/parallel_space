/// 分身通知的解析和展示。不读系统，也不保存标题正文。
class CloneNoteParsed {
  const CloneNoteParsed({
    required this.pkg,
    required this.userId,
    required this.appId,
    required this.appTag,
    required this.uid,
  });

  final String pkg;
  final int userId;
  final int appId;
  final String appTag;
  final String uid;
}

class CloneNoteActive {
  const CloneNoteActive({
    required this.tag,
    required this.id,
    required this.postTime,
    required this.title,
    required this.text,
    required this.groupSummary,
  });

  final String tag;
  final int id;
  final int postTime;
  final String title;
  final String text;
  final bool groupSummary;
}

class CloneNoteKnown {
  const CloneNoteKnown({required this.pkg, required this.userId, required this.name});

  final String pkg;
  final int userId;
  final String name;
}

class CloneNoteRow {
  const CloneNoteRow({
    required this.systemTag,
    required this.systemId,
    required this.pkg,
    required this.userId,
    required this.uid,
    required this.name,
    required this.title,
    required this.body,
    required this.groupSummary,
    required this.canOpen,
    required this.postTime,
  });

  final String systemTag;
  final int systemId;
  final String pkg;
  final int userId;
  final String uid;
  final String name;
  final String title;
  final String body;
  final bool groupSummary;
  final bool canOpen;
  final int postTime;
}

const cloneNoteOrphan = '当前没有这个分身';
const cloneNoteNoTitle = '这条通知没有标题';
const _sep = '\u001f';

CloneNoteParsed? parseCloneNotificationTag(String? tag) {
  if (tag == null || !tag.startsWith('ae1$_sep')) return null;
  final parts = tag.split(_sep);
  if (parts.length < 5 || parts[0] != 'ae1') return null;
  // split 不限次数时，应用 tag 里的分隔符要拼回去，和 Java split(limit=5) 一致。
  final appTag = parts.sublist(4).join(_sep);
  if (!RegExp(r'^[0-9]+$').hasMatch(parts[1])) return null;
  final userId = int.tryParse(parts[1]);
  final appId = int.tryParse(parts[3]);
  if (userId == null || userId < 0 || userId > 2147483647 || userId.toString() != parts[1]) return null;
  if (appId == null || appId < -2147483648 || appId > 2147483647 || parts[2].isEmpty) return null;
  final uid = userId <= 0 ? 'w:${parts[2]}' : 'w:${parts[2]}#$userId';
  return CloneNoteParsed(pkg: parts[2], userId: userId, appId: appId, appTag: appTag, uid: uid);
}

List<CloneNoteRow> presentCloneNotifications(List<CloneNoteActive> active, List<CloneNoteKnown> clones) {
  final newest = <String, CloneNoteActive>{};
  for (final item in active) {
    if (parseCloneNotificationTag(item.tag) == null) continue;
    final key = '${item.tag}\u0000${item.id}';
    final prev = newest[key];
    if (prev == null || item.postTime >= prev.postTime) newest[key] = item;
  }
  final rows = <CloneNoteRow>[];
  for (final item in newest.values) {
    final parsed = parseCloneNotificationTag(item.tag);
    if (parsed == null) continue;
    CloneNoteKnown? known;
    for (final clone in clones) {
      if (clone.pkg == parsed.pkg && clone.userId == parsed.userId) {
        known = clone;
        break;
      }
    }
    final open = known != null;
    final name = open ? (known.name.trim().isEmpty ? parsed.pkg : known.name.trim()) : cloneNoteOrphan;
    var title = item.title.trim();
    final body = item.text.trim();
    if (title.isEmpty && body.isEmpty) title = cloneNoteNoTitle;
    rows.add(CloneNoteRow(
      systemTag: item.tag,
      systemId: item.id,
      pkg: parsed.pkg,
      userId: parsed.userId,
      uid: parsed.uid,
      name: name,
      title: title,
      body: body,
      groupSummary: item.groupSummary,
      canOpen: open,
      postTime: item.postTime,
    ));
  }
  rows.sort((a, b) {
    final byTime = b.postTime.compareTo(a.postTime);
    if (byTime != 0) return byTime;
    final byTag = a.systemTag.compareTo(b.systemTag);
    if (byTag != 0) return byTag;
    return a.systemId.compareTo(b.systemId);
  });
  return rows;
}

/// 点击当下再核对。名单里没有同一包名和 userId 时不打开。
CloneNoteParsed? shouldLaunchCloneNotification(String tag, List<CloneNoteKnown> known) {
  final parsed = parseCloneNotificationTag(tag);
  if (parsed == null) return null;
  for (final clone in known) {
    if (clone.pkg == parsed.pkg && clone.userId == parsed.userId) return parsed;
  }
  return null;
}

class RefreshGate {
  int _current = 0;

  int begin() => ++_current;

  bool accept(int token) => token == _current;
}
