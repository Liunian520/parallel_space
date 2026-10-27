import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../services/vault_group.dart';
import '../state/clone_store.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';

/// 主应用里的密码本。和悬浮窗共用同一份加密存储，打开前走生物识别或设备锁。
class VaultScreen extends StatefulWidget {
  const VaultScreen({super.key});

  @override
  State<VaultScreen> createState() => _VaultScreenState();
}

class _VaultScreenState extends State<VaultScreen> {
  var _locked = true;
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
    await _unlock();
  }

  Future<void> _unlock() async {
    setState(() => _loading = true);
    final r = await NativeBridge.vaultUnlock();
    if (!mounted) return;
    if (r != 'ok') {
      setState(() {
        _locked = true;
        _loading = false;
        _error = switch (r) {
          'need_lock' => '请先在系统设置里设置锁屏密码或生物识别。',
          'cancelled' => '已取消验证。',
          _ => '验证失败，请再试一次。',
        };
        _entries = const [];
      });
      return;
    }
    await _reload();
  }

  Future<void> _reload() async {
    final raw = await NativeBridge.vaultList();
    if (!mounted) return;
    final locked = raw?['locked'] == true;
    final list = raw?['entries'];
    setState(() {
      _locked = locked;
      _loading = false;
      _error = locked ? '验证已过期，请重新验证。' : null;
      _entries = [
        if (!locked && list is List) ...list.map(VaultEntry.fromMap).nonNulls,
      ];
    });
  }

  Future<void> _lock() async {
    await NativeBridge.vaultLock();
    if (!mounted) return;
    setState(() {
      _locked = true;
      _entries = const [];
      _error = null;
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
      showGlassSnack(context, r == 'locked' ? '请重新验证' : '删除失败');
    }
  }

  Future<void> _fill(VaultEntry e) async {
    final r = await NativeBridge.vaultFill(e.id);
    if (!mounted) return;
    showGlassSnack(context, switch (r) {
      'filled' => '已填入当前输入框',
      'copied' => '未开启无障碍，已复制到剪贴板，30 秒后清除',
      'copied_no_field' => '没有找到输入框，已复制到剪贴板，30 秒后清除',
      'locked' || 'need_auth' => '请重新验证',
      _ => '填入失败',
    });
    if (r == 'locked' || r == 'need_auth') {
      setState(() {
        _locked = true;
        _entries = const [];
      });
    }
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
                if (!_locked) IconButton(tooltip: '锁定', onPressed: _lock, icon: const Icon(Icons.lock_rounded)),
              ]),
              if (_loading)
                const Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()))
              else if (_locked) ...[
                const Padding(
                  padding: EdgeInsets.fromLTRB(4, 12, 4, 12),
                  child: Text('账号和密码用 Android Keystore 里的 AES-GCM 密钥加密，只保存在本机。查看前需要生物识别或锁屏密码。'),
                ),
                if (_error != null) Padding(padding: const EdgeInsets.only(bottom: 12), child: Text(_error!)),
                FilledButton(onPressed: _unlock, child: const Text('验证后查看')),
                if (_error != null && _error!.contains('锁屏'))
                  TextButton(onPressed: () => NativeBridge.compatOpen('lock'), child: const Text('去设置锁屏')),
              ] else ...[
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
                              trailing: PopupMenuButton<String>(
                                onSelected: (v) {
                                  if (v == 'fill') _fill(e);
                                  if (v == 'edit') _edit(existing: e);
                                  if (v == 'delete') _delete(e);
                                },
                                itemBuilder: (_) => const [
                                  PopupMenuItem(value: 'fill', child: Text('填入')),
                                  PopupMenuItem(value: 'edit', child: Text('编辑')),
                                  PopupMenuItem(value: 'delete', child: Text('删除')),
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
      'need_lock' => '请先设置锁屏',
      'locked' || 'need_auth' => '请重新验证',
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
          TextField(controller: _label, decoration: const InputDecoration(labelText: '名称'), textInputAction: TextInputAction.next),
          TextField(controller: _user, decoration: const InputDecoration(labelText: '用户名'), textInputAction: TextInputAction.next),
          TextField(
            controller: _pass,
            obscureText: _obscure,
            decoration: InputDecoration(
              labelText: '密码',
              suffixIcon: IconButton(
                onPressed: () => setState(() => _obscure = !_obscure),
                icon: Icon(_obscure ? Icons.visibility_rounded : Icons.visibility_off_rounded),
              ),
            ),
          ),
          TextField(controller: _pkg, decoration: const InputDecoration(labelText: '包名'), textInputAction: TextInputAction.done),
        ]),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context, false), child: const Text('取消')),
        FilledButton(onPressed: _busy ? null : _save, child: const Text('保存')),
      ],
    );
  }
}
