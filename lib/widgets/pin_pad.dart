import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'pressable.dart';

/// 数字键盘 + 圆点指示器。
class PinPad extends StatefulWidget {
  const PinPad({super.key, required this.onSubmit, this.length = 6, this.error, this.minLength = 4});
  final ValueChanged<String> onSubmit;
  final int length;
  final int minLength;
  final String? error;

  @override
  State<PinPad> createState() => PinPadState();
}

class PinPadState extends State<PinPad> {
  String _v = '';

  void clear() => setState(() => _v = '');

  void _press(String k) {
    HapticFeedback.selectionClick();
    setState(() {
      if (k == '⌫') {
        if (_v.isNotEmpty) _v = _v.substring(0, _v.length - 1);
      } else if (k == '✓') {
        if (_v.length >= widget.minLength) {
          final v = _v;
          _v = '';
          widget.onSubmit(v);
        }
      } else if (_v.length < widget.length) {
        _v += k;
        if (_v.length == widget.length) {
          final v = _v;
          _v = '';
          widget.onSubmit(v);
        }
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = Theme.of(context).colorScheme;
    const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', '✓'];
    return Column(mainAxisSize: MainAxisSize.min, children: [
      Row(mainAxisAlignment: MainAxisAlignment.center, children: [
        for (var i = 0; i < widget.length; i++)
          AnimatedContainer(
            duration: const Duration(milliseconds: 160),
            margin: const EdgeInsets.symmetric(horizontal: 6),
            width: 14,
            height: 14,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: i < _v.length ? s.primary : Colors.transparent,
              border: Border.all(color: s.primary, width: 2),
            ),
          ),
      ]),
      SizedBox(
        height: 28,
        child: Center(
          child: widget.error == null
              ? null
              : Text(widget.error!, style: TextStyle(color: s.error, fontSize: 13)),
        ),
      ),
      LayoutBuilder(builder: (context, box) {
        // 自适应宽度：小屏 / 对话框中不溢出
        final w = box.maxWidth.isFinite ? box.maxWidth.clamp(180.0, 264.0) : 264.0;
        final kw = (w - 20) / 3;
        return SizedBox(
        width: w,
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          for (var r = 0; r < 4; r++)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
                for (final k in keys.sublist(r * 3, r * 3 + 3))
                  SizedBox(
                    width: kw,
                    height: 54,
                    child: Pressable(
                      key: ValueKey('pin-$k'),
                      radius: 24,
                      onTap: () => _press(k),
                      child: Container(
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(24),
                          color: k == '✓'
                              ? s.primary.withValues(alpha: _v.length >= widget.minLength ? 0.9 : 0.25)
                              : s.surfaceContainerHighest.withValues(alpha: 0.5),
                        ),
                        child: Text(k,
                            style: TextStyle(
                              fontSize: 22,
                              fontWeight: FontWeight.w600,
                              color: k == '✓' ? s.onPrimary : s.onSurface,
                            )),
                      ),
                    ),
                  ),
              ]),
            ),
        ]),
      );
      }),
    ]);
  }
}

/// 弹出 PIN 验证框。[verify] 返回 'ok' | 'wrong' | 'cooldown' | 'nopin'。
Future<bool> showPinDialog(BuildContext context, {required String title, required String Function(String) verify}) async {
  final r = await showDialog<bool>(
    context: context,
    builder: (ctx) {
      String? err;
      return StatefulBuilder(
        builder: (ctx, setState) => AlertDialog(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
          title: Text(title, textAlign: TextAlign.center, style: const TextStyle(fontSize: 18)),
          content: SingleChildScrollView(
              child: PinPad(
                error: err,
                onSubmit: (v) {
                  final res = verify(v);
                  if (res == 'ok') {
                    Navigator.pop(ctx, true);
                  } else {
                    setState(() => err = res == 'cooldown' ? '尝试次数过多，请 30 秒后再试' : 'PIN 不正确');
                  }
                },
              ),
          ),
          actions: [TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消'))],
        ),
      );
    },
  );
  return r ?? false;
}
