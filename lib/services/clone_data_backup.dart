import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../config/app_version.dart';
import '../models/clone_app.dart';
import '../state/clone_store.dart';
import 'clone_actions.dart';
import 'clone_data_codec.dart';
import 'native_bridge.dart';

String _fileName({String? label, bool all = false}) {
  final n = DateTime.now();
  String two(int v) => v.toString().padLeft(2, '0');
  final ts = '${n.year}${two(n.month)}${two(n.day)}_${two(n.hour)}${two(n.minute)}';
  if (all) return 'alter_ego_data_all_$ts.aebak';
  final raw = (label ?? 'clone').replaceAll(RegExp(r'[^A-Za-z0-9\u4e00-\u9fff]+'), '_');
  final safe = raw.isEmpty ? 'clone' : raw.substring(0, raw.length.clamp(0, 20));
  return 'alter_ego_data_${safe}_$ts.aebak';
}

String formatBytes(int n) {
  if (n < 1024) return '$n B';
  if (n < 1024 * 1024) return '${(n / 1024).toStringAsFixed(1)} KB';
  if (n < 1024 * 1024 * 1024) return '${(n / (1024 * 1024)).toStringAsFixed(1)} MB';
  return '${(n / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
}

Future<void> exportCloneData(BuildContext context) async {
  final store = context.read<CloneStore>();
  if (!store.engineAvailable) {
    showGlassSnack(context, '仅支持虚拟引擎分身。引擎未就绪时无法备份应用数据。');
    return;
  }
  if (store.clones.isEmpty) {
    showGlassSnack(context, '还没有可备份的分身');
    return;
  }
  final warned = await showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('备份分身数据'),
      content: const Text(
        '备份文件会包含分身里的应用数据和登录状态，体积可能达到几百 MB。请妥善保存在本机，不要发给他人、不要上传到公开网盘。\n\n'
        '密码本和 PIN 不会写入这份备份。导出前会先停掉正在运行的分身，以保证文件完整。',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('继续')),
      ],
    ),
  );
  if (warned != true || !context.mounted) return;

  final scope = await showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('要备份哪些分身？'),
      content: const Text('可以只备份一个分身，也可以一次备份全部。恢复时再选择对应的备份文件。'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
        TextButton(onPressed: () => Navigator.pop(ctx, 'one'), child: const Text('某个分身')),
        FilledButton(onPressed: () => Navigator.pop(ctx, 'all'), child: const Text('全部分身')),
      ],
    ),
  );
  if (scope == null || !context.mounted) return;

  final List<CloneApp> targets;
  if (scope == 'all') {
    targets = List<CloneApp>.from(store.clones);
  } else {
    final picked = await showDialog<CloneApp>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('选择要备份的分身'),
        children: [
          for (final c in store.clones)
            SimpleDialogOption(
              onPressed: () => Navigator.pop(ctx, c),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Text('${c.customName}${c.userId > 0 ? '  ·  #${c.userId}' : ''}'),
              ),
            ),
        ],
      ),
    );
    if (picked == null || !context.mounted) return;
    targets = [picked];
  }

  final dest = await showDialog<String>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('保存方式'),
      content: const Text('「保存到文件」会打开系统文件选择器；也可以直接分享到网盘、电脑或即时通讯（请只发给自己）。'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
        TextButton(onPressed: () => Navigator.pop(ctx, 'share'), child: const Text('分享')),
        FilledButton(onPressed: () => Navigator.pop(ctx, 'save'), child: const Text('保存到文件')),
      ],
    ),
  );
  if (dest == null || !context.mounted) return;

  final name = _fileName(
    all: targets.length > 1,
    label: targets.length == 1 ? targets.first.customName : null,
  );
  final items = [
    for (final c in targets)
      {
        'pkg': c.packageName,
        'userId': c.userId,
        'customLabel': c.customLabel,
        'appName': c.appName,
      },
  ];

  final packed = await _runWithProgress(context, '正在备份分身数据…', () {
    return NativeBridge.packCloneData(items: items, name: name, appVersion: kAppVersionName);
  });
  if (!context.mounted) return;
  if (packed == null) return;
  final r = packed['r']?.toString() ?? 'error';
  if (r != 'ok') {
    showGlassSnack(context, _packError(r, packed['detail']?.toString()));
    return;
  }
  final path = packed['path']?.toString() ?? '';
  final fileName = packed['fileName']?.toString() ?? name;
  final size = (packed['size'] as num?)?.toInt() ?? 0;
  if (path.isEmpty) {
    showGlassSnack(context, '备份打包失败');
    return;
  }
  final saveR = dest == 'share'
      ? await NativeBridge.shareBinaryFile(path, name)
      : await NativeBridge.saveBinaryFile(path, name);
  if (!context.mounted) return;
  final warnings = packed['warnings'];
  final extra = warnings is List && warnings.isNotEmpty ? '\n${warnings.join('\n')}' : '';
  showGlassSnack(context, switch (saveR) {
    'ok' => dest == 'share'
        ? '已打开分享面板（${formatBytes(size)}）$extra'
        : '分身数据已保存：$fileName（${formatBytes(size)}）$extra',
    'cancelled' => '已取消保存。备份已打好，可再试一次保存或分享。',
    _ => '保存失败（仅支持 Android）',
  });
}

