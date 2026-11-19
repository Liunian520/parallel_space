import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/screens/camera_diag_screen.dart';
import 'package:parallel_space/screens/compat_screen.dart';
import 'package:parallel_space/screens/permission_status_style.dart';
import 'package:parallel_space/services/native_bridge.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('六种状态里只有已允许是绿色', () {
    const scheme = ColorScheme.light();
    const green = Color(0xFF2E7D32);
    expect(permissionStatusColor('granted', scheme), green);
    for (final status in ['denied', 'unknown', 'manual', 'na', 'conflict']) {
      expect(permissionStatusColor(status, scheme), isNot(green), reason: status);
    }
    expect(permissionStatusColor('unknown', scheme), scheme.outline);
    expect(permissionStatusColor('na', scheme), scheme.outline);
    expect(permissionStatusColor('manual', scheme), const Color(0xFFEF6C00));
    expect(permissionStatusColor('conflict', scheme), const Color(0xFFEF6C00));
  });

  test('打开结果的说明跟在竖线后面', () {
    expect(NativeBridge.compatOpenCode('rom|已打开电池相关设置'), 'rom');
    expect(NativeBridge.compatOpenHint('rom|已打开电池相关设置'), '已打开电池相关设置');
    expect(NativeBridge.compatOpenCode('error|请手动打开设置'), 'error');
    expect(NativeBridge.compatOpenHint('error|请手动打开设置'), '请手动打开设置');
    expect(NativeBridge.compatOpenHint('rom'), '');
  });

  test('状态页源码不申请权限、不刷新分身、打开设置后不立刻重读', () {
    final src = File('lib/screens/compat_screen.dart').readAsStringSync();
    expect(src.contains('permissionStatus'), isTrue);
    expect(src.contains('_gen'), isTrue);
    final open = src.split('Future<void> _open').last.split('Widget build').first;
    expect(open.contains('_load'), isFalse);
    for (final banned in ['cameraSelfTest', 'setToolboxEnabled', 'CloneStore', 'requestIgnoreBattery', 'A11yKeeper']) {
      expect(src.contains(banned), isFalse, reason: banned);
    }
  });

  testWidgets('未知、需手动确认和不适用不画成绿色，去设置跟能否打开有关', (t) async {
    final calls = await _install((call) async {
      if (call.method == 'permissionStatus') return _report();
      return null;
    });
    await t.binding.setSurfaceSize(const Size(800, 2400));
    addTearDown(() => t.binding.setSurfaceSize(null));
    await t.pumpWidget(const MaterialApp(home: CompatScreen()));
    await t.pumpAndSettle();
    expect(find.text('可检测权限'), findsOneWidget);
    expect(find.text('需手动确认'), findsWidgets);
    expect(find.text('分身相机诊断'), findsOneWidget);
    expect(find.text(permissionStatusIntro), findsOneWidget);
    expect(find.text('去设置'), findsNWidgets(2));
    expect(find.text('查看诊断日志'), findsOneWidget);
    for (final label in ['未知', '需手动确认', '不适用', '冲突']) {
      final texts = t.widgetList<Text>(find.text(label));
      expect(texts, isNotEmpty, reason: label);
      for (final text in texts) {
        expect(text.style?.color, isNot(const Color(0xFF2E7D32)), reason: label);
      }
    }
    expect(t.widget<Text>(find.text('已允许').first).style?.color, const Color(0xFF2E7D32));
    expect(t.widget<Text>(find.text('已连接').first).style?.color, const Color(0xFF2E7D32));
    expect(calls, isNot(contains('cameraSelfTest')));
  });

  testWidgets('后返回的旧报告不覆盖新报告，页面销毁后也不刷新', (t) async {
    final pending = <Completer<Object?>>[];
    await _install((call) async {
      if (call.method != 'permissionStatus') return null;
      final c = Completer<Object?>();
      pending.add(c);
      return c.future;
    });
    await t.pumpWidget(const MaterialApp(home: CompatScreen()));
    expect(pending, hasLength(1));
    pending[0].complete(_report(marker: '第一份'));
    await t.pump();
    expect(find.text('第一份'), findsOneWidget);

    t.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await t.pump();
    await t.tap(find.byIcon(Icons.refresh_rounded));
    await t.pump();
    expect(pending.length, greaterThanOrEqualTo(3));
    pending[2].complete(_report(marker: '最新'));
    await t.pump();
    pending[1].complete(_report(marker: '过期'));
    await t.pump();
    expect(find.text('最新'), findsOneWidget);
    expect(find.text('过期'), findsNothing);

    await t.pumpWidget(const SizedBox.shrink());
    if (pending.length > 3) pending.last.complete(_report(marker: '销毁后'));
    await t.pump();
    expect(find.text('销毁后'), findsNothing);
  });

  testWidgets('通道没有结果时显示读不到，不弹出权限窗', (t) async {
    final calls = await _install((call) async => null);
    await t.pumpWidget(const MaterialApp(home: CompatScreen()));
    await t.pumpAndSettle();
    expect(find.text('暂时读不到这台机器的权限状态。'), findsOneWidget);
    expect(calls, isNot(contains('cameraSelfTest')));
  });

  testWidgets('进入相机自检页不会自动打开相机', (t) async {
    final calls = await _install((call) async {
      if (call.method == 'cameraDiagLog') return '';
      return null;
    });
    await t.pumpWidget(const MaterialApp(home: CameraDiagScreen()));
    await t.pumpAndSettle();
    expect(find.text('开始自检'), findsOneWidget);
    expect(calls, isNot(contains('cameraSelfTest')));
    expect(calls, contains('cameraDiagLog'));
  });
}

