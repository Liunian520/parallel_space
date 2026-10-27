import 'package:flutter/widgets.dart';
import 'package:local_auth/local_auth.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/pin_hash.dart';

/// 私密空间：PIN（加盐哈希）+ 可选生物识别；App 进入后台自动上锁。
class PrivateSpaceStore extends ChangeNotifier with WidgetsBindingObserver {
  PrivateSpaceStore(this._prefs, {LocalAuthentication? auth, DateTime Function()? clock})
      : _auth = auth ?? LocalAuthentication(),
        _clock = clock ?? DateTime.now {
    biometricEnabled = _prefs.getBool(_bioKey) ?? false;
  }

  static const _hashKey = 'priv.pinHash';
  static const _saltKey = 'priv.pinSalt';
  static const _bioKey = 'priv.bio';
  static const maxAttempts = 5;
  static const cooldown = Duration(seconds: 30);

  final SharedPreferences _prefs;
  final LocalAuthentication _auth;
  final DateTime Function() _clock;

  bool _unlocked = false;
  bool _authInProgress = false;
  int _failed = 0;
  DateTime? _lockedUntil;
  bool biometricEnabled = false;

  bool get unlocked => _unlocked;
  bool get hasPin => _prefs.getString(_hashKey) != null && _prefs.getString(_saltKey) != null;

  /// 剩余冷却时间（连续输错 5 次后 30 秒内禁止尝试）。
  Duration? get cooldownLeft {
    final u = _lockedUntil;
    if (u == null) return null;
    final left = u.difference(_clock());
    return left.isNegative ? null : left;
  }

  void attach() => WidgetsBinding.instance.addObserver(this);

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 生物识别弹窗/系统文件选择器会让 Activity 暂停，验证期间不上锁。
    if ((state == AppLifecycleState.paused || state == AppLifecycleState.hidden) && !_authInProgress) lock();
  }

  void lock() {
    if (_unlocked) {
      _unlocked = false;
      notifyListeners();
    }
  }

  bool setPin(String pin) {
    if (!PinHash.valid(pin)) return false;
    final salt = PinHash.newSalt();
    _prefs
      ..setString(_saltKey, salt)
      ..setString(_hashKey, PinHash.hash(pin, salt));
    _unlocked = true;
    _failed = 0;
    _lockedUntil = null;
    notifyListeners();
    return true;
  }

  /// 'ok' | 'wrong' | 'cooldown' | 'nopin'
  String unlockWithPin(String pin) {
    final r = checkPin(pin);
    if (r == 'ok') {
      _unlocked = true;
      notifyListeners();
    }
    return r;
  }

  /// 仅校验 PIN（用于分身密码锁），不会解锁私密空间。共享错误次数与冷却。
  String checkPin(String pin) {
    if (!hasPin) return 'nopin';
    if (cooldownLeft != null) return 'cooldown';
    final ok = PinHash.equals(PinHash.hash(pin, _prefs.getString(_saltKey)!), _prefs.getString(_hashKey)!);
    if (ok) {
      _failed = 0;
      _lockedUntil = null;
      return 'ok';
    }
    _failed++;
    if (_failed >= maxAttempts) {
      _failed = 0;
      _lockedUntil = _clock().add(cooldown);
      notifyListeners();
      return 'cooldown';
    }
    return 'wrong';
  }

  int get attemptsLeft => maxAttempts - _failed;

  Future<bool> canUseBiometrics() async {
    try {
      return await _auth.isDeviceSupported() && await _auth.canCheckBiometrics;
    } catch (_) {
      return false;
    }
  }

  /// 生物识别解锁；失败/不可用返回 false，由界面回退到 PIN。
  Future<bool> unlockWithBiometrics() async {
    if (!biometricEnabled || !hasPin || _authInProgress) return false;
    _authInProgress = true;
    try {
      final ok = await _auth.authenticate(localizedReason: '验证身份以打开私密空间', biometricOnly: true);
      if (ok) {
        _unlocked = true;
        _failed = 0;
        notifyListeners();
      }
      return ok;
    } catch (_) {
      return false;
    } finally {
      _authInProgress = false;
    }
  }

  void setBiometric(bool v) {
    biometricEnabled = v;
    _prefs.setBool(_bioKey, v);
    notifyListeners();
  }

  /// 修改 PIN 需已解锁。
  bool changePin(String pin) => _unlocked && setPin(pin);
}
