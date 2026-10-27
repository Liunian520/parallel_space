import 'package:flutter/material.dart';

import '../models/clone_app.dart';
import 'app_icon.dart';
import 'glass.dart';
import 'pressable.dart';

/// 首页网格中的分身卡片（真实应用图标；不使用实时模糊）。
class CloneCard extends StatelessWidget {
  const CloneCard({
    super.key,
    required this.clone,
    required this.onTap,
    required this.onOpen,
    required this.onDelete,
    required this.onPin,
    this.mocking = false,
    this.dragging = false,
  });

  final CloneApp clone;
  final VoidCallback onTap;
  final VoidCallback onOpen;
  final VoidCallback onDelete;
  final VoidCallback onPin;
  final bool mocking;
  final bool dragging;

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;
    final card = Glass(
      child: Pressable(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 10, 2, 12),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Stack(clipBehavior: Clip.none, children: [
                  Opacity(
                    opacity: clone.frozen ? 0.45 : 1,
                    child: AppIcon(packageName: clone.packageName, label: clone.customName, size: 52),
                  ),
                  if (clone.frozen)
                    Positioned.fill(child: Center(child: Icon(Icons.ac_unit_rounded, color: s.primary, size: 24))),
                ]),
              ),
              const Spacer(),
              // 紧凑尺寸：320dp 宽屏幕上 48dp 的默认按钮会让这一行溢出
              PopupMenuButton<String>(
                tooltip: '更多操作',
                padding: EdgeInsets.zero,
                constraints: const BoxConstraints(minWidth: 160),
                style: IconButton.styleFrom(minimumSize: const Size(36, 36), tapTargetSize: MaterialTapTargetSize.shrinkWrap),
                icon: Icon(Icons.more_vert_rounded, color: s.onSurfaceVariant),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                onSelected: (v) => switch (v) { 'open' => onOpen(), 'pin' => onPin(), _ => onDelete() },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'open', child: ListTile(leading: Icon(Icons.open_in_new_rounded), title: Text('打开'))),
                  PopupMenuItem(
                      value: 'pin', child: ListTile(leading: Icon(Icons.add_to_home_screen_rounded), title: Text('添加到桌面'))),
                  PopupMenuItem(
                      value: 'delete', child: ListTile(leading: Icon(Icons.delete_outline_rounded), title: Text('删除分身'))),
                ],
              ),
            ]),
            const Spacer(),
            Text(clone.customName, maxLines: 1, overflow: TextOverflow.ellipsis, style: tt.titleMedium),
            const SizedBox(height: 2),
            Row(children: [
              Flexible(
                child: Text(
                  clone.frozen ? '已冻结' : (clone.version.isEmpty ? '分身' : 'v${clone.version}'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: tt.bodySmall?.copyWith(color: s.onSurfaceVariant),
                ),
              ),
              if (clone.locked) ...[
                const SizedBox(width: 4),
                Icon(Icons.lock_rounded, size: 13, color: s.onSurfaceVariant),
              ],
            ]),
            if (clone.mockEnabled) ...[
              const SizedBox(height: 6),
              _LocationChip(name: clone.mockName, active: mocking),
            ],
          ]),
        ),
      ),
    );
    if (!dragging) return card;
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(28),
        boxShadow: [BoxShadow(color: s.primary.withValues(alpha: 0.35), blurRadius: 24, offset: const Offset(0, 10))],
      ),
      child: card,
    );
  }
}

class _LocationChip extends StatelessWidget {
  const _LocationChip({required this.name, required this.active});
  final String name;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final c = active ? s.tertiary : s.onSurfaceVariant;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: c.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(10)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(active ? Icons.my_location_rounded : Icons.location_on_outlined, size: 12, color: c),
        const SizedBox(width: 3),
        Flexible(
          child: Text(name,
              maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 11, color: c, fontWeight: FontWeight.w600)),
        ),
      ]),
    );
  }
}
