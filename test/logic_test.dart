import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/models/app_info.dart';
import 'package:parallel_space/models/clone_app.dart';
import 'package:parallel_space/models/location_models.dart';
import 'package:parallel_space/services/backup_codec.dart';
import 'package:parallel_space/services/pin_hash.dart';

void main() {
  group('RouteMath', () {
    const a = GeoPoint(0, 0), b = GeoPoint(0, 1);

    test('距离与航向', () {
      expect(RouteMath.distance(a, b), closeTo(111195, 50));
      expect(RouteMath.bearing(a, b), closeTo(90, 0.01));
      expect(RouteMath.bearing(b, a), closeTo(270, 0.01));
      expect(RouteMath.bearing(a, const GeoPoint(1, 0)), closeTo(0, 0.01));
    });

    test('单程：起点、中点、终点后停止', () {
      final total = RouteMath.totalLength([a, b]);
      expect(RouteMath.sample([a, b], 0).point.lng, 0);
      final mid = RouteMath.sample([a, b], total / 2);
      expect(mid.point.lng, closeTo(0.5, 1e-6));
      expect(mid.finished, isFalse);
      final end = RouteMath.sample([a, b], total * 3);
      expect(end.finished, isTrue);
      expect(end.progress, 1);
      expect(end.point.lng, closeTo(1, 1e-9));
    });

    test('循环：自动闭合并回绕', () {
      const pts = [GeoPoint(0, 0), GeoPoint(0, 1), GeoPoint(1, 1)];
      final total = RouteMath.totalLength(pts, loop: true);
      expect(total, greaterThan(RouteMath.totalLength(pts)));
      final s = RouteMath.sample(pts, total + 10, loop: true);
      expect(s.finished, isFalse);
      expect(s.progress, lessThan(0.01));
    });

    test('跨越 ±180 经线取短边插值', () {
      const p = [GeoPoint(0, 179.5), GeoPoint(0, -179.5)];
      expect(RouteMath.totalLength(p), closeTo(111195, 100));
      final m = RouteMath.sample(p, RouteMath.totalLength(p) / 2);
      expect(m.point.lng.abs(), closeTo(180, 1e-6));
    });

    test('边界：空、单点、重复点、负距离', () {
      expect(RouteMath.sample(const [], 10).finished, isTrue);
      expect(RouteMath.sample(const [a], 10).point, a);
      final dup = RouteMath.sample(const [a, a], 5);
      expect(dup.point, a);
      expect(RouteMath.sample([a, b], -100).point.lng, 0);
    });

    test('MockRoute.fromJson 过滤非法点与不足 2 点的路线', () {
      expect(MockRoute.fromJson({'name': 'x', 'points': [[0, 0]]}), isNull);
      final r = MockRoute.fromJson({
        'name': 'x',
        'points': [[0, 0], [999, 0], 'bad', [1, 1]],
        'speed': 'rocket',
      })!;
      expect(r.points.length, 2);
      expect(r.speed, SpeedMode.walk);
    });
  });

  group('PinHash', () {
    test('哈希可复现、加盐不同、格式校验', () {
      final h = PinHash.hash('1234', 'salt');
      expect(PinHash.hash('1234', 'salt'), h);
      expect(PinHash.hash('1234', 'other'), isNot(h));
      expect(PinHash.valid('123'), isFalse);
      expect(PinHash.valid('12a4'), isFalse);
      expect(PinHash.valid('123456'), isTrue);
      expect(PinHash.equals('abc', 'abc'), isTrue);
      expect(PinHash.equals('abc', 'abd'), isFalse);
    });
  });

  group('CloneApp 数据迁移', () {
    test('1.0/1.1 的模拟分身（没有包名）被丢弃', () {
      expect(CloneApp.fromJson({'uid': 'c1', 'app': 'wechat', 'index': 2, 'name': '微信', 'emoji': '😀'}), isNull);
      expect(CloneApp.fromJson({'uid': 'c2', 'app': 'qq', 'kind': 'work'}), isNull);
      expect(CloneApp.fromJson({'uid': 'c3', 'pkg': '  '}), isNull);
    });

    test('1.1 工作资料分身保留设置；与应用名相同的 name 不算自定义', () {
      final c = CloneApp.fromJson({
        'uid': 'w1', 'app': 'real:com.x', 'pkg': 'com.x', 'appName': 'X', 'kind': 'work', 'index': 2,
        'name': 'X', 'color': 123, 'fp': true, 'mock': true, 'mockName': '东京', 'lat': 35.6, 'lng': 139.7, 'priv': true,
      })!;
      expect(c.packageName, 'com.x');
      expect(c.customLabel, isNull);
      expect(c.customName, 'X');
      expect(c.fingerprintLock, isTrue);
      expect(c.isPrivate, isTrue);
      expect(c.mockName, '东京');
      final named = CloneApp.fromJson({'uid': 'w2', 'pkg': 'com.y', 'appName': 'Y', 'name': '工作号'})!;
      expect(named.customName, '工作号');
    });

    test('类型错误 / 越界值不会崩溃；缺少 uid 时按包名生成', () {
      final c = CloneApp.fromJson({'pkg': 'com.z', 'fp': 'yes', 'lat': 500, 'lng': 10, 'af': 99999, 'cat': 'nope'})!;
      expect(c.uid, 'w:com.z');
      expect(c.appName, 'com.z');
      expect(c.fingerprintLock, isFalse);
      expect(c.mockLat, 39.908722); // 非法坐标回退默认
      expect(c.autoFreezeMin, 1440);
      expect(c.category, AppCategory.other);
    });

    test('toJson → fromJson 往返', () {
      final c = CloneApp(
        uid: 'u', packageName: 'com.a', appName: 'A', version: '1.0', category: AppCategory.social,
        customLabel: '小号', isPrivate: true, autoFreezeMin: 15, passwordLock: true,
      );
      final r = CloneApp.fromJson(jsonDecode(jsonEncode(c.toJson())) as Map<String, dynamic>)!;
      expect(r.isPrivate, isTrue);
      expect(r.autoFreezeMin, 15);
      expect(r.customName, '小号');
      expect(r.category, AppCategory.social);
      expect(r.passwordLock, isTrue);
    });

    test('AppInfo.fromMap：原生数据校验', () {
      expect(AppInfo.fromMap(null), isNull);
      expect(AppInfo.fromMap({'pkg': ''}), isNull);
      final a = AppInfo.fromMap({'pkg': 'com.a', 'label': ' ', 'ver': 3, 'cat': 'game', 'suspended': true})!;
      expect(a.name, 'com.a');
      expect(a.version, '');
      expect(a.category, AppCategory.game);
      expect(a.suspended, isTrue);
    });
  });

  group('BackupCodec', () {
    BackupData sample() => BackupData(
          clones: [CloneApp(uid: 'a', packageName: 'com.a', appName: 'A')],
          favorites: [FavoriteLocation(id: 'f', name: '家', lat: 1, lng: 2)],
          routes: [MockRoute(id: 'r', name: 'R', points: const [GeoPoint(0, 0), GeoPoint(1, 1)])],
          settings: {'title': 'T', 'theme': 1, 'pinHash': 'SECRET', 'priv.pinSalt': 'SALT'},
        );

    test('往返，且不包含任何 PIN 信息', () {
      final text = BackupCodec.encode(sample());
      expect(text, isNot(contains('SECRET')));
      expect(text, isNot(contains('SALT')));
      expect(text.toLowerCase(), isNot(contains('pin')));
      final d = BackupCodec.decode(text);
      expect(d.clones.single.uid, 'a');
      expect(d.favorites.single.name, '家');
      expect(d.routes.single.points.length, 2);
      expect(d.settings['title'], 'T');
      expect(d.skipped, 0);
    });

    test('拒绝：非 JSON、非对象、错误格式、未来版本、超大文件', () {
      expect(() => BackupCodec.decode('not json'), throwsA(isA<BackupFormatException>()));
      expect(() => BackupCodec.decode('[1,2]'), throwsA(isA<BackupFormatException>()));
      expect(() => BackupCodec.decode('{"format":"other","version":1}'), throwsA(isA<BackupFormatException>()));
      expect(() => BackupCodec.decode('{"format":"${BackupCodec.format}","version":99}'),
          throwsA(isA<BackupFormatException>()));
      expect(() => BackupCodec.decode('{"format":"${BackupCodec.format}","version":"2"}'),
          throwsA(isA<BackupFormatException>()));
      expect(() => BackupCodec.decode(' ' * (BackupCodec.maxBytes + 1)), throwsA(isA<BackupFormatException>()));
      expect(() => BackupCodec.decode('{"format":"${BackupCodec.format}","version":2,"clones":{}}'),
          throwsA(isA<BackupFormatException>()));
    });

    test('跳过坏条目与重复 uid，忽略未知设置键', () {
      final d = BackupCodec.decode(jsonEncode({
        'format': BackupCodec.format,
        'version': 1,
        'clones': [
          {'uid': 'a', 'pkg': 'com.a'},
          {'uid': 'a', 'pkg': 'com.a'},
          {'uid': 'b', 'app': 'qq'},
          42,
        ],
        'favorites': [{'name': '', 'lat': 0, 'lng': 0}, {'name': 'ok', 'lat': 1, 'lng': 1}],
        'settings': {'title': 'x', 'priv.pinHash': 'h', 'evil': true},
      }));
      expect(d.clones.length, 1);
      expect(d.favorites.length, 1);
      expect(d.skipped, 4);
      expect(d.settings.keys, ['title']);
    });
  });
}
