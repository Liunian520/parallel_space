import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/clone_app.dart';
import '../services/clone_actions.dart';
import '../state/clone_store.dart';
import '../state/private_space_store.dart';
import '../widgets/app_icon.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';
import '../widgets/pin_pad.dart';
import '../widgets/section_tile.dart';
import 'clone_detail_screen.dart';

/// 私密空间：PIN + 生物识别解锁；离开页面或 App 进入后台自动上锁。
class PrivateSpaceScreen extends StatefulWidget {
  const PrivateSpaceScreen({super.key});

  @override
  State<PrivateSpaceScreen> createState() => _PrivateSpaceScreenState();
}

class _PrivateSpaceScreenState extends State<PrivateSpaceScreen> {
  String? _error;
  String? _firstPin; // 设置 PIN 时第一次输入
  bool _bioAvailable = false;
  final _pad = GlobalKey<PinPadState>();

  @override
  void initState() {
    super.initState();
    final priv = context.read<PrivateSpaceStore>();
    priv.canUseBiometrics().then((v) {
      if (mounted) setState(() => _bioAvailable = v);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && priv.hasPin && !priv.unlocked && priv.biometricEnabled) priv.unlockWithBiometrics();
    });
  }

  void _onSetup(PrivateSpaceStore priv, String pin) {
    if (_firstPin == null) {
      setState(() {
        _firstPin = pin;
        _error = null;
      });
    } else if (_firstPin == pin) {
      priv.setPin(pin);
      setState(() => _firstPin = null);
      showGlassSnack(context, 'PIN 已设置，私密空间已开启');
    } else {
      setState(() {
        _firstPin = null;
        _error = '两次输入不一致，请重新设置';
      });
    }
  }

  void _onUnlock(PrivateSpaceStore priv, String pin) {
    final r = priv.unlockWithPin(pin);
    setState(() {
      _error = switch (r) {
        'ok' => null,
        'cooldown' => '尝试次数过多，请 ${priv.cooldownLeft?.inSeconds ?? 30} 秒后再试',
        _ => 'PIN 不正确，还可尝试 ${priv.attemptsLeft} 次',
      };
    });
  }

  Future<void> _changePin(PrivateSpaceStore priv) async {
    String? first;
    final ok = await showPinDialog(context, title: '输入新 PIN（4-6 位）', verify: (v) {
      first = v;
      return 'ok';
    });
    if (!ok || first == null || !mounted) return;
    final ok2 = await showPinDialog(context, title: '再次输入新 PIN', verify: (v) => v == first ? 'ok' : 'wrong');
    if (!ok2 || !mounted) return;
    priv.changePin(first!);
    showGlassSnack(context, 'PIN 已更新');
  }

  void _addClones(CloneStore store) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => ListenableBuilder(
        listenable: store,
        builder: (ctx, _) {
          final list = store.visibleClones;
          return GlassSheet(
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('移入私密空间', style: Theme.of(ctx).textTheme.titleLarge),
              const SizedBox(height: 4),
              Text('移入后不再显示在主屏、桌面小组件中', style: Theme.of(ctx).textTheme.bodySmall),
              const SizedBox(height: 12),
              if (list.isEmpty) const Padding(padding: EdgeInsets.all(20), child: Center(child: Text('主屏没有可移入的分身'))),
              for (final c in list)
                ListTile(
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                  leading: AppIcon(packageName: c.packageName, label: c.customName, size: 40),
                  title: Text(c.customName, maxLines: 1, overflow: TextOverflow.ellipsis),
                  trailing: const Icon(Icons.lock_outline_rounded),
                  onTap: () => store.update(c, (c) => c.isPrivate = true),
                ),
            ]),
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final priv = context.watch<PrivateSpaceStore>();
    final store = context.watch<CloneStore>();
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;

    final Widget body;
    if (!priv.hasPin) {
      body = _lockView(
        icon: Icons.shield_moon_rounded,
        title: _firstPin == null ? '设置私密空间 PIN' : '再次输入 PIN',
        subtitle: '4-6 位数字。PIN 仅以加盐哈希保存在本机，不会被备份导出。',
        pad: PinPad(key: _pad, error: _error, onSubmit: (v) => _onSetup(priv, v)),
      );
    } else if (!priv.unlocked) {
      body = _lockView(
        icon: Icons.lock_rounded,
        title: '私密空间已锁定',
        subtitle: priv.biometricEnabled ? '使用指纹 / 面容，或输入 PIN' : '输入 PIN 解锁',
        pad: PinPad(key: _pad, error: _error, onSubmit: (v) => _onUnlock(priv, v)),
        extra: priv.biometricEnabled
            ? TextButton.icon(
                onPressed: () async {
                  final ok = await priv.unlockWithBiometrics();
                  if (!ok && mounted) setState(() => _error = '生物识别未通过，请输入 PIN');
                },
                icon: const Icon(Icons.fingerprint_rounded),
                label: const Text('使用生物识别'),
              )
            : null,
      );
    } else {
      final list = store.privateClones;
      body = ListView(
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
        children: [
          SectionCard(
            title: '私密分身 · ${list.length}',
            children: [
              if (list.isEmpty)
                const GlassTile(icon: Icons.inbox_rounded, title: '还没有私密分身', subtitle: '点击下方按钮，把主屏的分身移进来'),
              for (final c in list) _privateTile(store, c),
              GlassTile(icon: Icons.add_rounded, title: '移入分身', onTap: () => _addClones(store)),
            ],
          ),
          SectionCard(title: '安全', children: [
            GlassTile(
              icon: Icons.fingerprint_rounded,
              title: '生物识别解锁',
              subtitle: _bioAvailable ? '指纹 / 面容，失败时可用 PIN' : '此设备未录入或不支持生物识别',
              trailing: Switch(value: priv.biometricEnabled && _bioAvailable, onChanged: _bioAvailable ? priv.setBiometric : null),
            ),
            GlassTile(icon: Icons.password_rounded, title: '修改 PIN', onTap: () => _changePin(priv)),
            GlassTile(
              icon: Icons.lock_clock_rounded,
              title: '立即锁定',
              subtitle: '离开本页或 App 进入后台时也会自动锁定',
              onTap: priv.lock,
            ),
          ]),
        ],
      );
    }

    return PopScope(
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) priv.lock();
      },
      child: Scaffold(
        body: BlobBackground(
          child: SafeArea(
            child: Column(children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(4, 4, 16, 0),
                child: Row(children: [
                  const BackButton(),
                  Expanded(child: Text('私密空间', style: tt.titleLarge)),
                  if (priv.unlocked) Icon(Icons.lock_open_rounded, color: s.tertiary),
                ]),
              ),
              Expanded(
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 300),
                  child: KeyedSubtree(key: ValueKey('${priv.hasPin}-${priv.unlocked}'), child: body),
                ),
              ),
            ]),
          ),
        ),
      ),
    );
  }

  Widget _lockView({required IconData icon, required String title, required String subtitle, required Widget pad, Widget? extra}) {
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Glass(
          padding: const EdgeInsets.all(24),
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Icon(icon, size: 48, color: s.primary),
            const SizedBox(height: 12),
            Text(title, style: tt.titleLarge, textAlign: TextAlign.center),
            const SizedBox(height: 6),
            Text(subtitle, textAlign: TextAlign.center, style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
            const SizedBox(height: 20),
            pad,
            ?extra,
          ]),
        ),
      ),
    );
  }

  Widget _privateTile(CloneStore store, CloneApp c) {
    return ListTile(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      leading: AppIcon(packageName: c.packageName, label: c.customName, size: 42),
      title: Text(c.customName, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
      subtitle: Text(c.frozen ? '已冻结' : (c.version.isEmpty ? '分身' : 'v${c.version}')),
      onTap: () => Navigator.of(context).push(CupertinoPageRoute(builder: (_) => CloneDetailScreen(uid: c.uid))),
      trailing: Row(mainAxisSize: MainAxisSize.min, children: [
        IconButton(tooltip: '打开', onPressed: () => openClone(Navigator.of(context), c), icon: const Icon(Icons.open_in_new_rounded)),
        IconButton(
          tooltip: '移出私密空间',
          onPressed: () => store.update(c, (c) => c.isPrivate = false),
          icon: const Icon(Icons.lock_open_rounded),
        ),
      ]),
    );
  }
}
