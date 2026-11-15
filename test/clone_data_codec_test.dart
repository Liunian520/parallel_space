import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/services/clone_data_codec.dart';

void main() {
  group('CloneDataCodec format', () {
    test('当前版本通过，更高版本要求升级', () {
      expect(CloneDataCodec.checkFormat('alter_ego.clone_data', 1), 'ok');
      expect(CloneDataCodec.checkFormat('alter_ego.clone_data', 99), 'need_upgrade');
      expect(CloneDataCodec.checkFormat('parallel_space.backup', 3), 'invalid');
      expect(CloneDataCodec.checkFormat(null, 1), 'invalid');
    });

    test('编解码清单，更高版本抛 need_upgrade', () {
      final json = CloneDataCodec.encode(
        CloneDataManifest(clones: [
          CloneDataEntry(packageName: 'com.t.app', userId: 1, customLabel: '工作', appName: 'App', hasApk: true),
        ], appVersion: '1.3.33'),
        now: DateTime.utc(2026, 11, 15, 12),
      );
      final m = CloneDataCodec.decode(json);
      expect(m.clones, hasLength(1));
      expect(m.clones.first.packageName, 'com.t.app');
      expect(m.clones.first.userId, 1);
      expect(m.clones.first.customLabel, '工作');
      expect(m.clones.first.hasApk, isTrue);
      expect(m.appVersion, '1.3.33');

      expect(
        () => CloneDataCodec.decode('{"format":"alter_ego.clone_data","version":99,"clones":[]}'),
        throwsA(isA<CloneDataFormatException>().having((e) => e.code, 'code', 'need_upgrade')),
      );
      expect(
        () => CloneDataCodec.decode('{"format":"parallel_space.backup","version":3}'),
        throwsA(isA<CloneDataFormatException>().having((e) => e.code, 'code', 'invalid')),
      );
    });
  });

  group('路径与跳过规则', () {
    test('拒绝 zip 穿越', () {
      expect(CloneDataCodec.isSafeZipEntry('clones/0/data/files/a.db'), isTrue);
      expect(CloneDataCodec.isSafeZipEntry('../etc/passwd'), isFalse);
      expect(CloneDataCodec.isSafeZipEntry('clones/0/data/../../blackbox/system'), isFalse);
      expect(CloneDataCodec.isSafeZipEntry('/data/user/0/x'), isFalse);
      expect(CloneDataCodec.normalizePath(r'files\a.db'), 'files/a.db');
    });

    test('跳过 code_cache 和顶层 lib，保留会话目录', () {
      expect(CloneDataCodec.shouldSkipDataEntry('code_cache/x'), isTrue);
      expect(CloneDataCodec.shouldSkipDataEntry('lib/arm64/a.so'), isTrue);
      expect(CloneDataCodec.shouldSkipDataEntry('cache'), isFalse);
      expect(CloneDataCodec.shouldSkipDataEntry('shared_prefs/x.xml'), isFalse);
      expect(CloneDataCodec.shouldSkipDataEntry('databases/t.db'), isFalse);
      expect(CloneDataCodec.shouldSkipDataEntry('app_webview/Cookies'), isFalse);
      expect(CloneDataCodec.shouldSkipDataEntry('files/session'), isFalse);
    });
  });

  group('恢复决策', () {
    CloneDataEntry e(String pkg, int user, {String? label, String app = 'App', bool apk = true}) =>
        CloneDataEntry(packageName: pkg, userId: user, customLabel: label, appName: app, hasApk: apk);

    test('覆盖已有分身时沿用原 userId，不改备注', () {
      final plan = CloneDataCodec.planRestore(
        backup: [e('com.t.app', 0, label: '工作')],
        mode: CloneDataCodec.modeOverwrite,
        installedKeys: {'com.t.app#0'},
        existingLabels: ['工作'],
      );
      expect(plan.single.action, 'overwrite');
      expect(plan.single.targetUserId, 0);
      expect(plan.single.needsApk, isFalse);
      expect(plan.single.label, '工作');
    });

    test('覆盖但本机没有时，在原 userId 上新建', () {
      final plan = CloneDataCodec.planRestore(
        backup: [e('com.t.app', 3, label: '出差')],
        mode: CloneDataCodec.modeOverwrite,
        installedKeys: {},
        existingLabels: const [],
      );
      expect(plan.single.action, 'create');
      expect(plan.single.targetUserId, 3);
      expect(plan.single.needsApk, isTrue);
    });

    test('全部新建：已有的换下一个空闲 userId，备注冲突才加「复制」', () {
      final plan = CloneDataCodec.planRestore(
        backup: [
          e('a.one', 0, label: '甲'),
          e('a.one', 1, label: '乙'),
          e('b.two', 0, label: '甲'),
        ],
        mode: CloneDataCodec.modeCreateNew,
        installedKeys: {'a.one#0', 'a.one#1', 'b.two#0'},
        existingLabels: ['甲', '乙'],
      );
      expect(plan.map((p) => p.targetUserId).toList(), [2, 3, 1]);
      expect(plan.map((p) => p.label).toList(), ['甲 复制', '乙 复制', '甲 复制2']);
    });

    test('备注不冲突时不加后缀；空备注不改写', () {
      final unique = CloneDataCodec.planRestore(
        backup: [e('com.t.app', 0, label: '独有名')],
        mode: CloneDataCodec.modeCreateNew,
        installedKeys: {'com.t.app#0'},
        existingLabels: ['其他'],
      );
      expect(unique.single.label, '独有名');
      expect(unique.single.targetUserId, 1);

      final empty = CloneDataCodec.planRestore(
        backup: [e('com.t.app', 0, label: null)],
        mode: CloneDataCodec.modeCreateNew,
        installedKeys: {'com.t.app#0'},
        existingLabels: ['App'],
      );
      expect(empty.single.label, '');
    });

    test('分身数量达到上限时报错', () {
      final used = {for (var i = 0; i < CloneDataCodec.maxUsers; i++) 'p#$i'};
      final plan = CloneDataCodec.planRestore(
        backup: [e('p', 0, label: 'n')],
        mode: CloneDataCodec.modeCreateNew,
        installedKeys: used,
        existingLabels: const [],
      );
      expect(plan.single.action, 'error');
    });
  });
}
