import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/native_bridge.dart';

/// 相机自检 + 分身相机诊断日志（1.3.29）。
class CameraDiagScreen extends StatefulWidget {
  const CameraDiagScreen({super.key});
  @override
  State<CameraDiagScreen> createState() => _CameraDiagScreenState();
}

class _CameraDiagScreenState extends State<CameraDiagScreen> {
  String _test = '';
  String _log = '';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    final l = await NativeBridge.cameraDiagLog();
    if (mounted) setState(() => _log = l);
  }

  Future<void> _run() async {
    setState(() => _busy = true);
    final r = await NativeBridge.cameraSelfTest();
    await _reload();
    if (mounted) setState(() { _test = r; _busy = false; });
  }

  @override
  Widget build(BuildContext context) {
    final all = '【自检】\n$_test\n【分身相机日志】\n$_log';
    return Scaffold(
      appBar: AppBar(title: const Text('相机自检'), actions: [
        IconButton(icon: const Icon(Icons.copy_rounded), onPressed: () {
          Clipboard.setData(ClipboardData(text: all));
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('已复制')));
        }),
        IconButton(icon: const Icon(Icons.delete_outline_rounded), onPressed: () async {
          await NativeBridge.clearCameraDiag();
          _reload();
        }),
      ]),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        const Text('1. 点「开始自检」，检查 Alter Ego 本身能否打开相机。\n'
            '2. 分身扫码仍黑屏时：先清空日志，打开分身进首页再进扫码页，回到这里点刷新并复制。\n'
            '请保留 win@ 行（e2e、manual、pad、statusVisible）和 -> connect 行。1.3.32 起还会记一行 attr（改写前后真正发出的身份）。'),
        const SizedBox(height: 12),
        Row(children: [
          FilledButton(onPressed: _busy ? null : _run, child: Text(_busy ? '自检中…' : '开始自检')),
          const SizedBox(width: 12),
          OutlinedButton(onPressed: _reload, child: const Text('刷新日志')),
        ]),
        const SizedBox(height: 16),
        if (_test.isNotEmpty) SelectableText(_test),
        const Divider(height: 32),
        SelectableText(_log.isEmpty ? '（暂无分身相机日志）' : _log,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
      ]),
    );
  }
}
