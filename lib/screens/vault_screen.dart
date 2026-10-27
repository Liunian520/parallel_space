import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../services/vault_group.dart';
import '../state/clone_store.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';
import 'toolbox_screen.dart';

/// 主应用里的密码本。和悬浮窗共用同一份本机明文存储，打开即可用，不验证身份。
class VaultScreen extends StatefulWidget {
  const VaultScreen({super.key});

  @override
  State<VaultScreen> createState() => _VaultScreenState();
}

class _VaultScreenState extends State<VaultScreen> {
  var _loading = true;
  String? _error;
  List<VaultEntry> _entries = const [];
  String _currentPkg = '';

  @override
  void initState() {
    super.initState();
    _boot();
  }

  Future<void> _boot() async {
    _currentPkg = await NativeBridge.toolboxCurrentPackage() ?? '';
    if (!mounted) return;
    await _reload();
    if (mounted) maybeShowA11yGuide(context);
  }

  Future<void> _reload() async {
    setState(() => _loading = true);
    final raw = await NativeBridge.vaultList();
    if (!mounted) return;
    final list = raw?['entries'];
    setState(() {
      _loading = false;
      _error = raw?['error'] is String ? raw!['error'] as String : null;
      _entries = [
        if (list is List) ...list.map(VaultEntry.fromMap).nonNulls,
      ];
    });
  }

  Future<void> _edit({VaultEntry? existing}) async {
    final saved = await showDialog<bool>(
      context: context,
      builder: (_) => _VaultEditor(existing: existing, currentPackage: _currentPkg),
    );
    if (saved == true) await _reload();
  }

