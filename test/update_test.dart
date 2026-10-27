import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/config/update_config.dart';
import 'package:parallel_space/services/update_manifest.dart';

void main() {
  group('versionCode scheme', () {
    test('1.2.4 → 10204；1.3.x → 12300+patch', () {
      expect(encodeVersionCode(1, 2, 4), 10204);
      expect(encodeVersionCode(1, 3, 0), 12300);
      expect(encodeVersionCode(1, 3, 6), 12306);
      expect(versionCodeFromName('1.2.4'), 10204);
      expect(versionCodeFromName('1.3.0+12300'), 12300);
      expect(versionCodeFromName('1.3.6'), 12306);
      expect(versionCodeFromName('bogus'), isNull);
    });
  });

  group('update URLs', () {
    test('manifest is stable raw URL; share fallback is short link', () {
      expect(kUpdateManifestUrl, contains('raw.githubusercontent.com'));
      expect(kUpdateManifestUrl, contains('update_manifest.txt'));
      expect(kUpdateShareFallbackUrl, startsWith('https://link.liunianai.cn/'));
      expect(kUpdateManifestUrl, isNot(contains('n8afhu')), reason: 'app must not use shield short link as primary');
    });
  });

  group('UpdateManifest.tryParse', () {
    test('valid with apkShareUrl', () {
      final m = UpdateManifest.tryParse({
        'versionName': '1.3.6',
        'versionCode': 12306,
        'apkUrl': 'https://link.liunianai.cn/f/disk/a/parallel_space.apk',
        'apkShareUrl': 'https://link.liunianai.cn/abcd',
        'sha256': 'a' * 64,
        'size': 19970000,
        'notes': 'fix',
        'publishedAt': '2026-10-27T00:00:00+08:00',
      });
      expect(m, isNotNull);
      expect(m!.apkShareUrl, 'https://link.liunianai.cn/abcd');
      expect(m.isNewerThanApp(currentCode: 10305, currentName: '1.3.5'), isTrue);
      expect(m.isNewerThanApp(currentCode: 12302, currentName: '1.3.2'), isTrue);
      expect(m.isNewerThanApp(currentCode: 12306, currentName: '1.3.6'), isFalse);
    });

    test('missing fields → null', () {
      expect(UpdateManifest.tryParse({'versionName': '1.0.0'}), isNull);
      expect(UpdateManifest.tryParse({'versionCode': 1, 'apkUrl': 'ftp://x', 'sha256': 'a' * 64, 'size': 1, 'versionName': '1.0.0'}), isNull);
      expect(UpdateManifest.tryParse({'versionName': '1.0.0', 'versionCode': 1, 'apkUrl': 'https://x', 'sha256': 'dead', 'size': 1}), isNull);
    });

    test('sha normalized to lowercase hex', () {
      final m = UpdateManifest.tryParse({
        'versionName': '1.0.0',
        'versionCode': '10000',
        'apkUrl': 'http://example.com/a.apk',
        'sha256': 'AA' * 32,
        'size': 10,
      });
      expect(m!.sha256, 'aa' * 32);
      expect(m.apkShareUrl, isNull);
    });

    test('JSON string roundtrip', () {
      final raw =
          '{"versionName":"1.3.0","versionCode":10300,"apkUrl":"https://x/a.apk","sha256":"${'ab' * 32}","size":12,"notes":""}';
      expect(UpdateManifest.tryParse(jsonDecode(raw)), isNotNull);
    });
  });

  group('sha mismatch messaging', () {
    test('StateError message used by UI', () {
      expect(
        () => throw StateError('SHA-256 不符'),
        throwsA(isA<StateError>().having((e) => e.message, 'message', contains('SHA-256'))),
      );
    });
  });
}
