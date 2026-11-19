import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:parallel_space/services/backup_codec.dart';

void main() {
  final overlay = File(
    'android/app/src/main/kotlin/com/parallelspace/parallel_space/ToolboxOverlayService.kt',
  ).readAsStringSync();
  final kotlinRoot = Directory('android/app/src/main/kotlin');
  final heading = File(
    'android/app/src/main/java/com/parallelspace/parallel_space/VaultAppHeading.java',
  ).readAsStringSync();
  final vaultGroup = File('lib/services/vault_group.dart').readAsStringSync();
  final detail = File('lib/screens/clone_detail_screen.dart').readAsStringSync();
  final store = File(
    'android/app/src/main/kotlin/com/parallelspace/parallel_space/VaultStore.kt',
  ).readAsBytesSync();

  test('悬浮窗保持不可聚焦，分段不新增输入、写回或重建', () {
    expect(overlay.contains('FLAG_NOT_FOCUSABLE'), isTrue);
    expect(overlay.contains('VaultAppHeading.sections'), isTrue);
    expect(overlay.contains('cu == uid || (cu == pkg && CloneId.userId(uid) == 0)'), isTrue);
    expect(overlay.contains('e.pkg == pkg && CloneId.userId(uid) == 0'), isTrue);
    expect('requestRebuild("label_memo")'.allMatches(overlay).length, 1);
    for (final banned in ['EditText', 'SearchView', '未安装', 'reliablyUninstalled', 'VaultStore.persist', 'VaultStore.upsert']) {
      expect(overlay.contains(banned), isFalse, reason: banned);
    }
    final resolve = overlay.substring(
      overlay.indexOf('private fun resolveSectionHeading'),
      overlay.indexOf('private fun lookupSectionHeading'),
    );
    expect(resolve.contains('requestRebuild'), isFalse);
    expect(resolve.contains('refreshVaultAsync'), isFalse);
    expect(resolve.contains('invalidateCache'), isFalse);
    expect(resolve.contains('ToolboxCrashLog'), isFalse);
  });

  test('Kotlin 主源码不把查找失败写成未安装', () {
    final files = kotlinRoot
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) => f.path.endsWith('.kt'));
    for (final file in files) {
      final text = file.readAsStringSync();
      expect(text.contains('未安装'), isFalse, reason: file.path);
      expect(text.contains('reliablyUninstalled'), isFalse, reason: file.path);
    }
  });

  test('未安装三个字只出现在可靠卸载的返回值附近', () {
    final lines = heading.split('\n');
    final hits = [
      for (var i = 0; i < lines.length; i++)
        if (lines[i].contains('未安装')) i,
    ];
    expect(hits, isNotEmpty);
    for (final i in hits) {
      final from = i >= 3 ? i - 3 : 0;
      final window = lines.sublist(from, i + 1).join('\n');
      expect(window.contains('RELIABLY_UNINSTALLED'), isTrue, reason: lines[i]);
    }
  });

  test('搜索实现不读取密码字段', () {
    final start = vaultGroup.indexOf('List<VaultEntry> filterVaultEntries(');
    final end = vaultGroup.indexOf('/// 合并多源条目');
    final body = vaultGroup.substring(start, end);
    expect(body.contains('.password'), isFalse);
    expect(body.contains("['pass']"), isFalse);
    expect(body.contains('label'), isTrue);
    expect(body.contains('username'), isTrue);
    expect(body.contains('packageName'), isTrue);
    expect(body.contains('note'), isTrue);
    expect(body.contains('cloneUid'), isTrue);
  });

  test('分身详情入口和密码本文件保持原样', () {
    expect(
      detail.contains('openVaultScreen(context, initialPackage: c.uid, openEditor: true)'),
      isTrue,
    );
    expect(
      _sha256Hex(store),
      '54b5fb224093df3276d88e9f618577c8bd7bea1ced2724df3891cefdce1d6a64',
    );
  });

  test('应用名读取一次返回完整结果，不在 catch 里重写 val', () {
    final controller = File(
      'android/app/src/main/kotlin/com/parallelspace/parallel_space/ToolboxController.kt',
    ).readAsStringSync();
    final start = controller.indexOf('private fun appLabelState');
    final end = controller.indexOf('private class AppLabelLookup');
    expect(start, greaterThan(0));
    expect(end, greaterThan(start));
    final body = controller.substring(start, end);
    expect(RegExp(r'^\s*(kind|label)\s*=', multiLine: true).hasMatch(body), isFalse);
    expect(body.contains('NameNotFoundException'), isTrue);
    expect(body.contains('kindFromLookup(false, null, true, false)'), isTrue);
    expect(body.contains('kindFromLookup(false, null, false, true)'), isTrue);
    expect(body.contains('channelLabel'), isTrue);
    expect(body.contains('ToolboxCrashLog'), isFalse);
    expect(body.contains('print('), isFalse);
  });

  test('配置备份白名单不含搜索词', () {
    expect(
      BackupCodec.settingKeys,
      {'title', 'theme', 'dyn', 'seed', 'fx', 'updAuto', 'toolbox'},
    );
  });
}

