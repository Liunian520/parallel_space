import 'dart:async';

import 'package:flutter/material.dart';
import 'package:local_auth/local_auth.dart';
import 'package:provider/provider.dart';

import '../models/app_info.dart';
import '../models/clone_app.dart';
import '../state/clone_store.dart';
import '../state/private_space_store.dart';
import '../widgets/pin_pad.dart';
import '../widgets/text_input_dialog.dart';
import 'native_bridge.dart';

void showGlassSnack(BuildContext context, String msg, {SnackBarAction? action}) {
  final m = ScaffoldMessenger.maybeOf(context);
  if (m == null) return;
  m
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      behavior: SnackBarBehavior.floating,
      margin: const EdgeInsets.fromLTRB(20, 0, 20, 100),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      content: Text(msg),
      action: action,
    ));
}

// ---------------------------------------------------------------------------
// 桌面快捷方式
// ---------------------------------------------------------------------------

/// 等待系统“已添加到桌面”回执（PinResultReceiver → pinResult）。
class PinWatcher {
  PinWatcher._();
  static final Map<String, Completer<bool>> _waiting = {};

  static void notifyPinned(String sid) => _waiting.remove(sid)?.complete(true);

  /// 回执或轮询 getPinnedShortcuts 任一确认即成功；超时返回 false。
  static Future<bool> wait(String sid, {Duration timeout = const Duration(seconds: 20)}) async {
    final c = _waiting[sid] = Completer<bool>();
    final deadline = DateTime.now().add(timeout);
    unawaited(() async {
      while (!c.isCompleted && DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 1500));
        if (!c.isCompleted && await NativeBridge.isPinned(sid)) notifyPinned(sid);
      }
      if (!c.isCompleted) {
        _waiting.remove(sid);
        c.complete(false);
      }
    }());
    return c.future;
  }
}

/// 各 ROM 开启“桌面快捷方式”权限的路径。
String shortcutPermissionPath(String rom) => switch (rom) {
      'hyperos' || 'miui' => '设置 → 应用设置 → 应用管理 → 平行空间 → 权限管理 → 桌面快捷方式 → 允许',
      'harmony' || 'emui' || 'magicos' => '设置 → 应用和服务 → 权限管理 → 平行空间 → 创建桌面快捷方式 → 允许',
      'coloros' || 'oxygenos' || 'realmeui' => '设置 → 应用 → 应用管理 → 平行空间 → 权限 → 桌面快捷方式 → 允许',
      'originos' || 'funtouch' => 'i管家 → 应用管理 → 权限管理 → 平行空间 → 桌面快捷方式 → 允许',
      _ => '检查桌面启动器是否允许添加快捷方式（部分第三方启动器不支持）',
    };

bool _cnRom(String rom) => rom.isNotEmpty && rom != 'stock' && rom != 'oneui';

/// 添加到桌面（固定快捷方式，打开时直接在工作资料中启动该应用）。
Future<void> pinCloneToHome(BuildContext context, CloneApp c, {bool force = false}) async {
  if (c.isPrivate) {
    showGlassSnack(context, '私密空间中的分身不能添加到桌面，请先移出私密空间');
    return;
  }
  final label = c.customName;
  final r = await NativeBridge.pinShortcut(id: c.uid, pkg: c.packageName, label: label, force: force);
  if (!context.mounted) return;
  switch (r.result) {
    case 'exists':
      showGlassSnack(context, '「$label」已在桌面上，图标和名称已更新');
    case 'perm_denied':
      await _shortcutGuide(context, c, r.rom, denied: true);
    case 'no_profile':
      showGlassSnack(context, '分身引擎未就绪，无法添加快捷方式');
    case 'unsupported':
      if (_cnRom(r.rom)) {
        await _shortcutGuide(context, c, r.rom, denied: false);
      } else {
        showGlassSnack(context, '当前桌面启动器不支持固定快捷方式');
      }
    case 'requested':
      showGlassSnack(context, '请在系统弹窗中确认添加「$label」');
      final ok = await PinWatcher.wait(r.sid);
      if (!context.mounted) return;
      if (ok) {
        showGlassSnack(context, '已添加「$label」到桌面');
      } else if (_cnRom(r.rom)) {
        // 国产 ROM 在权限关闭时会静默丢弃请求：给出指引
        await _shortcutGuide(context, c, r.rom, denied: false);
      } else {
        showGlassSnack(context, '未检测到快捷方式，可能已取消或启动器不支持');
      }
    default:
      showGlassSnack(context, '添加到桌面失败，请稍后重试');
  }
}