final _channel = const MethodChannel('parallel_space/native');

Future<List<String>> _install(Future<Object?> Function(MethodCall call) handle) async {
  final calls = <String>[];
  NativeBridge.debugIsAndroid = true;
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  messenger.setMockMethodCallHandler(_channel, (call) async {
    calls.add(call.method);
    return handle(call);
  });
  addTearDown(() {
    messenger.setMockMethodCallHandler(_channel, null);
    NativeBridge.debugIsAndroid = null;
  });
  return calls;
}

Map<String, Object?> _report({String marker = '悬浮窗'}) => {
      'brand': 'Redmi',
      'romLabel': '小米 HyperOS',
      'model': 'Test',
      'release': '15',
      'sdk': 35,
      'abi': 'arm64-v8a',
      'brandTip': '',
      'harmonyNote': '',
      'items': [
        _item(id: 'overlay', title: marker, status: 'granted', badge: '已允许', canOpen: true, openId: 'overlay'),
        _item(id: 'a11y-live', title: '无障碍实时连接', status: 'granted', badge: '已连接', openId: 'accessibility'),
        _item(id: 'noti-runtime', title: '通知运行时权限', status: 'unknown', badge: '未知', group: 'detectable'),
        _item(id: 'shortcut', title: '桌面快捷方式', status: 'manual', badge: '需手动确认', group: 'manual', canOpen: true, openId: 'shortcut'),
        _item(id: 'autostart', title: '自启动', status: 'na', badge: '不适用', group: 'detectable'),
        _item(id: 'popup', title: '后台弹出', status: 'conflict', badge: '冲突'),
        _item(id: 'clone-cam', title: '分身里的相机和麦克风', status: 'unknown', badge: '未知', group: 'clone', openId: 'camera-diag'),
      ],
    };

Map<String, Object?> _item({
  required String id,
  required String title,
  required String status,
  required String badge,
  String group = 'detectable',
  bool canOpen = false,
  String openId = '',
}) =>
    {
      'id': id,
      'title': title,
      'status': status,
      'badge': badge,
      'detail': '$title 的说明',
      'group': group,
      'canOpen': canOpen,
      'openId': openId,
    };