String _sha256Hex(List<int> bytes) => _Sha256().update(bytes).digest();

class _Sha256 {
  // Compact public-domain style SHA-256. Enough to lock VaultStore.kt bytes.
  static const _k = <int>[
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];

  final _h = <int>[0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  final _buf = <int>[];
  int _len = 0;

  _Sha256 update(List<int> bytes) {
    _buf.addAll(bytes);
    _len += bytes.length;
    while (_buf.length >= 64) {
      _block(_buf.sublist(0, 64));
      _buf.removeRange(0, 64);
    }
    return this;
  }

  String digest() {
    final bitLen = _len * 8;
    _buf.add(0x80);
    while ((_buf.length % 64) != 56) {
      _buf.add(0);
    }
    for (var i = 7; i >= 0; i--) {
      _buf.add((bitLen >> (i * 8)) & 0xff);
    }
    while (_buf.length >= 64) {
      _block(_buf.sublist(0, 64));
      _buf.removeRange(0, 64);
    }
    final out = StringBuffer();
    for (final v in _h) {
      out.write((v & 0xffffffff).toRadixString(16).padLeft(8, '0'));
    }
    return out.toString();
  }

  void _block(List<int> chunk) {
    final w = List<int>.filled(64, 0);
    for (var i = 0; i < 16; i++) {
      w[i] = (chunk[i * 4] << 24) | (chunk[i * 4 + 1] << 16) | (chunk[i * 4 + 2] << 8) | chunk[i * 4 + 3];
    }
    for (var i = 16; i < 64; i++) {
      final s0 = _rotr(w[i - 15], 7) ^ _rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      final s1 = _rotr(w[i - 2], 17) ^ _rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) & 0xffffffff;
    }
    var a = _h[0], b = _h[1], c = _h[2], d = _h[3], e = _h[4], f = _h[5], g = _h[6], hh = _h[7];
    for (var i = 0; i < 64; i++) {
      final s1 = _rotr(e, 6) ^ _rotr(e, 11) ^ _rotr(e, 25);
      final ch = (e & f) ^ ((~e) & g);
      final t1 = (hh + s1 + ch + _k[i] + w[i]) & 0xffffffff;
      final s0 = _rotr(a, 2) ^ _rotr(a, 13) ^ _rotr(a, 22);
      final maj = (a & b) ^ (a & c) ^ (b & c);
      final t2 = (s0 + maj) & 0xffffffff;
      hh = g;
      g = f;
      f = e;
      e = (d + t1) & 0xffffffff;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) & 0xffffffff;
    }
    _h[0] = (_h[0] + a) & 0xffffffff;
    _h[1] = (_h[1] + b) & 0xffffffff;
    _h[2] = (_h[2] + c) & 0xffffffff;
    _h[3] = (_h[3] + d) & 0xffffffff;
    _h[4] = (_h[4] + e) & 0xffffffff;
    _h[5] = (_h[5] + f) & 0xffffffff;
    _h[6] = (_h[6] + g) & 0xffffffff;
    _h[7] = (_h[7] + hh) & 0xffffffff;
  }

  int _rotr(int x, int n) => ((x >>> n) | (x << (32 - n))) & 0xffffffff;
}
