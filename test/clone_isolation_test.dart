import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/models/clone_app.dart';
import 'package:parallel_space/models/device_identity.dart';
import 'package:parallel_space/services/clone_data_codec.dart';
import 'package:parallel_space/state/clone_store.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('旧分身数据没有隔离开关，解析后保持关闭', () {
    final c = CloneApp.fromJson({'uid': 'w:com.a', 'pkg': 'com.a', 'appName': 'A'})!;
    expect(c.deviceIdIsolated, isFalse);
    expect(c.toJson().containsKey('iso'), isFalse);
  });

  test('开关可以往返，不把标识写进分身名单', () {
    final c = CloneApp(uid: 'w:com.a', packageName: 'com.a', appName: 'A', deviceIdIsolated: true);
    final back = CloneApp.fromJson(jsonDecode(jsonEncode(c.toJson())) as Map<String, dynamic>)!;
    expect(back.deviceIdIsolated, isTrue);
    expect(jsonEncode(c.toJson()), isNot(contains('androidId')));
  });

  test('通道结果：关着不展示标识，非法标识也不展示', () {
    expect(DeviceIdentity.fromMap(null).enabled, isFalse);
    expect(DeviceIdentity.fromMap({'enabled': false, 'androidId': '0123456789abcdef'}).showId, isFalse);
    final on = DeviceIdentity.fromMap({'enabled': true, 'androidId': '0123456789abcdef'});
    expect(on.showId, isTrue);
    expect(DeviceIdentity.fromMap({'enabled': true, 'androidId': '0'}).showId, isFalse);
    expect(DeviceIdentity.relaunchNote, contains('重新登录'));
    expect(DeviceIdentity.relaunchNote, contains('不能绕过'));
  });

  test('旧版 .aebak 清单没有 identity，新清单可以带回', () {
    const old = '{"format":"alter_ego.clone_data","version":1,"clones":[{"packageName":"com.a","userId":0,"appName":"A"}]}';
    final parsed = CloneDataCodec.decode(old);
    expect(parsed.clones.single.identityEnabled, isNull);
    expect(parsed.clones.single.androidId, isNull);
    final entry = CloneDataEntry(
      packageName: 'com.a',
      userId: 0,
      appName: 'A',
      identityEnabled: true,
      androidId: '0123456789abcdef',
    );
    final again = CloneDataEntry.fromJson(jsonDecode(jsonEncode(entry.toJson())))!;
    expect(again.identityEnabled, isTrue);
    expect(again.androidId, '0123456789abcdef');
    final off = CloneDataEntry(packageName: 'com.a', userId: 1, identityEnabled: false, androidId: 'fedcba9876543210');
    final offBack = CloneDataEntry.fromJson(jsonDecode(jsonEncode(off.toJson())))!;
    expect(offBack.identityEnabled, isFalse);
    expect(offBack.androidId, 'fedcba9876543210');
  });

  test('恢复结果写回开关，缺省时不改掉原来的', () async {
    SharedPreferences.setMockInitialValues({'clones.v1.userOnly': true});
    final store = CloneStore(await SharedPreferences.getInstance());
    store.adoptRestored(packageName: 'com.a', userId: 0, appName: 'A', deviceIdIsolated: true);
    expect(store.byInstance('com.a', 0)!.deviceIdIsolated, isTrue);
    store.adoptRestored(packageName: 'com.a', userId: 0, appName: 'A');
    expect(store.byInstance('com.a', 0)!.deviceIdIsolated, isTrue);
    store.adoptRestored(packageName: 'com.a', userId: 0, appName: 'A', deviceIdIsolated: false);
    expect(store.byInstance('com.a', 0)!.deviceIdIsolated, isFalse);
    store.adoptRestored(packageName: 'com.b', userId: 2, appName: 'B', deviceIdIsolated: true);
    expect(store.byInstance('com.b', 2)!.deviceIdIsolated, isTrue);
  });
}
