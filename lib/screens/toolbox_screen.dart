import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/settings_store.dart';
import '../widgets/blob_background.dart';
import '../widgets/section_tile.dart';
import 'compat_screen.dart';
import 'vault_screen.dart';

String _openMessage(String raw) {
  final code = NativeBridge.compatOpenCode(raw);
  final hint = NativeBridge.compatOpenHint(raw);
  return switch (code) {
    'rom' => '已打开厂商设置',
    'details' => '已打开应用详情',
    'settings' => '已打开系统设置',
    'ok' || 'requested' => '已跳转',
    'error' => hint.isEmpty ? '没有打开权限页' : hint,
    _ => '已尝试打开',
  };
}

Future<void> openPermissionPage(BuildContext context, String id) async {
  // 等一帧，避免对话框刚关掉时 HyperOS 把 startActivity 吞掉
  await Future<void>.delayed(const Duration(milliseconds: 80));
  if (!context.mounted) return;
  final r = await NativeBridge.compatOpen(id);
  if (!context.mounted) return;
  showGlassSnack(context, _openMessage(r));
}

/// 打开或关闭分身里的悬浮小窗，并在缺少悬浮窗权限时带去系统页面。
Future<void> applyToolboxEnabled(BuildContext context, bool on) async {
  context.read<SettingsStore>().set((s) => s.toolboxOverlay = on);
  await NativeBridge.setToolboxEnabled(on);
  if (!on || !context.mounted) return;
  final granted = await NativeBridge.overlayGranted();
  if (granted != false || !context.mounted) return;
  final go = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('需要悬浮窗权限'),
      content: const Text('分身运行时，工具箱要显示在其他应用上层。接下来会打开系统的悬浮窗权限页。各厂商的页面不一样，打不开时会提示手动路径。'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('稍后')),
        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('去开启')),
      ],
    ),
  );
  if (go == true && context.mounted) await openPermissionPage(context, 'overlay');
}

/// 一次性引导开启无障碍服务。
Future<void> maybeShowA11yGuide(BuildContext context) async {
  final seen = await NativeBridge.a11yGuideSeen();
  if (seen || !context.mounted) return;
  final on = await NativeBridge.compatReport();
  // 粗略：报告里 accessibility 已 ok 就不再提示
  final items = on?['items'];
  if (items is List) {
    for (final raw in items) {
      if (raw is Map && raw['id'] == 'accessibility' && raw['status'] == 'ok') {
        await NativeBridge.a11yGuideMark();
        return;
      }
    }
  }
  if (!context.mounted) return;
  final go = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('开启一键填入'),
      content: const Text(
        '打开「平行空间」无障碍服务后，点「一键填入」会自动写入当前登录框的账号和密码。\n\n'
        '我们不会记录按键，也不会保存你在其他应用里输入的内容，只在你点填入时读取当前窗口。\n\n'
        '部分应用（游戏、银行、使用私有控件或加固的登录页）可能填不进去，这时可以用「复制账号 / 复制密码」后长按粘贴。',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('稍后')),
        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('去开启')),
      ],
    ),
  );
  await NativeBridge.a11yGuideMark();
  if (go == true && context.mounted) await openPermissionPage(context, 'accessibility');
}

class ToolboxScreen extends StatefulWidget {
  const ToolboxScreen({super.key});

  @override
  State<ToolboxScreen> createState() => _ToolboxScreenState();
}

