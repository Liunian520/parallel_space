import 'package:flutter/material.dart';

import '../models/clone_app.dart';
import 'glass.dart';

class CloneChooserResult {
  const CloneChooserResult(this.clone, {required this.remember});

  final CloneApp clone;
  final bool remember;
}

/// 未指定具体分身、又不能安全地直接打开时，让用户选一份。不会替用户记住，除非勾选。
Future<CloneChooserResult?> showCloneChooser(
  BuildContext context,
  List<CloneApp> clones, {
  String? message,
}) {
  if (clones.isEmpty) return Future.value();
  return showModalBottomSheet<CloneChooserResult>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => _ChooserSheet(clones: clones, message: message),
  );
}

class _ChooserSheet extends StatefulWidget {
  const _ChooserSheet({required this.clones, this.message});

  final List<CloneApp> clones;
  final String? message;

  @override
  State<_ChooserSheet> createState() => _ChooserSheetState();
}

class _ChooserSheetState extends State<_ChooserSheet> {
  late String _uid = widget.clones.first.uid;
  bool _remember = false;

  CloneApp get _selected => widget.clones.firstWhere((c) => c.uid == _uid, orElse: () => widget.clones.first);

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final selected = _selected;
    return GlassSheet(
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('选择分身', style: tt.titleLarge),
        const SizedBox(height: 6),
        Text(
          widget.message ?? '未指定具体分身时才用默认。点某一个、快捷方式和通知仍打开你选的那一个。',
          style: tt.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 8),
        for (final c in widget.clones)
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(c.customName, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(c.userId <= 0 ? c.packageName : '${c.packageName} · 实例 ${c.userId + 1}'),
            trailing: c.uid == selected.uid ? Icon(Icons.check_rounded, color: Theme.of(context).colorScheme.primary) : null,
            onTap: () => setState(() => _uid = c.uid),
          ),
        CheckboxListTile(
          contentPadding: EdgeInsets.zero,
          value: _remember,
          controlAffinity: ListTileControlAffinity.leading,
          title: const Text('记住为默认'),
          subtitle: Text('以后未指定具体分身时打开「${selected.customName}」'),
          onChanged: (v) => setState(() => _remember = v ?? false),
        ),
        const SizedBox(height: 8),
        Row(children: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
          const Spacer(),
          FilledButton(
            onPressed: () => Navigator.pop(context, CloneChooserResult(selected, remember: _remember)),
            child: const Text('打开这一份'),
          ),
        ]),
      ]),
    );
  }
}
