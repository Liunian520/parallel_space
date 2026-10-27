import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../state/settings_store.dart';
import '../widgets/blob_background.dart';
import '../widgets/section_tile.dart';
import 'compat_screen.dart';
import 'vault_screen.dart';

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
      content: const Text('分身运行时，工具箱要显示在其他应用上层。接下来会打开系统的悬浮窗权限页。各厂商的页面不一样，打不开时会回到应用详情。'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('稍后')),
        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('去开启')),
      ],
    ),
  );
  if (go == true) await NativeBridge.compatOpen('overlay');
}

class ToolboxScreen extends StatelessWidget {
  const ToolboxScreen({super.key});

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
                child: Text('分身应用在前台时，屏幕上会出现一个可拖动、可收起的小窗。以后还可以往里面加工具，目前第一项是账号密码本。'),
              ),
              SectionCard(title: '悬浮小窗', children: [
                GlassTile(
                  icon: Icons.picture_in_picture_alt_rounded,
                  title: '分身运行时显示',
                  subtitle: '关闭后，打开分身不会再出现小窗',
                  trailing: Switch(
                    value: st.toolboxOverlay,
                    onChanged: (v) => applyToolboxEnabled(context, v),
                  ),
                ),
                GlassTile(
                  icon: Icons.layers_rounded,
                  title: '悬浮窗权限',
                  subtitle: '小米、华为、荣耀、OPPO、vivo、三星和 Pixel 会各自跳到对应页面',
                  onTap: () async {
                    final r = await NativeBridge.compatOpen('overlay');
                    if (context.mounted && r == 'error') showGlassSnack(context, '没有打开权限页');
                  },
                ),
                GlassTile(
                  icon: Icons.accessibility_new_rounded,
                  title: '无障碍填入',
                  subtitle: '开启后，点「填入」会写入当前输入框。没开时改为复制，30 秒后清除',
                  onTap: () async {
                    final r = await NativeBridge.compatOpen('accessibility');
                    if (context.mounted && r == 'error') showGlassSnack(context, '没有打开无障碍设置');
                  },
                ),
              ]),
              SectionCard(title: '账号密码本', children: [
                GlassTile(
                  icon: Icons.key_rounded,
                  title: '管理账号密码',
                  subtitle: '按分身或包名分组，打开前要验证身份',
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const VaultScreen())),
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
            ],
          ),
        ),
      ),
    );
  }
}
