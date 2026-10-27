import 'package:flutter/material.dart';

import 'glass.dart';
import 'pressable.dart';

class NavItem {
  const NavItem(this.icon, this.selectedIcon, this.label);
  final IconData icon;
  final IconData selectedIcon;
  final String label;
}

/// 悬浮式底部导航栏（精简特效关闭时使用真实模糊）。
class FloatingNavBar extends StatelessWidget {
  const FloatingNavBar({super.key, required this.items, required this.index, required this.onChanged});
  final List<NavItem> items;
  final int index;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    return Glass(
      radius: 32,
      blur: true,
      padding: const EdgeInsets.all(6),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        for (var i = 0; i < items.length; i++)
          Pressable(
            radius: 26,
            onTap: () => onChanged(i),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 300),
              curve: Curves.easeOutBack,
              padding: EdgeInsets.symmetric(horizontal: i == index ? 20 : 16, vertical: 12),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(26),
                color: i == index ? s.primary : Colors.transparent,
              ),
              child: Row(children: [
                Icon(i == index ? items[i].selectedIcon : items[i].icon,
                    color: i == index ? s.onPrimary : s.onSurfaceVariant),
                AnimatedSize(
                  duration: const Duration(milliseconds: 250),
                  child: i == index
                      ? Padding(
                          padding: const EdgeInsets.only(left: 8),
                          child: Text(items[i].label,
                              style: TextStyle(color: s.onPrimary, fontWeight: FontWeight.w700)),
                        )
                      : const SizedBox.shrink(),
                ),
              ]),
            ),
          ),
      ]),
    );
  }
}