Future<void> importCloneData(BuildContext context) async {
  final store = context.read<CloneStore>();
  if (!store.engineAvailable) {
    showGlassSnack(context, '仅支持虚拟引擎分身。引擎未就绪时无法恢复应用数据。');
    return;
  }
  final path = await NativeBridge.openBinaryFile();
  if (path == null || !context.mounted) return;
  final preview = await NativeBridge.previewCloneData(path);
  if (!context.mounted) return;
  final code = preview['r']?.toString() ?? 'error';
  if (code == 'need_upgrade') {
    showGlassSnack(context, preview['detail']?.toString() ?? '备份来自更新版本，请先升级 Alter Ego');
    return;
  }
  if (code != 'ok') {
    showGlassSnack(context, preview['detail']?.toString() ?? '无法读取备份文件');
    return;
  }

  final raw = preview['clones'];
  final clones = <CloneDataEntry>[];
  if (raw is List) {
    for (final e in raw) {
      final exists = e is Map && e['exists'] == true;
      final c = CloneDataEntry.fromJson(e, exists: exists);
      if (c != null) clones.add(c);
    }
  }
  if (clones.isEmpty) {
    showGlassSnack(context, '备份里没有可恢复的分身');
    return;
  }
  final anyExists = clones.any((c) => c.exists);
  final exportedAt = preview['exportedAt']?.toString() ?? '';
  final summary = StringBuffer()
    ..write('共 ${clones.length} 个分身')
    ..write(exportedAt.isEmpty ? '' : ' · 导出于 $exportedAt')
    ..write('\n\n');
  for (final c in clones.take(8)) {
    summary.writeln('· ${c.displayName}${c.exists ? '（本机已有，可覆盖或新建）' : '（本机没有，将新建）'}${c.hasApk ? '' : ' · 备份未含安装包'}');
  }
  if (clones.length > 8) summary.writeln('· …还有 ${clones.length - 8} 个');
  summary.write('\n恢复会写入登录状态，覆盖已有分身时，该分身里的当前数据会被替换。密码本和 PIN 不会被改动。');
  summary.write('\n备份里如果开了独立设备标识：覆盖会沿用原来的标识；再新建一份会另给一个，避免和留下的分身相同。旧版备份没有这项，覆盖后改回本机真实标识，已经生成过的标识仍保留。');

  String? mode;
  if (!anyExists) {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('恢复分身数据'),
        content: SingleChildScrollView(child: Text(summary.toString())),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('开始恢复')),
        ],
      ),
    );
    if (ok != true) return;
    mode = CloneDataCodec.modeOverwrite; // 本机没有时等同于新建，沿用原 userId
  } else if (clones.length == 1) {
    mode = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('本机已有这个分身'),
        content: SingleChildScrollView(child: Text('$summary\n覆盖：替换已有分身的数据。\n新建一个分身：保留现有分身，再恢复出一份。')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, CloneDataCodec.modeOverwrite), child: const Text('覆盖')),
          FilledButton(onPressed: () => Navigator.pop(ctx, CloneDataCodec.modeCreateNew), child: const Text('新建一个分身')),
        ],
      ),
    );
  } else {
    mode = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('恢复全部分身'),
        content: SingleChildScrollView(
          child: Text('$summary\n全部覆盖：本机已有的同一分身会被替换；没有的会新建。\n全部新建：已有的保留，再各恢复一份新的。'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          TextButton(onPressed: () => Navigator.pop(ctx, CloneDataCodec.modeOverwrite), child: const Text('全部覆盖')),
          FilledButton(onPressed: () => Navigator.pop(ctx, CloneDataCodec.modeCreateNew), child: const Text('全部新建')),
        ],
      ),
    );
  }
  if (mode == null || !context.mounted) return;

  final labels = [for (final c in store.clones) c.customName];
  final restored = await _runWithProgress(context, '正在恢复分身数据…', () {
    return NativeBridge.restoreCloneData(path: path, mode: mode!, existingLabels: labels);
  });
  if (!context.mounted) return;
  if (restored == null) return;
  final code2 = restored['r']?.toString() ?? 'error';
  // 先刷新引擎列表，再把恢复出的分身写入主屏（sync 不会自动添加新条目）。
  await store.refresh();
  if (!context.mounted) return;
  final items = restored['items'];
  if (items is List) {
    for (final e in items) {
      if (e is! Map || e['r'] != 'ok') continue;
      final pkg = e['packageName'] is String ? e['packageName'] as String : '';
      if (pkg.isEmpty) continue;
      final userId = (e['targetUserId'] as num?)?.toInt() ?? 0;
      final appName = e['appName'] is String ? e['appName'] as String : pkg;
      final label = e['label'] is String ? e['label'] as String : '';
      store.adoptRestored(
        packageName: pkg,
        userId: userId,
        appName: appName,
        customLabel: label.isEmpty ? null : label,
        deviceIdIsolated: e['deviceIdIsolated'] is bool ? e['deviceIdIsolated'] as bool : null,
      );
      final sourceUserId = (e['sourceUserId'] as num?)?.toInt() ?? userId;
      final action = e['action'] is String ? e['action'] as String : '';
      store.noteCloneDataRestore(
        packageName: pkg,
        sourceUserId: sourceUserId,
        targetUserId: userId,
        action: action,
      );
    }
  }
  final okN = (restored['ok'] as num?)?.toInt() ?? 0;
  final failN = (restored['fail'] as num?)?.toInt() ?? 0;
  final detail = restored['detail']?.toString();
  final identityNote = _identityWarn(items);
  showGlassSnack(context, switch (code2) {
    'ok' => '已恢复 $okN 个分身，可在主屏打开。第一次启动可能稍慢。${identityNote.isEmpty ? '' : ' $identityNote'}',
    'partial' => '恢复了 $okN 个分身，$failN 个失败。${_firstFail(items)}${identityNote.isEmpty ? '' : ' $identityNote'}',
    'no_apk' => detail ?? '备份里没有安装包，本机也没有该应用，无法恢复',
    'engine_unavailable' => '仅支持虚拟引擎分身，当前引擎不可用',
    _ => detail ?? '恢复失败',
  });
}

