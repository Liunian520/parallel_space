import 'package:flutter/material.dart';

import 'glass.dart';

/// 设置分组卡片。
class SectionCard extends StatelessWidget {
  const SectionCard({super.key, this.title, required this.children});
  final String? title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      if (title != null)
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 20, 12, 10),
          child: Text(title!,
              style: tt.labelLarge?.copyWith(color: Theme.of(context).colorScheme.primary, letterSpacing: 1)),
        ),
      Glass(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Column(children: children),
      ),
    ]);
  }
}

/// 带涟漪的圆角列表项。
class GlassTile extends StatelessWidget {
  const GlassTile({
    super.key,
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.danger = false,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    final c = danger ? s.error : s.primary;
    return ListTile(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      leading: Container(
        padding: const EdgeInsets.all(9),
        decoration: BoxDecoration(color: c.withValues(alpha: 0.14), borderRadius: BorderRadius.circular(14)),
        child: Icon(icon, color: c, size: 22),
      ),
      title: Text(title,
          style: TextStyle(fontWeight: FontWeight.w600, color: danger ? s.error : null)),
      subtitle: subtitle == null ? null : Text(subtitle!),
      trailing: trailing ?? (onTap != null ? const Icon(Icons.chevron_right_rounded) : null),
      onTap: onTap,
    );
  }
}
