// ignore_for_file: deprecated_member_use, depend_on_referenced_packages
import 'dart:typed_data';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart';
import 'package:parallel_space/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  final seeds = <String, Map<String, Object>>{
    'empty': {},
    'v1.0 legacy': {
      'clones.v1': '[{"uid":"a1","app":"wechat","index":2,"name":"工作号","color":4283215696,"fp":true},{"uid":"a2","app":"qq","index":2}]',
      'clones.v1.seq': 2, 's.theme': 2, 's.dyn': true, 's.seed': 4280391411, 's.title': '我的空间',
    },
    'corrupt': {
      'clones.v1': '{not json', 's.theme': 'dark', 's.dndS': 'x', 'loc.fav': '[1,2', 's.seed': 'blue',
    },
  };
  for (final dyn in [false, true]) {
  for (final seed in seeds.entries) {
    testWidgets('startup dyn=$dyn prefs=${seed.key}', (t) async {
      final errors = <Object>[];
      final old = FlutterError.onError;
      FlutterError.onError = (d) => errors.add('${d.exception}\n${d.stack}');
      if (dyn) {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
            DynamicColorPlugin.channel, (c) async => c.method == 'getCorePalette' ? Int64List.fromList(CorePalette.of(0xFF3366FF).asList()) : null);
      }
      SharedPreferences.setMockInitialValues(seed.value);
      final prefs = await SharedPreferences.getInstance();
      await t.pumpWidget(buildProviders(prefs, child: const ParallelSpaceApp()));
      for (var i = 0; i < 30; i++) {
        await t.pump(const Duration(milliseconds: 60));
      }
      FlutterError.onError = old;
      expect(errors, isEmpty, reason: errors.join('\n---\n'));
      expect(find.byType(ErrorCard), findsNothing);
      expect(find.text(seed.key == 'v1.0 legacy' ? '我的空间' : '平行空间'), findsWidgets);
    });
  }
  }

  testWidgets('ErrorWidget.builder 显示可读错误卡片', (t) async {
    final prev = ErrorWidget.builder;
    final prevOn = FlutterError.onError;
    installErrorGuards();
    final w = ErrorWidget.builder(FlutterErrorDetails(exception: StateError('boom')));
    await t.pumpWidget(w);
    expect(find.text('页面出错了'), findsOneWidget);
    expect(find.textContaining('boom'), findsOneWidget);
    ErrorWidget.builder = prev;
    FlutterError.onError = prevOn;
  });
}