String _identityWarn(Object? items) {
  if (items is! List) return '';
  for (final e in items) {
    if (e is Map && e['identityWarning'] is String) {
      final text = (e['identityWarning'] as String).trim();
      if (text.isNotEmpty) return text;
    }
  }
  return '';
}

String _firstFail(Object? items) {
  if (items is! List) return '';
  for (final e in items) {
    if (e is Map && e['r'] != 'ok') {
      final name = (e['appName'] ?? e['packageName'] ?? '').toString();
      final d = e['detail']?.toString() ?? '';
      if (name.isEmpty) return d;
      return d.isEmpty ? name : '$name：$d';
    }
  }
  return '';
}

String _packError(String r, String? detail) => switch (r) {
      'empty' => '没有可备份的分身',
      'engine_unavailable' => '仅支持虚拟引擎分身，当前引擎不可用',
      _ => detail == null || detail.isEmpty ? '备份失败' : '备份失败：$detail',
    };

Future<T?> _runWithProgress<T>(BuildContext context, String title, Future<T> Function() job) async {
  final progress = ValueNotifier<Map<String, dynamic>>({'message': title, 'current': 0, 'total': 0});
  final sub = NativeBridge.cloneDataProgress().listen((e) {
    progress.value = e;
  });
  showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => PopScope(
      canPop: false,
      child: AlertDialog(
        title: Text(title),
        content: ValueListenableBuilder<Map<String, dynamic>>(
          valueListenable: progress,
          builder: (context, ev, _) {
            final msg = ev['message']?.toString() ?? title;
            final current = (ev['current'] as num?)?.toInt() ?? 0;
            final total = (ev['total'] as num?)?.toInt() ?? 0;
            final bytes = (ev['bytes'] as num?)?.toInt() ?? 0;
            final line = StringBuffer(msg);
            if (total > 0 && current > 0) line.write('\n$current / $total');
            if (bytes > 0) line.write('\n已处理 ${formatBytes(bytes)}');
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const LinearProgressIndicator(),
                const SizedBox(height: 16),
                Text(line.toString()),
              ],
            );
          },
        ),
      ),
    ),
  );
  try {
    return await job();
  } catch (e) {
    if (context.mounted) showGlassSnack(context, '操作失败：$e');
    return null;
  } finally {
    await sub.cancel();
    progress.dispose();
    if (context.mounted) {
      final nav = Navigator.of(context, rootNavigator: true);
      if (nav.canPop()) nav.pop();
    }
  }
}
