import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/clone_store.dart';
import '../state/location_store.dart';
import '../state/settings_store.dart';
import 'backup_codec.dart';
import 'clone_actions.dart';
import 'native_bridge.dart';

/// 生成备份 JSON（分身、收藏、自建路线、设置；不含 PIN）。
String buildBackupJson(BuildContext context) {
  final store = context.read<CloneStore>();
  final loc = context.read<LocationStore>();
  final st = context.read<SettingsStore>();
  return BackupCodec.encode(BackupData(
    clones: store.clones,
    favorites: loc.favorites,
    routes: loc.userRoutes,
    settings: st.toBackup(),
  ));
}

String _fileName() {
  final n = DateTime.now();
  String two(int v) => v.toString().padLeft(2, '0');
  return 'parallel_space_${n.year}${two(n.month)}${two(n.day)}_${two(n.hour)}${two(n.minute)}.json';
}

Future<void> exportBackup(BuildContext context, {required bool share}) async {
  final json = buildBackupJson(context);
  final name = _fileName();
  final r = share ? await NativeBridge.shareFile(name, json) : await NativeBridge.saveFile(name, json);
  if (!context.mounted) return;
  showGlassSnack(context, switch (r) {
    'ok' => share ? '已打开分享面板' : '备份已保存：$name',
    'cancelled' => '已取消',
    _ => '导出失败（仅支持 Android）',
  });
}

Future<void> importBackup(BuildContext context) async {
  final text = await NativeBridge.openFile();
  if (text == null || !context.mounted) return;
  await applyBackupText(context, text);
}

/// 校验并询问合并/覆盖后写入。公开以便测试。
Future<void> applyBackupText(BuildContext context, String text) async {
  final BackupData data;
  try {
    data = BackupCodec.decode(text);
  } on BackupFormatException catch (e) {
    showGlassSnack(context, '导入失败：${e.message}');
    return;
  }
  final mode = await showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('导入备份'),
      content: Text(
        '分身 ${data.clones.length} 个 · 收藏 ${data.favorites.length} 个 · 路线 ${data.routes.length} 条'
        '${data.skipped > 0 ? '\n有 ${data.skipped} 项无法识别，将被跳过' : ''}'
        '\n\n合并：保留现有数据，只添加新的项目。\n覆盖：重置分身设置、清空收藏和路线，并恢复备份中的设置（不会删除工作资料中的应用）。'
        '\n\nPIN 不会随备份迁移，私密空间需在新设备重新设置 PIN。分身设置只会应用到本机工作资料中已有的同一应用。',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
        TextButton(
          onPressed: () => Navigator.pop(ctx, 'replace'),
          style: TextButton.styleFrom(foregroundColor: Theme.of(ctx).colorScheme.error),
          child: const Text('覆盖'),
        ),
        FilledButton(onPressed: () => Navigator.pop(ctx, 'merge'), child: const Text('合并')),
      ],
    ),
  );
  if (mode == null || !context.mounted) return;
  final replace = mode == 'replace';
  final store = context.read<CloneStore>();
  final loc = context.read<LocationStore>();
  if (replace && store.mockingUid != null) {
    loc.stopRoute();
    await NativeBridge.stopMock();
  }
  if (!context.mounted) return;
  final r = store.importClones(data.clones, replace: replace);
  final n = loc.importData(data.favorites, data.routes, replace: replace);
  if (replace && data.settings.isNotEmpty) context.read<SettingsStore>().applyBackup(data.settings);
  showGlassSnack(context, '导入完成：恢复 ${r.applied} 个分身设置、$n 个收藏/路线${r.missing > 0 ? '；${r.missing} 个应用不在本机工作资料中，请先添加分身后再导入' : ''}');
}
