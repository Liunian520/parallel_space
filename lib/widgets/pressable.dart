import 'package:flutter/material.dart';

/// 统一点击反馈：缩放 + 涟漪。
class Pressable extends StatefulWidget {
  const Pressable({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.radius = 28,
    this.scale = 0.95,
  });

  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final double radius;
  final double scale;

  @override
  State<Pressable> createState() => _PressableState();
}

class _PressableState extends State<Pressable> {
  bool _down = false;

  void _set(bool v) {
    if (_down != v) setState(() => _down = v);
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedScale(
      scale: _down ? widget.scale : 1,
      duration: const Duration(milliseconds: 140),
      curve: Curves.easeOut,
      child: Material(
        type: MaterialType.transparency,
        child: InkWell(
          borderRadius: BorderRadius.circular(widget.radius),
          onTap: widget.onTap,
          onLongPress: widget.onLongPress,
          onHighlightChanged: _set,
          child: widget.child,
        ),
      ),
    );
  }
}