Future<void> _shortcutGuide(BuildContext context, CloneApp c, String rom, {required bool denied}) async {
  final action = await showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      icon: const Icon(Icons.add_to_home_screen_rounded),
      title: Text(denied ? '需要“桌面快捷方式”权限' : '没有添加成功？'),
      content: Text(
        '${denied ? '系统禁止了平行空间创建桌面快捷方式。' : '系统没有确认添加，通常是“桌面快捷方式”权限未开启。'}\n\n'
        '开启路径：${shortcutPermissionPath(rom)}\n\n开启后返回这里再试一次；也可以尝试兼容方式。',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
        TextButton(onPressed: () => Navigator.pop(ctx, 'legacy'), child: const Text('兼容方式')),
        FilledButton(onPressed: () => Navigator.pop(ctx, 'perm'), child: const Text('去开启')),
      ],
    ),
  );
  if (!context.mounted) return;
  if (action == 'perm') {
    final r = await NativeBridge.openShortcutPermission();
    if (context.mounted && r == 'details') showGlassSnack(context, '已打开应用详情，请在“权限”中开启桌面快捷方式');
    if (context.mounted && r == 'error') showGlassSnack(context, '无法打开系统设置');
  } else if (action == 'legacy') {
    final ok = await NativeBridge.pinLegacy(id: c.uid, pkg: c.packageName, label: c.customName);
    if (context.mounted) showGlassSnack(context, ok ? '已发送添加请求，请回到桌面查看' : '兼容方式不可用');
  }
}

// ---------------------------------------------------------------------------
// 锁 / 打开
// ---------------------------------------------------------------------------

/// 分身隐私锁验证：指纹锁走系统生物识别（失败时可用设备锁屏密码），密码锁使用 App PIN。
Future<bool> verifyCloneLock(BuildContext context, CloneApp c) async {
  if (c.fingerprintLock) {
    try {
      final ok = await LocalAuthentication().authenticate(localizedReason: '验证身份以打开「${c.customName}」');
      if (!ok) return false;
    } catch (_) {
      if (!context.mounted) return false;
      showGlassSnack(context, '此设备无法使用生物识别，请改用密码锁');
      return false;
    }
  }
  if (c.passwordLock) {
    if (!context.mounted) return false;
    final priv = context.read<PrivateSpaceStore>();
    if (!priv.hasPin) {
      showGlassSnack(context, '请先在“设置 → 私密空间”中设置 PIN');
      return false;
    }
    final ok = await showPinDialog(context, title: '输入 PIN 打开「${c.customName}」', verify: priv.checkPin);
    if (!ok) return false;
  }
  return true;
}

String launchErrorText(String r, String name) => switch (r) {
      'not_found' => '工作资料中没有「$name」，且无法自动复制（个人空间中可能未安装）',
      'no_profile' => '平行空间的工作资料不存在',
      'paused' => '“工作应用”已关闭，已请求系统开启，请确认后重试',
      'suspended' => '「$name」处于冻结状态',
      _ => '启动失败，请稍后重试',
    };

/// 打开分身：优先虚拟引擎；引擎不可用时回退工作资料启动。
Future<void> openClone(NavigatorState nav, CloneApp c) async {
  final context = nav.context;
  if (c.isPrivate && !await _unlockPrivate(context)) return;
  if (!context.mounted) return;
  if (c.locked && !await verifyCloneLock(context, c)) return;
  if (!context.mounted) return;
  final store = context.read<CloneStore>();
  final vs = await NativeBridge.virtualStatus();
  if (vs['available'] == true) {
    final m = await NativeBridge.launchVirtual(c.packageName, userId: c.userId);
    final r = m['r'] as String? ?? 'error';
    if (!context.mounted) return;
    if (r == 'ok') {
      store.markOpened(c);
    } else if (r == 'not_found') {
      showGlassSnack(context, '分身不存在，请重新添加');
      unawaited(store.refresh());
    } else {
      showGlassSnack(context, '启动失败：${m['detail'] ?? r}');
    }
    return;
  }
  // 遗留：工作资料路径
  var r = await NativeBridge.launchWorkApp(c.packageName);
  if (!context.mounted) return;
  if (r == 'not_found') {
    final copy = await NativeBridge.cloneIntoWork(c.packageName);
    if (!context.mounted) return;
    if (copy == 'ok') {
      await _waitInWork(c.packageName);
      if (!context.mounted) return;
      r = await NativeBridge.launchWorkApp(c.packageName);
    } else {
      showGlassSnack(context, cloneErrorText(copy));
      return;
    }
  }
  if (!context.mounted) return;
  if (r == 'ok') {
    store.markOpened(c);
  } else {
    showGlassSnack(context, launchErrorText(r, c.customName));
  }
}

/// installExistingPackage 之后系统需要片刻才能在 LauncherApps 中看到应用。
Future<bool> _waitInWork(String pkg) async {
  for (var i = 0; i < 6; i++) {
    if (await NativeBridge.isInWork(pkg)) return true;
    await Future<void>.delayed(const Duration(milliseconds: 500));
  }
  return false;
}

