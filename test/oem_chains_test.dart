import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/screens/compat_screen.dart';
import 'package:parallel_space/services/native_bridge.dart';

import 'fake_native.dart';

const _families = [
  'XIAOMI',
  'HUAWEI',
  'HONOR',
  'OPPO',
  'VIVO',
  'SAMSUNG',
  'MEIZU',
  'STOCK',
];

const _kinds = ['overlay', 'accessibility', 'autostart', 'shortcut', 'notification', 'battery'];

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('compatOpen 能拆出错误码和手动路径', () {
    expect(NativeBridge.compatOpenCode('rom'), 'rom');
    expect(NativeBridge.compatOpenHint('rom'), '');
    expect(NativeBridge.compatOpenCode('error|请手动打开设置'), 'error');
    expect(NativeBridge.compatOpenHint('error|请手动打开设置'), '请手动打开设置');
  });

  test('每个主流品牌的权限链都以通用回退结尾', () async {
    NativeBridge.debugIsAndroid = true;
    final m = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    m.setMockMethodCallHandler(FakeNative.channel, (MethodCall call) async {
      if (call.method != 'compatChains') return null;
      return {
        for (final f in _families)
          f: {
            for (final k in _kinds)
              k: [
                if (f != 'STOCK') '${f}_oem_$k',
                if (k == 'overlay') ...['ACTION_MANAGE_OVERLAY_PERMISSION:package', 'ACTION_MANAGE_OVERLAY_PERMISSION'],
                if (k == 'accessibility') 'ACTION_ACCESSIBILITY_SETTINGS',
                if (k == 'notification') 'ACTION_APP_NOTIFICATION_SETTINGS',
                if (k == 'battery') 'ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
                'APPLICATION_DETAILS',
                'SETTINGS',
              ],
          },
      };
    });
    addTearDown(() {
      m.setMockMethodCallHandler(FakeNative.channel, null);
      NativeBridge.debugIsAndroid = null;
    });

    final chains = await NativeBridge.compatChains();
    expect(chains, isNotNull);
    for (final f in _families) {
      expect(chains!.containsKey(f), isTrue, reason: 'missing family $f');
      final kinds = Map<String, dynamic>.from(chains[f] as Map);
      for (final k in _kinds) {
        final list = (kinds[k] as List).cast<String>();
        expect(list, isNotEmpty, reason: '$f/$k empty');
        expect(list.last, 'SETTINGS', reason: '$f/$k must end with SETTINGS');
        expect(list.contains('APPLICATION_DETAILS'), isTrue, reason: '$f/$k needs APPLICATION_DETAILS');
      }
    }
  });

  test('vaultCopy 区分账号和密码', () async {
    NativeBridge.debugIsAndroid = true;
    final m = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    m.setMockMethodCallHandler(FakeNative.channel, (MethodCall call) async {
      if (call.method != 'vaultCopy') return null;
      final a = call.arguments as Map;
      return (a['which'] == 'user' || a['which'] == 'username') ? 'copied_user' : 'copied_pass';
    });
    addTearDown(() {
      m.setMockMethodCallHandler(FakeNative.channel, null);
      NativeBridge.debugIsAndroid = null;
    });
    expect(await NativeBridge.vaultCopy('1', 'user'), 'copied_user');
    expect(await NativeBridge.vaultCopy('1', 'pass'), 'copied_pass');
  });

  test('CompatReport 解析鸿蒙说明字段', () {
    final r = CompatReport.fromMap({
      'romLabel': '华为 HarmonyOS 4',
      'brand': '华为',
      'harmonyNext': false,
      'harmonyNote': '请打开后台弹出界面',
      'items': [],
    });
    expect(r.harmonyNext, isFalse);
    expect(r.harmonyNote, contains('后台弹出'));
    final next = CompatReport.fromMap({'harmonyNext': true, 'harmonyNote': '纯血', 'items': []});
    expect(next.harmonyNext, isTrue);
    final tip = CompatReport.fromMap({
      'brandTip': 'ColorOS：请打开悬浮窗',
      'items': [
        {'id': 'popup', 'title': '后台弹出界面', 'status': 'unknown', 'detail': '请允许', 'canOpen': true},
      ],
    });
    expect(tip.brandTip, contains('悬浮窗'));
    expect(tip.items.single.id, 'popup');
    expect(tip.items.single.canOpen, isTrue);
  });
}
