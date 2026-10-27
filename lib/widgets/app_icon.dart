import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../services/app_icons.dart';

/// 已安装应用的真实图标。默认取个人空间里的原图标（虚拟分身不走工作资料角标）。
/// 加载前显示首字占位。
class AppIcon extends StatefulWidget {
  const AppIcon({super.key, required this.packageName, required this.label, this.work = false, this.size = 56});

  final String packageName;
  final String label;
  final bool work;
  final double size;

  @override
  State<AppIcon> createState() => _AppIconState();
}

class _AppIconState extends State<AppIcon> {
  late Future<Uint8List?> _f;

  @override
  void initState() {
    super.initState();
    _f = AppIcons.get(widget.packageName, work: widget.work);
  }

  @override
  void didUpdateWidget(covariant AppIcon old) {
    super.didUpdateWidget(old);
    if (old.packageName != widget.packageName || old.work != widget.work) {
      _f = AppIcons.get(widget.packageName, work: widget.work);
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final cache = (size * dpr).round().clamp(32, 256);
    Widget image(Uint8List bytes) => Image.memory(
          bytes,
          width: size,
          height: size,
          cacheWidth: cache,
          cacheHeight: cache,
          gaplessPlayback: true,
          filterQuality: FilterQuality.medium,
          semanticLabel: widget.label,
          errorBuilder: (_, _, _) => _Placeholder(label: widget.label, size: size),
        );

    final sync = AppIcons.peek(widget.packageName, work: widget.work);
    return SizedBox(
      width: size,
      height: size,
      child: sync != null
          ? image(sync)
          : FutureBuilder<Uint8List?>(
              future: _f,
              builder: (context, snap) =>
                  snap.data != null ? image(snap.data!) : _Placeholder(label: widget.label, size: size),
            ),
    );
  }
}

class _Placeholder extends StatelessWidget {
  const _Placeholder({required this.label, required this.size});
  final String label;
  final double size;

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(color: s.surfaceContainerHighest, borderRadius: BorderRadius.circular(size * 0.3)),
      child: Text(
        label.characters.firstOrNull ?? '·',
        style: TextStyle(fontSize: size * 0.42, fontWeight: FontWeight.w700, color: s.onSurfaceVariant),
        textScaler: TextScaler.noScaling,
      ),
    );
  }
}