  Future<void> _delete(VaultEntry e) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('删除这条账号？'),
        content: Text('「${e.label}」会从本机密码本里去掉，无法恢复。'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('删除')),
        ],
      ),
    );
    if (ok != true) return;
    final r = await NativeBridge.vaultDelete(e.id);
    if (!mounted) return;
    if (r == 'ok') {
      await _reload();
    } else {
      showGlassSnack(context, '删除失败');
    }
  }

  Future<void> _fill(VaultEntry e) async {
    final r = await NativeBridge.vaultFill(e.id);
    if (!mounted) return;
    showGlassSnack(context, switch (r) {
      'filled' => '已填入当前输入框',
      'copied' => '未开启无障碍，已复制密码，长按输入框粘贴',
      'copied_no_field' => '没有找到输入框，已复制密码，长按输入框粘贴',
      _ => '填入失败',
    });
  }

  Future<void> _copy(VaultEntry e, String which) async {
    final r = await NativeBridge.vaultCopy(e.id, which);
    if (!mounted) return;
    showGlassSnack(context, switch (r) {
      'copied_user' => '已复制账号',
      'copied_pass' => '已复制密码，长按输入框粘贴',
      'empty' => which == 'user' ? '没有账号可复制' : '没有密码可复制',
      _ => '复制失败',
    });
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final store = context.watch<CloneStore>();
    final titles = <String, String>{
      for (final c in store.clones) 'w:${c.packageName}': c.customName,
      for (final c in store.clones) c.packageName: c.customName,
    };
    final groups = groupVaultEntries(_entries, currentPackage: _currentPkg, titles: titles);
    return Scaffold(
      body: BlobBackground(
        child: SafeArea(
          child: ListView(
            physics: const BouncingScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
            children: [
              Row(children: [
                IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.arrow_back_rounded)),
                Expanded(child: Text('账号密码本', style: tt.headlineMedium)),
              ]),
              const Padding(
                padding: EdgeInsets.fromLTRB(4, 8, 4, 12),
                child: Text('账号和密码保存在本机。可一键填入；也可分别复制账号或密码。未开无障碍时会自动复制并提示粘贴。'),
              ),
              if (_loading)
                const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()))
              else ...[
                if (_error != null) Padding(padding: const EdgeInsets.only(bottom: 12), child: Text(_error!)),
                Align(
                  alignment: Alignment.centerLeft,
                  child: FilledButton.icon(
                    onPressed: () => _edit(),
                    icon: const Icon(Icons.add_rounded),
                    label: const Text('添加'),
                  ),
                ),
                if (groups.isEmpty)
                  const Padding(padding: EdgeInsets.only(top: 16), child: Text('还没有账号。可以按分身或包名分别保存。'))
                else
                  for (final g in groups) ...[
                    Padding(
                      padding: const EdgeInsets.fromLTRB(4, 16, 4, 8),
                      child: Text(g.current ? '当前分身 · ${g.title}' : g.title, style: tt.titleMedium),
                    ),
                    Glass(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Column(
                        children: [
                          for (final e in g.entries)
                            ListTile(
                              title: Text(e.label),
                              subtitle: Text(e.username.isEmpty ? e.packageName : e.username),
                              onLongPress: () => _copy(e, 'pass'),
                              trailing: Wrap(
                                spacing: 0,
                                children: [
                                  IconButton(
                                    tooltip: '一键填入',
                                    onPressed: () => _fill(e),
                                    icon: const Icon(Icons.input_rounded),
                                  ),
                                  IconButton(
                                    tooltip: '复制账号',
                                    onPressed: () => _copy(e, 'user'),
                                    icon: const Icon(Icons.person_outline_rounded),
                                  ),
                                  IconButton(
                                    tooltip: '复制密码',
                                    onPressed: () => _copy(e, 'pass'),
                                    icon: const Icon(Icons.key_rounded),
                                  ),
                                  PopupMenuButton<String>(
                                    onSelected: (v) {
                                      if (v == 'edit') _edit(existing: e);
                                      if (v == 'delete') _delete(e);
                                    },
                                    itemBuilder: (_) => const [
                                      PopupMenuItem(value: 'edit', child: Text('编辑')),
                                      PopupMenuItem(value: 'delete', child: Text('删除')),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                        ],
                      ),
                    ),
                  ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _VaultEditor extends StatefulWidget {
  const _VaultEditor({this.existing, required this.currentPackage});
  final VaultEntry? existing;
  final String currentPackage;

  @override
  State<_VaultEditor> createState() => _VaultEditorState();
}

class _VaultEditorState extends State<_VaultEditor> {
  late final _label = TextEditingController(text: widget.existing?.label ?? '');
  late final _user = TextEditingController(text: widget.existing?.username ?? '');
  late final _pass = TextEditingController(text: widget.existing?.password ?? '');
  late final _pkg = TextEditingController(
    text: widget.existing?.packageName ?? widget.currentPackage,
  );
  var _obscure = true;
  var _busy = false;

  @override
  void dispose() {
    _label.dispose();
    _user.dispose();
    _pass.dispose();
    _pkg.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    final pkg = _pkg.text.trim();
    final r = await NativeBridge.vaultUpsert({
      'id': widget.existing?.id ?? '',
      'pkg': pkg,
      'clone': widget.existing?.cloneUid ?? (pkg.isEmpty ? '' : 'w:$pkg'),
      'label': _label.text.trim(),
      'user': _user.text.trim(),
      'pass': _pass.text,
    });
    if (!mounted) return;
    if (r == 'ok') {
      Navigator.pop(context, true);
      return;
    }
    setState(() => _busy = false);
    final msg = switch (r) {
      null => '保存失败',
      _ => r == 'error' ? '保存失败' : r,
    };
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.existing == null ? '添加账号' : '编辑账号'),
      content: SingleChildScrollView(
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          TextField(controller: _label, decoration: const InputDecoration(labelText: '名称', prefixIcon: Icon(Icons.badge_outlined)), textInputAction: TextInputAction.next),
          TextField(controller: _user, decoration: const InputDecoration(labelText: '用户名 / 手机 / 学号', prefixIcon: Icon(Icons.person_outline_rounded)), textInputAction: TextInputAction.next),
          TextField(
            controller: _pass,
            obscureText: _obscure,
            decoration: InputDecoration(
              labelText: '密码',
              prefixIcon: const Icon(Icons.lock_outline_rounded),
              suffixIcon: IconButton(
                onPressed: () => setState(() => _obscure = !_obscure),
                icon: Icon(_obscure ? Icons.visibility_rounded : Icons.visibility_off_rounded),
              ),
            ),
          ),
          TextField(controller: _pkg, decoration: const InputDecoration(labelText: '包名', prefixIcon: Icon(Icons.apps_rounded)), textInputAction: TextInputAction.done),
        ]),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context, false), child: const Text('取消')),
        FilledButton(onPressed: _busy ? null : _save, child: const Text('保存')),
      ],
    );
  }
}
