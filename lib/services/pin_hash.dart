import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

/// PIN 加盐哈希（PBKDF 风格的多轮 SHA-256），PIN 明文从不落盘、从不导出。
class PinHash {
  static const rounds = 20000;

  static String newSalt([Random? r]) {
    final rnd = r ?? Random.secure();
    return base64Url.encode(List.generate(16, (_) => rnd.nextInt(256)));
  }

  static String hash(String pin, String salt) {
    List<int> bytes = utf8.encode('$salt:$pin');
    for (var i = 0; i < rounds; i++) {
      bytes = sha256.convert(bytes).bytes;
    }
    return base64Url.encode(bytes);
  }

  static bool valid(String pin) => RegExp(r'^\d{4,8}$').hasMatch(pin);

  /// 常量时间比较。
  static bool equals(String a, String b) {
    if (a.length != b.length) return false;
    var r = 0;
    for (var i = 0; i < a.length; i++) {
      r |= a.codeUnitAt(i) ^ b.codeUnitAt(i);
    }
    return r == 0;
  }
}