class _ToolboxScreenState extends State<ToolboxScreen> with WidgetsBindingObserver {
  bool? _overlay;
  bool? _a11y;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshPerms();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) maybeShowA11yGuide(context);
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _onResumed();
  }

  Future<void> _refreshPerms() async {
    final o = await NativeBridge.overlayGranted();
    final report = await NativeBridge.compatReport();
    var a11y = false;
    final items = report?['items'];
    if (items is List) {
      for (final raw in items) {
        if (raw is Map && raw['id'] == 'accessibility') {
          a11y = raw['status'] == 'ok';
        }
      }
    }
    if (!mounted) return;
    setState(() {
      _overlay = o;
      _a11y = a11y;
    });
  }

  Future<void> _onResumed() async {
    await _refreshPerms();
    if (!mounted) return;
    final settings = context.read<SettingsStore>();
    if (settings.toolboxOverlay && _overlay == true) {
      await NativeBridge.setToolboxEnabled(true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final st = context.watch<SettingsStore>();
    final tt = Theme.of(context).textTheme;
    return Scaffold(
      body: BlobBackground(
        child: SafeArea(
          child: ListView(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
            children: [
              Row(children: [
                IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.arrow_back_rounded)),
                Text('工具箱', style: tt.headlineMedium),
              ]),
              const Padding(
                padding: EdgeInsets.fromLTRB(4, 8, 4, 0),
                child: Text('分身应用在前台时，屏幕上会出现一个可拖动、可收起的小窗。毛玻璃风格，点窗外不挡操作。可打开密码本进行一键填入和复制；添加账号请到 App 密码本页。'),
              ),
              SectionCard(title: '悬浮小窗', children: [
                GlassTile(
                  icon: Icons.picture_in_picture_alt_rounded,
                  title: '分身运行时显示',
                  subtitle: '关闭后，打开分身不会再出现小窗',
                  trailing: Switch(
                    value: st.toolboxOverlay,
                    onChanged: (v) async {
                      await applyToolboxEnabled(context, v);
                      await _refreshPerms();
                    },
                  ),
                ),
                GlassTile(
                  icon: Icons.layers_rounded,
                  title: '悬浮窗权限',
                  subtitle: _overlay == true
                      ? '已允许'
                      : '小米、华为、荣耀、OPPO、vivo、三星、魅族和 Pixel 会各自跳到对应页面',
                  onTap: () async {
                    await openPermissionPage(context, 'overlay');
                    await _refreshPerms();
                  },
                ),
                GlassTile(
                  icon: Icons.accessibility_new_rounded,
                  title: '无障碍填入',
                  subtitle: _a11y == true
                      ? '已开启，可一键写入登录框'
                      : '开启后点「填入」写入输入框；未开时改为复制到剪贴板',
                  onTap: () async {
                    await openPermissionPage(context, 'accessibility');
                    await _refreshPerms();
                  },
                ),
              ]),
              SectionCard(title: '密码本', children: [
                GlassTile(
                  icon: Icons.key_rounded,
                  title: '打开密码本',
                  subtitle: '在 App 里添加和管理账号；工具箱只负责填入和复制',
                  onTap: () => openVaultScreen(context),
                ),
              ]),
              SectionCard(title: '权限状态', children: [
                GlassTile(
                  icon: Icons.phonelink_setup_rounded,
                  title: '机型适配检查',
                  subtitle: '查看悬浮窗、无障碍、电池和快捷方式是否就绪',
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const CompatScreen())),
                ),
              ]),
              SectionCard(title: '诊断', children: [
                GlassTile(
                  icon: Icons.bug_report_rounded,
                  title: '工具箱诊断日志',
                  subtitle: '悬浮窗消失或崩溃时，可查看、复制或分享最近日志',
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const ToolboxCrashLogScreen()),
                  ),
                ),
              ]),
            ],
          ),
        ),
      ),
    );
  }
}


class ToolboxCrashLogScreen extends StatefulWidget {
  const ToolboxCrashLogScreen({super.key});

  @override
  State<ToolboxCrashLogScreen> createState() => _ToolboxCrashLogScreenState();
}

class _ToolboxCrashLogScreenState extends State<ToolboxCrashLogScreen> {
  String _log = '加载中…';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final text = await NativeBridge.toolboxCrashLog();
    if (!mounted) return;
    setState(() => _log = text);
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _log));
    if (!mounted) return;
    showGlassSnack(context, '已复制日志');
  }

  Future<void> _share() async {
    setState(() => _busy = true);
    final r = await NativeBridge.toolboxCrashLogShare();
    if (!mounted) return;
    setState(() => _busy = false);
    if (r != 'ok') showGlassSnack(context, '分享失败');
  }

  Future<void> _clear() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清空日志？'),
        content: const Text('只清除本机上的工具箱诊断日志，不影响密码本。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('清空')),
        ],
      ),
    );
    if (ok != true) return;
    await NativeBridge.toolboxCrashLogClear();
    await _reload();
    if (!mounted) return;
    showGlassSnack(context, '已清空');
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    return Scaffold(
      body: BlobBackground(
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 8, 0),
                child: Row(children: [
                  IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.arrow_back_rounded)),
                  Expanded(child: Text('工具箱诊断日志', style: tt.headlineMedium)),
                  IconButton(onPressed: _busy ? null : _reload, icon: const Icon(Icons.refresh_rounded)),
                ]),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
                child: Text(
                  '记录悬浮窗启动、看门狗重挂、addView 失败和未捕获异常。下次小球消失时把这里的内容发我即可定位。',
                  style: tt.bodySmall,
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Wrap(spacing: 8, runSpacing: 8, children: [
                  FilledButton.tonalIcon(onPressed: _busy ? null : _copy, icon: const Icon(Icons.copy_rounded), label: const Text('复制')),
                  FilledButton.tonalIcon(onPressed: _busy ? null : _share, icon: const Icon(Icons.share_rounded), label: const Text('分享')),
                  FilledButton.tonalIcon(onPressed: _busy ? null : _clear, icon: const Icon(Icons.delete_outline_rounded), label: const Text('清空')),
                ]),
              ),
              const SizedBox(height: 8),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: Theme.of(context).colorScheme.surface.withValues(alpha: 0.72),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(14),
                      child: SelectableText(_log, style: tt.bodySmall?.copyWith(fontFamily: 'monospace')),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
