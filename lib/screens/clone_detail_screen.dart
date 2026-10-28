import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/app_info.dart';
import '../models/clone_app.dart';
import '../services/clone_actions.dart';
import '../state/clone_store.dart';
import '../state/private_space_store.dart';
import '../widgets/app_icon.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';
import '../widgets/location_section.dart';
import '../widgets/section_tile.dart';
import 'home_screen.dart';
import 'vault_screen.dart';

class CloneDetailScreen extends StatelessWidget {
  const CloneDetailScreen({super.key, required this.uid});
  final String uid;

  static const _freezeOptions = [0, 5, 15, 30, 60, 180];

  Future<void> _rename(BuildContext context, CloneStore store, CloneApp c) => renameClone(context, c);

  Future<void> _toggleFingerprint(BuildContext context, CloneStore store, CloneApp c, bool v) async {
    if (v) {
      final ok = await context.read<PrivateSpaceStore>().canUseBiometrics();
      if (!context.mounted) return;
      if (!ok) {
        showGlassSnack(context, '此设备未录入指纹/面容或不支持生物识别');
        return;
      }
    }
    store.update(c, (c) => c.fingerprintLock = v);
  }

  void _togglePassword(BuildContext context, CloneStore store, CloneApp c, bool v) {
    if (v && !context.read<PrivateSpaceStore>().hasPin) {
      showGlassSnack(context, '请先在私密空间中设置 PIN，密码锁使用同一个 PIN');
      return;
    }
    store.update(c, (c) => c.passwordLock = v);
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<CloneStore>();
    final priv = context.watch<PrivateSpaceStore>();
    final c = store.byId(uid);
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;

    if (c == null) return const Scaffold(body: Center(child: Text('分身不存在')));
    // 私密分身：私密空间被自动锁定后（例如 App 进入后台），详情页同步隐藏。
    if (c.isPrivate && !priv.unlocked && priv.hasPin) {
      return Scaffold(
        body: BlobBackground(
          child: Center(
            child: Column(mainAxisSize: MainAxisSize.min, children: [
              Icon(Icons.lock_rounded, size: 48, color: s.primary),
              const SizedBox(height: 12),
              Text('私密空间已锁定', style: tt.titleLarge),
              const SizedBox(height: 16),
              FilledButton(onPressed: () => Navigator.of(context).maybePop(), child: const Text('返回')),
            ]),
          ),
        ),
      );
    }
    return Scaffold(
      body: BlobBackground(
        child: CustomScrollView(physics: const BouncingScrollPhysics(), slivers: [
          SliverAppBar(backgroundColor: Colors.transparent, surfaceTintColor: Colors.transparent, title: const Text('分身详情')),
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
            sliver: SliverList.list(children: [
              Glass(
                padding: const EdgeInsets.all(24),
                child: Column(children: [
                  AppIcon(packageName: c.packageName, label: c.customName, size: 88),
                  const SizedBox(height: 16),
                  Text(c.customName,
                      maxLines: 2, textAlign: TextAlign.center, style: tt.headlineSmall?.copyWith(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 4),
                  Text(
                    [if (c.customName != c.appName) c.appName, if (c.userId > 0) '实例 ${c.userId + 1}', if (c.version.isNotEmpty) 'v${c.version}', c.category.label].join(' · '),
                    textAlign: TextAlign.center,
                    style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant),
                  ),
                  const SizedBox(height: 4),
                  Text(c.packageName, style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant)),
                  const SizedBox(height: 16),
                  Wrap(alignment: WrapAlignment.center, spacing: 12, runSpacing: 10, children: [
                    FilledButton.icon(
                      onPressed: () => openClone(Navigator.of(context), c),
                      icon: const Icon(Icons.open_in_new_rounded),
                      label: const Text('打开'),
                    ),
                    FilledButton.tonalIcon(
                      onPressed: () => _rename(context, store, c),
                      icon: const Icon(Icons.edit_rounded),
                      label: const Text('改名'),
                    ),
                    FilledButton.tonalIcon(
                      onPressed: () async {
                        final n = await cloneAnotherInstance(context, c);
                        if (n != null && context.mounted) {
                          unawaited(Navigator.of(context)
                              .pushReplacement(CupertinoPageRoute(builder: (_) => CloneDetailScreen(uid: n.uid))));
                        }
                      },
                      icon: const Icon(Icons.library_add_rounded),
                      label: const Text('再分身一个'),
                    ),
                    FilledButton.tonalIcon(
                      onPressed: () => pinCloneToHome(context, c),
                      icon: const Icon(Icons.add_to_home_screen_rounded),
                      label: const Text('添加到桌面'),
                    ),
                  ]),
                ]),
              ),
              SectionCard(title: '隐私', children: [
                GlassTile(
                  icon: Icons.fingerprint_rounded,
                  title: '指纹解锁',
                  subtitle: '打开分身前通过系统生物识别验证',
                  trailing: Switch(value: c.fingerprintLock, onChanged: (v) => _toggleFingerprint(context, store, c, v)),
                ),
                GlassTile(
                  icon: Icons.password_rounded,
                  title: '密码锁',
                  subtitle: '打开前输入 App PIN（与私密空间相同）',
                  trailing: Switch(value: c.passwordLock, onChanged: (v) => _togglePassword(context, store, c, v)),
                ),
                GlassTile(
                  icon: Icons.lock_person_rounded,
                  title: '放入私密空间',
                  subtitle: priv.hasPin ? '从主屏和桌面小组件中隐藏' : '需先在私密空间中设置 PIN',
                  trailing: Switch(
                    value: c.isPrivate,
                    onChanged: (v) {
                      if (v && !priv.hasPin) {
                        showGlassSnack(context, '请先打开主屏右上角的私密空间并设置 PIN');
                        return;
                      }
                      store.update(c, (c) => c.isPrivate = v);
                    },
                  ),
                ),
              ]),
              LocationSection(clone: c),
              SectionCard(title: '省电', children: [
                GlassTile(
                  icon: Icons.ac_unit_rounded,
                  title: '冻结后台',
                  subtitle: '由系统暂停该应用：不能运行、不在后台耗电，打开分身时自动解冻',
                  trailing: Switch(value: c.frozen, onChanged: (v) => setCloneFrozen(context, c, v)),
                ),
                GlassTile(
                  icon: Icons.timer_outlined,
                  title: '自动冻结',
                  subtitle: c.autoFreezeMin == 0 ? '关闭' : '闲置 ${_fmtMin(c.autoFreezeMin)} 后自动冻结',
                  trailing: PopupMenuButton<int>(
                    tooltip: '选择时间',
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                    initialValue: c.autoFreezeMin,
                    onSelected: (v) => store.update(c, (c) {
                      c.autoFreezeMin = v;
                      c.lastActiveMs = DateTime.now().millisecondsSinceEpoch;
                    }),
                    itemBuilder: (_) => [
                      for (final m in _freezeOptions) PopupMenuItem(value: m, child: Text(m == 0 ? '关闭' : _fmtMin(m))),
                    ],
                    child: const Padding(padding: EdgeInsets.all(8), child: Icon(Icons.expand_more_rounded)),
                  ),
                ),
                GlassTile(
                    icon: Icons.query_stats_rounded,
                    title: '用量统计',
                    subtitle: store.engineAvailable
                        ? '这里只统计个人空间中的原应用，不包含分身'
                        : '工作资料与个人空间由系统隔离，无法从这里读取该分身的用量',
                  ),
              ]),
              SectionCard(title: '密码本', children: [
                GlassTile(
                  icon: Icons.key_rounded,
                  title: '管理此分身账号',
                  subtitle: '添加或编辑该分身的登录账号，工具箱可一键填入',
                  onTap: () => openVaultScreen(context, initialPackage: c.packageName, openEditor: true),
                ),
              ]),
              SectionCard(title: '管理', children: [
                GlassTile(
                  icon: Icons.delete_forever_rounded,
                  title: '移除分身',
                  subtitle: store.engineAvailable
                      ? '从平行空间删除这份分身，不影响个人空间中的原应用'
                      : '隐藏工作资料中的这份应用，数据保留；不影响个人空间中的原应用',
                  danger: true,
                  onTap: () async {
                    if (!await confirmDelete(context, c) || !context.mounted) return;
                    if (await deleteClone(context, c) && context.mounted) Navigator.of(context).pop();
                  },
                ),
              ]),
            ]),
          ),
        ]),
      ),
    );
  }

  static String _fmtMin(int m) => m >= 60 ? '${m ~/ 60} 小时' : '$m 分钟';
}
