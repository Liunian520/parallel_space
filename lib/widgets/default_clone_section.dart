import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/clone_app.dart';
import '../services/clone_actions.dart';
import '../state/clone_store.dart';
import 'section_tile.dart';

/// 分身详情里的「默认启动」。开关是明确操作，打开分身本身不会把它记成默认。
class DefaultCloneSection extends StatelessWidget {
  const DefaultCloneSection({super.key, required this.clone});

  final CloneApp clone;

  @override
  Widget build(BuildContext context) {
    final store = context.watch<CloneStore>();
    final live = store.byId(clone.uid);
    if (live == null) return const SizedBox.shrink();
    final current = store.defaultClone(live.packageName);
    final mine = current?.uid == live.uid;
    final other = current != null && !mine ? current.customName : null;
    final subtitle = mine
        ? '未指定具体分身时打开「${live.customName}」。点某一个分身、桌面快捷方式和通知仍打开你选的那一个。'
        : other != null
            ? '当前默认是「$other」。打开后，未指定具体分身时改为「${live.customName}」。'
            : '打开后，未指定具体分身时打开「${live.customName}」。最近打开的不会自动变成默认。';
    return SectionCard(title: '默认启动', children: [
      GlassTile(
        icon: Icons.star_rounded,
        title: mine ? '已是默认分身' : '设为默认分身',
        subtitle: subtitle,
        trailing: Switch(
          value: mine,
          onChanged: (v) {
            if (v) {
              store.setDefaultClone(live);
              showGlassSnack(context, '已把「${live.customName}」设为默认分身');
            } else {
              store.clearDefault(live.packageName);
              showGlassSnack(context, '已取消默认。未指定具体分身时会再询问');
            }
          },
        ),
      ),
    ]);
  }
}
