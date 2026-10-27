import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../config/app_version.dart';
import '../services/backup_service.dart';
import '../services/update_service.dart';
import '../widgets/update_sheet.dart';
import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import '../state/settings_store.dart';
import '../widgets/blob_background.dart';
import '../widgets/pressable.dart';
import '../widgets/section_tile.dart';
import 'compat_screen.dart';
import 'private_space_screen.dart';
import 'toolbox_screen.dart';
import 'usage_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key, this.embedded = false});
  final bool embedded;

  void _push(BuildContext context, Widget page) => Navigator.of(context).push(CupertinoPageRoute(builder: (_) => page));

  @override
  Widget build(BuildContext context) {
    final st = context.watch<SettingsStore>();
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;

    final body = SafeArea(
      bottom: false,
      child: ListView(
        physics: const BouncingScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(20, 20, 20, 140),
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: Text('设置', style: tt.headlineMedium),
          ),
          SectionCard(title: '分身空间', children: [
            ..._spaceTiles(context),
            GlassTile(
              icon: Icons.lock_person_rounded,
              title: '私密空间',
              subtitle: '指纹 / PIN 解锁，后台自动上锁',
              onTap: () => _push(context, const PrivateSpaceScreen()),
            ),
          ]),
          SectionCard(title: '工具箱', children: [
            GlassTile(
              icon: Icons.widgets_rounded,
              title: '工具箱',
              subtitle: '分身运行时的悬浮小窗，第一项是账号密码本',
              trailing: Switch(
                value: st.toolboxOverlay,
                onChanged: (v) => applyToolboxEnabled(context, v),
              ),
              onTap: () => _push(context, const ToolboxScreen()),
            ),
            GlassTile(
              icon: Icons.bug_report_rounded,
              title: '工具箱诊断日志',
              subtitle: '悬浮窗消失时可查看、复制或分享最近日志',
              onTap: () => _push(context, const ToolboxCrashLogScreen()),
            ),
          ]),
          SectionCard(title: '系统适配', children: [
            GlassTile(
              icon: Icons.phonelink_setup_rounded,
              title: '机型适配检查',
              subtitle: '悬浮窗、无障碍、电池、快捷方式和系统版本',
              onTap: () => _push(context, const CompatScreen()),
            ),
            GlassTile(
              icon: Icons.add_to_home_screen_rounded,
              title: '桌面快捷方式权限',
              subtitle: '小米、华为、荣耀、OPPO、vivo 等需允许后才能添加分身到桌面',
              onTap: () => NativeBridge.openShortcutPermission(),
            ),
            GlassTile(
              icon: Icons.rocket_launch_rounded,
              title: '自启动与后台运行',
              subtitle: '允许后，虚拟定位与自动冻结在后台更稳定',
              onTap: () => NativeBridge.openAutoStart(),
            ),
            GlassTile(
              icon: Icons.battery_saver_rounded,
              title: '忽略电池优化',
              subtitle: '防止系统在后台结束虚拟定位服务',
              onTap: () async {
                final r = await NativeBridge.requestIgnoreBattery();
                if (context.mounted && r == 'ok') showGlassSnack(context, '已忽略电池优化');
              },
            ),
          ]),
          SectionCard(title: '显示与性能', children: [
            GlassTile(
              icon: Icons.speed_rounded,
              title: '精简特效',
              subtitle: '关闭实时模糊与背景动画，更流畅、更省电（推荐）',
              trailing: Switch(value: st.reduceEffects, onChanged: (v) => st.set((s) => s.reduceEffects = v)),
            ),
          ]),
          SectionCard(title: '省电与用量', children: [
            GlassTile(
              icon: Icons.query_stats_rounded,
              title: '今日应用用量',
              subtitle: '需要“使用情况访问权限”',
              onTap: () => _push(context, const UsageScreen()),
            ),
          ]),
          SectionCard(title: '备份与迁移', children: [
            GlassTile(
              icon: Icons.save_alt_rounded,
              title: '导出到文件',
              subtitle: '分身、收藏位置、自建路线、设置（不含 PIN）',
              onTap: () => exportBackup(context, share: false),
            ),
            GlassTile(icon: Icons.share_rounded, title: '分享备份', onTap: () => exportBackup(context, share: true)),
            GlassTile(
              icon: Icons.settings_backup_restore_rounded,
              title: '导入备份',
              subtitle: '校验文件后可选择合并或覆盖',
              onTap: () => importBackup(context),
            ),
          ]),
          const SectionCard(title: '桌面与快捷开关', children: [
            GlassTile(
              icon: Icons.widgets_rounded,
              title: '桌面小组件',
              subtitle: '长按桌面 → 小组件 → 平行空间：显示主屏前 4 个分身，点击直接打开',
            ),
            GlassTile(
              icon: Icons.toggle_on_rounded,
              title: '快捷设置开关',
              subtitle: '下拉通知栏 → 编辑 → 拖入“虚拟定位”，一键开关上次的模拟位置',
            ),
          ]),
          SectionCard(title: '主题', children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
              child: SizedBox(
                width: double.infinity,
                child: SegmentedButton<ThemeMode>(
                  segments: const [
                    ButtonSegment(value: ThemeMode.system, icon: Icon(Icons.brightness_auto_rounded), label: FittedBox(child: Text('跟随系统'))),
                    ButtonSegment(value: ThemeMode.light, icon: Icon(Icons.light_mode_rounded), label: Text('浅色')),
                    ButtonSegment(value: ThemeMode.dark, icon: Icon(Icons.dark_mode_rounded), label: Text('深色')),
                  ],
                  selected: {st.themeMode},
                  onSelectionChanged: (v) => st.set((s) => s.themeMode = v.first),
                ),
              ),
            ),
            GlassTile(
              icon: Icons.wallpaper_rounded,
              title: '动态取色',
              subtitle: '跟随系统壁纸颜色（Android 12+）',
              trailing: Switch(value: st.useDynamicColor, onChanged: (v) => st.set((s) => s.useDynamicColor = v)),
            ),
            AnimatedOpacity(
              duration: const Duration(milliseconds: 250),
              opacity: st.useDynamicColor ? 0.4 : 1,
              child: IgnorePointer(
                ignoring: st.useDynamicColor,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 6, 16, 14),
                  child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                    for (final c in SettingsStore.seedOptions)
                      Pressable(
                        radius: 22,
                        onTap: () => st.set((s) => s.seed = c),
                        child: AnimatedContainer(
                          duration: const Duration(milliseconds: 250),
                          curve: Curves.easeOutBack,
                          width: st.seed == c ? 44 : 36,
                          height: st.seed == c ? 44 : 36,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: LinearGradient(colors: [c, Color.lerp(c, const Color(0xFF00E5FF), 0.5)!]),
                            border: Border.all(color: st.seed == c ? s.onSurface : Colors.transparent, width: 3),
                          ),
                          child: st.seed == c ? const Icon(Icons.check_rounded, color: Colors.white, size: 20) : null,
                        ),
                      ),
                  ]),
                ),
              ),
            ),
          ]),
          SectionCard(title: '分身引擎', children: [
            Builder(builder: (context) {
              final store = context.watch<CloneStore>();
              final ok = store.engineAvailable;
              return GlassTile(
                icon: ok ? Icons.memory_rounded : Icons.warning_amber_rounded,
                title: '虚拟引擎',
                subtitle: ok
                    ? '${store.engineName.isEmpty ? 'NewBlackbox/Bcore' : store.engineName} · 可用'
                    : '不可用${store.engineError == null || store.engineError!.isEmpty ? '' : ' · ${store.engineError}'}',
              );
            }),
          ]),
          SectionCard(title: '更新', children: [
            Builder(builder: (context) {
              final st = context.watch<SettingsStore>();
              return GlassTile(
                icon: Icons.system_update_rounded,
                title: '自动检查更新',
                subtitle: '最多每 12 小时一次',
                trailing: Switch.adaptive(
                  value: st.autoCheckUpdate,
                  onChanged: (v) {
                    st.set((s) => s.autoCheckUpdate = v);
                    context.read<UpdateService>().autoCheck = v;
                  },
                ),
              );
            }),
            GlassTile(
              icon: Icons.upgrade_rounded,
              title: '检查更新',
              subtitle: '当前版本 $kAppVersionName（build $kAppVersionCode）',
              onTap: () => checkForUpdates(context, force: true),
            ),
          ]),
          SectionCard(title: '关于', children: [
            GlassTile(
              icon: Icons.info_outline_rounded,
              title: '关于平行空间',
              subtitle: '版本 $kAppVersionName · 虚拟引擎分身',
              onTap: () => showAboutDialog(
                context: context,
                applicationName: '平行空间',
                applicationVersion: kAppVersionName,
                applicationIcon: const Icon(Icons.layers_rounded, size: 40),
                children: const [
                  Text('分身由进程内虚拟引擎（NewBlackbox/Bcore）运行，不依赖系统工作资料。'
                      '部分应用在 HyperOS 上可能无法虚拟化，失败时会给出明确提示。'
                      '虚拟定位通过系统模拟位置接口实现，对整台设备生效。'),
                ],
              ),
            ),
            const GlassTile(icon: Icons.shield_outlined, title: '隐私政策', subtitle: '所有数据仅存储于本机，不上传任何服务器'),
          ]),
          const _OpenSourceFooter(),
        ],
      ),
    );

    return embedded ? body : Scaffold(body: BlobBackground(child: body));
  }

  static bool _leftoverProfile(WorkState s) =>
      s == WorkState.ready || s == WorkState.paused || s == WorkState.foreign || s == WorkState.inside;

  static List<Widget> _spaceTiles(BuildContext context) {
    final store = context.watch<CloneStore>();
    final ws = store.status;
    if (store.engineAvailable) {
      return [
        const GlassTile(
          icon: Icons.layers_rounded,
          title: '虚拟分身引擎',
          subtitle: '已就绪 · 分身不依赖工作资料',
        ),
        if (_leftoverProfile(ws.state))
          GlassTile(
            icon: Icons.delete_forever_rounded,
            title: '删除残留工作资料',
            subtitle: '早期版本留在系统里的工作资料，不影响当前虚拟分身',
            danger: true,
            onTap: () => confirmWipeWorkProfile(context, leftover: true),
          ),
      ];
    }
    return [
      GlassTile(
        icon: Icons.work_rounded,
        title: '工作资料',
        subtitle: '${_stateText(ws.state)}${ws.romLabel.isEmpty ? '' : ' · ${ws.romLabel}'}',
        trailing: IconButton(tooltip: '重新检测', onPressed: store.refresh, icon: const Icon(Icons.refresh_rounded)),
      ),
      if (ws.state == WorkState.ready || ws.state == WorkState.paused)
        GlassTile(
          icon: Icons.delete_forever_rounded,
          title: '删除工作资料',
          subtitle: '删除全部分身及其数据（不可恢复）',
          danger: true,
          onTap: () => confirmWipeWorkProfile(context),
        ),
    ];
  }

  static String _stateText(WorkState s) => switch (s) {
        WorkState.ready => '已就绪',
        WorkState.paused => '工作应用已关闭',
        WorkState.none => '尚未创建',
        WorkState.foreign => '已存在其他工作资料',
        WorkState.unsupported => '设备不支持',
        WorkState.inside => '当前在工作资料内',
        WorkState.unknown => '检测中',
      };
}