/// 私密分身（例如从桌面快捷方式进入）需要先解锁私密空间。
Future<bool> _unlockPrivate(BuildContext context) async {
  final priv = context.read<PrivateSpaceStore>();
  if (priv.unlocked) return true;
  if (!priv.hasPin) return true; // 未设置 PIN 时不存在私密分身的锁（导入数据的兜底）
  if (priv.biometricEnabled && await priv.unlockWithBiometrics()) return true;
  if (!context.mounted) return false;
  return showPinDialog(context, title: '输入 PIN 解锁私密空间', verify: priv.unlockWithPin);
}

String _suspendMsg(String r) => switch (r) {
      'not_owner' => '需要由平行空间创建的工作资料',
      'no_profile' => '工作资料不可用',
      'unsupported' => '当前系统不支持',
      _ => '系统拒绝了操作',
    };

/// 冻结/解冻：DevicePolicyManager.setPackagesSuspended（由工作资料中的资料所有者执行）。
Future<bool> setCloneFrozen(BuildContext context, CloneApp c, bool frozen, {bool quiet = false}) async {
  final store = context.read<CloneStore>();
  final r = await NativeBridge.setWorkAppSuspended(c.packageName, frozen);
  if (r != 'ok') {
    if (!quiet && context.mounted) showGlassSnack(context, '${frozen ? '冻结' : '解冻'}失败：${_suspendMsg(r)}');
    return false;
  }
  store.update(c, (c) {
    c.frozen = frozen;
    if (!frozen) c.lastActiveMs = DateTime.now().millisecondsSinceEpoch;
  });
  return true;
}

/// 改名：只影响这个分身实例；桌面快捷方式、最近任务、工具箱同步使用新名字。
Future<void> renameClone(BuildContext context, CloneApp c) async {
  final store = context.read<CloneStore>();
  final v = await showTextInputDialog(context,
      title: '分身名称', initial: c.customLabel ?? '', hint: c.defaultName, helper: '留空则用「${c.defaultName}」', maxLength: 20);
  if (v == null || !context.mounted) return;
  store.update(c, (c) => c.customLabel = v.trim().isEmpty ? null : v.trim());
  showGlassSnack(context, '已改名为「${c.customName}」，桌面图标与最近任务会同步更新');
}

/// 再分身一个：在新的虚拟用户里再装一份，数据与已有分身完全独立。
Future<CloneApp?> cloneAnotherInstance(BuildContext context, CloneApp base) async {
  final store = context.read<CloneStore>();
  final vs = await NativeBridge.virtualStatus();
  if (!context.mounted) return null;
  if (vs['available'] != true) {
    showGlassSnack(context, '多开需要虚拟引擎，当前引擎未就绪');
    return null;
  }
  showGlassSnack(context, '正在创建「${base.appName}」的新分身…');
  final m = await NativeBridge.cloneVirtual(base.packageName, userId: -1);
  if (!context.mounted) return null;
  final r = m['r'] as String? ?? 'error';
  final u = m['userId'];
  if (r != 'ok' || u is! num) {
    showGlassSnack(context, cloneErrorText(r));
    return null;
  }
  store.addFromApp(AppInfo(
    packageName: base.packageName,
    name: base.appName,
    version: base.version,
    category: base.category,
    userId: u.toInt(),
  ));
  final c = store.byInstance(base.packageName, u.toInt());
  if (c != null) showGlassSnack(context, '已创建「${c.customName}」，可在菜单里改名');
  return c;
}

/// 创建分身结果：状态码 + 虚拟用户 id（工作资料路径恒为 0）。
class CloneResult {
  const CloneResult(this.code, {this.userId = 0});
  final String code;
  final int userId;
  bool get ok => code == 'ok';
}

/// 创建分身：优先虚拟引擎；引擎不可用时回退工作资料 installExisting。
/// [userId] < 0 表示新开一个实例（与「再分身一个」相同）。
Future<String> cloneAppIntoWork(String pkg, {int userId = 0}) async =>
    (await cloneAppIntoWorkEx(pkg, userId: userId)).code;

Future<CloneResult> cloneAppIntoWorkEx(String pkg, {int userId = 0}) async {
  final vs = await NativeBridge.virtualStatus();
  if (vs['available'] == true) {
    final m = await NativeBridge.cloneVirtual(pkg, userId: userId);
    final r = m['r'] as String? ?? 'error';
    final u = m['userId'];
    return CloneResult(r, userId: u is num ? u.toInt() : (userId < 0 ? 0 : userId));
  }
  final r = await NativeBridge.cloneIntoWork(pkg);
  return CloneResult(r);
}

String cloneErrorText(String r) => switch (r) {
      'engine_unavailable' => '虚拟引擎未就绪，无法创建分身',
      'rejected' => '系统拒绝了静默复制（或该应用无法虚拟化）',
      'unsupported' => '当前设备不支持此分身方式',
      'not_owner' => '需要由平行空间创建的工作资料',
      'no_profile' => '工作资料不存在',
      'need_permission' || 'need_setup' || 'relinking' => '需要完成跨空间授权 / 初始化后再试',
      _ => '复制失败：$r',
    };
