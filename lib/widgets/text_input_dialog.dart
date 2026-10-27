import 'package:flutter/material.dart';

/// 文本输入对话框。控制器由对话框自身的 State 持有并在其销毁时释放
/// （在 pop 之后立即 dispose 会在退场动画期间触发 “TextEditingController used after being disposed”）。
Future<String?> showTextInputDialog(
  BuildContext context, {
  required String title,
  String initial = '',
  int maxLength = 12,
  String? hint,
  String? helper,
}) =>
    showDialog<String>(
      context: context,
      builder: (_) => _TextInputDialog(title: title, initial: initial, maxLength: maxLength, hint: hint, helper: helper),
    );

class _TextInputDialog extends StatefulWidget {
  const _TextInputDialog({required this.title, required this.initial, required this.maxLength, this.hint, this.helper});
  final String title;
  final String initial;
  final int maxLength;
  final String? hint;
  final String? helper;

  @override
  State<_TextInputDialog> createState() => _TextInputDialogState();
}

class _TextInputDialogState extends State<_TextInputDialog> {
  late final _ctrl = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(widget.title),
        content: TextField(
          controller: _ctrl,
          autofocus: true,
          maxLength: widget.maxLength,
          decoration: InputDecoration(hintText: widget.hint, helperText: widget.helper),
          onSubmitted: (v) => Navigator.pop(context, v.trim()),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, _ctrl.text.trim()), child: const Text('保存')),
        ],
      );
}

/// 对话框关闭后，退场动画期间 TextField 仍在使用控制器；等动画结束后再释放。
void disposeAfterDialog(List<TextEditingController> ctrls) =>
    Future<void>.delayed(const Duration(milliseconds: 600), () {
      for (final c in ctrls) {
        c.dispose();
      }
    });