class _OpenSourceFooter extends StatelessWidget {
  const _OpenSourceFooter();

  static const _url = 'https://github.com/Liunian520/parallel_space';

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final tt = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 28, 8, 12),
      child: Column(children: [
        Text('本项目开源', style: tt.titleMedium),
        const SizedBox(height: 4),
        Text('作者 流年似水', style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant)),
        const SizedBox(height: 6),
        InkWell(
          onTap: () async {
            final r = await NativeBridge.openUrl(_url);
            if (context.mounted && r != 'ok') showGlassSnack(context, '无法打开链接');
          },
          child: Text(
            _url,
            textAlign: TextAlign.center,
            style: tt.bodyMedium?.copyWith(color: s.primary, decoration: TextDecoration.underline),
          ),
        ),
      ]),
    );
  }
}


Future<bool> _ask(BuildContext context, String title, String body, String ok) async =>
    await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Theme.of(ctx).colorScheme.error),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(ok),
          ),
        ],
      ),
    ) ??
    false;

/// 删除工作资料：两次确认后由资料内的资料所有者执行 wipeData；不可用时打开系统设置。
/// [leftover] 为真时，这是虚拟引擎已接管后的清理，不会动当前虚拟分身。
Future<void> confirmWipeWorkProfile(BuildContext context, {bool leftover = false}) async {
  final body = leftover
      ? '只删除系统里残留的工作资料。平行空间里用虚拟引擎创建的分身和个人空间中的应用都不受影响。'
      : '工作资料中的所有分身、登录状态和数据都会被永久删除，个人空间中的应用不受影响。';
  if (!await _ask(context, '删除工作资料？', body, '继续') || !context.mounted) {
    return;
  }
  if (!await _ask(context, '再次确认', '此操作不可恢复。确定删除工作资料吗？', '永久删除') || !context.mounted) return;
  final store = context.read<CloneStore>();
  final r = await NativeBridge.wipeWorkProfile();
  if (!context.mounted) return;
  showGlassSnack(context, switch (r) {
    'ok' || 'pending' => '工作资料正在删除…',
    'settings' => '请在系统设置中删除工作资料',
    'no_profile' => '没有可删除的工作资料',
    _ => '删除失败，请在系统设置中删除',
  });
  for (var i = 0; i < 5; i++) {
    await Future<void>.delayed(const Duration(seconds: 1));
    await store.refresh();
    if (store.status.state != WorkState.ready && store.status.state != WorkState.paused) break;
  }
}
