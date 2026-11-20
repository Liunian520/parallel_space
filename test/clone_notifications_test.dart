import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/main.dart';
import 'package:parallel_space/screens/clone_notifications_screen.dart';
import 'package:parallel_space/screens/settings_screen.dart';
import 'package:parallel_space/services/clone_notification_inbox.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'fake_native.dart';

Future<void> settle(WidgetTester t, [int frames = 20]) async {
  for (var i = 0; i < frames; i++) {
    await t.pump(const Duration(milliseconds: 60));
  }
}

const _sep = '\u001f';

String _tag(String pkg, int userId, int appId, String appTag) => 'ae1$_sep$userId$_sep$pkg$_sep$appId$_sep$appTag';

void main() {
  test('解析 ae1 tag，非法 userId 和宿主 tag 丢掉', () {
    final ok = parseCloneNotificationTag(_tag('com.a', 0, 7, 't'));
    expect(ok, isNotNull);
    expect(ok!.pkg, 'com.a');
    expect(ok.userId, 0);
    expect(ok.uid, 'w:com.a');
    expect(parseCloneNotificationTag(_tag('com.a', 1, 7, 't'))!.uid, 'w:com.a#1');
    expect(parseCloneNotificationTag(_tag('com.a', 0, 3, 'a${_sep}b'))!.appTag, 'a${_sep}b');
    expect(parseCloneNotificationTag('toolbox'), isNull);
    expect(parseCloneNotificationTag('ae1$_sep-1${_sep}com.a${_sep}1${_sep}x'), isNull);
    expect(parseCloneNotificationTag('ae1${_sep}1x${_sep}com.a${_sep}1${_sep}x'), isNull);
    expect(parseCloneNotificationTag('ae1${_sep}00${_sep}com.a${_sep}1${_sep}x'), isNull);
    expect(parseCloneNotificationTag('ae1${_sep}2147483648${_sep}com.a${_sep}1${_sep}x'), isNull);
    expect(parseCloneNotificationTag('ae1${_sep}0${_sep}com.a${_sep}2147483648${_sep}x'), isNull);
    expect(parseCloneNotificationTag('ae1${_sep}0$_sep${_sep}1${_sep}x'), isNull);
  });

  test('同包两份分身、孤儿、摘要和同 key 更新', () {
    final child = _tag('com.mm', 1, 4, 'child');
    final summary = _tag('com.mm', 1, 5, 'sum');
    final first = _tag('com.mm', 0, 4, 'child');
    final orphan = _tag('com.gone', 2, 1, 'x');
    final rows = presentCloneNotifications([
      CloneNoteActive(tag: child, id: 100, postTime: 10, title: '子通知', text: '正文', groupSummary: false),
      CloneNoteActive(tag: child, id: 100, postTime: 20, title: '新子通知', text: '新正文', groupSummary: false),
      CloneNoteActive(tag: summary, id: 101, postTime: 15, title: '摘要', text: '', groupSummary: true),
      CloneNoteActive(tag: first, id: 102, postTime: 5, title: '第一份', text: 'a', groupSummary: false),
      CloneNoteActive(tag: orphan, id: 103, postTime: 30, title: '孤儿', text: '还在', groupSummary: false),
      CloneNoteActive(tag: 'toolbox', id: 1, postTime: 99, title: '宿主', text: '不要显示', groupSummary: false),
    ], const [
      CloneNoteKnown(pkg: 'com.mm', userId: 0, name: '大号'),
      CloneNoteKnown(pkg: 'com.mm', userId: 1, name: '小号'),
    ]);
    expect(rows.map((r) => r.systemTag), [orphan, child, summary, first]);
    expect(rows.first.canOpen, isFalse);
    expect(rows.first.name, '当前没有这个分身');
    expect(rows[1].title, '新子通知');
    expect(rows[2].groupSummary, isTrue);
    expect(rows[2].name, '小号');
    expect(rows[3].name, '大号');
    expect(rows[3].canOpen, isTrue);
  });

  test('打开必须包名、userId 和名单同时对上', () {
    final tag = _tag('com.mm', 1, 4, 'child');
    const known = [CloneNoteKnown(pkg: 'com.mm', userId: 1, name: '小号')];
    expect(shouldLaunchCloneNotification(tag, known), isNotNull);
    expect(shouldLaunchCloneNotification(tag, known)!.userId, 1);
    expect(shouldLaunchCloneNotification(tag, const [CloneNoteKnown(pkg: 'com.mm', userId: 0, name: '大号')]), isNull);
    expect(shouldLaunchCloneNotification('toolbox', known), isNull);
  });

  test('旧刷新代际不接受', () {
    final gate = RefreshGate();
    final first = gate.begin();
    final second = gate.begin();
    expect(gate.accept(first), isFalse);
    expect(gate.accept(second), isTrue);
  });

  testWidgets('设置进入分身通知，空列表和工作资料说明同时在', (t) async {
    final native = FakeNative()..cloneNotificationsResult = {'ok': true, 'items': <Object>[]};
    native.install(t);
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await t.pumpWidget(buildProviders(prefs, child: const MaterialApp(home: SettingsScreen())));
    await settle(t);
    await t.scrollUntilVisible(find.text('分身通知'), 300);
    await t.tap(find.text('分身通知'));
    await settle(t, 30);
    expect(find.byType(CloneNotificationsScreen), findsOneWidget);
    expect(find.text('现在没有正在显示的虚拟分身通知。'), findsOneWidget);
    expect(find.text('工作资料里的应用通知不在此列表。'), findsOneWidget);
  });

  testWidgets('服务失败不说成没有通知', (t) async {
    final native = FakeNative()..cloneNotificationsResult = {'ok': false, 'error': 'unavailable'};
    native.install(t);
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await t.pumpWidget(buildProviders(prefs, child: const MaterialApp(home: CloneNotificationsScreen())));
    await settle(t, 30);
    expect(find.text('暂时读不到通知'), findsOneWidget);
    expect(find.text('工作资料里的应用通知不在此列表。'), findsOneWidget);
    expect(find.text('现在没有正在显示的虚拟分身通知。'), findsNothing);
  });

  testWidgets('打开小号只调用 launchVirtual，孤儿只能清除', (t) async {
    final child = _tag('com.mm', 1, 4, 'child');
    final orphan = _tag('com.gone', 2, 1, 'x');
    final native = FakeNative()
      ..cloneNotificationsResult = {
        'ok': true,
        'items': [
          {'tag': child, 'id': 100, 'postTime': 20, 'title': '新子通知', 'text': '新正文', 'groupSummary': false},
          {'tag': orphan, 'id': 103, 'postTime': 30, 'title': '孤儿', 'text': '还在', 'groupSummary': false},
          {'tag': _tag('com.mm', 1, 5, 'sum'), 'id': 101, 'postTime': 15, 'title': '摘要', 'text': '', 'groupSummary': true},
        ],
      };
    native.install(t);
    FakeNative.virtualAvailable = true;
    SharedPreferences.setMockInitialValues({
      'clones.v1.userOnly': true,
      'clones.v1.listPolicy': 3,
      'clones.v1':
          '[{"uid":"w:com.mm","pkg":"com.mm","name":"大号","appName":"微信","u":0,"kind":"work","ua":true},{"uid":"w:com.mm#1","pkg":"com.mm","name":"小号","appName":"微信","u":1,"kind":"work","ua":true}]',
    });
    final prefs = await SharedPreferences.getInstance();
    await t.pumpWidget(buildProviders(prefs, child: const MaterialApp(home: CloneNotificationsScreen())));
    await settle(t, 30);
    expect(find.text('分组摘要'), findsOneWidget);
    expect(find.text('当前没有这个分身'), findsOneWidget);
    expect(find.text('小号'), findsWidgets);
    final opens = find.widgetWithText(TextButton, '打开');
    expect(opens, findsNWidgets(2));
    await t.tap(opens.at(1));
    await settle(t, 10);
    final launch = native.calls.lastWhere((c) => c.method == 'launchVirtual');
    expect(launch.arguments['pkg'], 'com.mm');
    expect(launch.arguments['userId'], 1);
    expect(native.methods.contains('launchWorkApp'), isFalse);
    await t.tap(find.widgetWithText(TextButton, '清除').first);
    await settle(t, 10);
    final cancel = native.calls.lastWhere((c) => c.method == 'cloneNotificationCancel');
    expect(cancel.arguments['tag'], orphan);
    expect(cancel.arguments['id'], 103);
  });

  testWidgets('后完成的刷新盖住先发出的结果', (t) async {
    final first = Completer<Map<String, Object?>>();
    final second = Completer<Map<String, Object?>>();
    final native = FakeNative();
    var calls = 0;
    native.cloneNotificationsHandler = () {
      calls += 1;
      return calls == 1 ? first.future : second.future;
    };
    native.install(t);
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await t.pumpWidget(buildProviders(prefs, child: const MaterialApp(home: CloneNotificationsScreen())));
    await t.pump();
    await t.fling(find.byType(RefreshIndicator), const Offset(0, 300), 1000);
    await t.pump();
    first.complete({
      'ok': true,
      'items': [
        {'tag': _tag('com.a', 0, 1, 'a'), 'id': 1, 'postTime': 1, 'title': '旧结果', 'text': '', 'groupSummary': false},
      ],
    });
    await settle(t, 5);
    second.complete({'ok': true, 'items': <Object>[]});
    await settle(t, 10);
    expect(find.text('旧结果'), findsNothing);
    expect(find.text('现在没有正在显示的虚拟分身通知。'), findsOneWidget);
  });
}
