import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/clone_app.dart';
import '../services/clone_actions.dart';
import '../services/native_bridge.dart';
import '../services/vault_group.dart';
import '../state/clone_store.dart';
import '../widgets/app_icon.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';

/// 主应用里的密码本：添加 / 编辑 / 删除都在这里完成。
/// 悬浮工具箱只读，共用原生 `vault_plain.json`。
class VaultScreen extends StatefulWidget {
  const VaultScreen({super.key, this.initialPackage = '', this.openEditor = false});

  /// 从工具箱或分身详情带入时预填所属分身。
  final String initialPackage;

  /// 为 true 时打开后直接弹出添加对话框（预填 [initialPackage]）。
  final bool openEditor;

  @override
  State<VaultScreen> createState() => _VaultScreenState();
}

class _VaultScreenState extends State<VaultScreen> {
  var _loading = true;
  String? _error;
  List<VaultEntry> _entries = const [];
  String _currentPkg = '';
  String _query = '';
  final _search = TextEditingController();

  @override
  void initState() {
    super.initState();
    _boot();
  }

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  Future<void> _boot() async {
    _currentPkg = widget.initialPackage.isNotEmpty
        ? widget.initialPackage
        : (await NativeBridge.toolboxCurrentPackage() ?? '');
    if (!mounted) return;
    await _reload();
    if (!mounted) return;
    if (widget.openEditor) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _edit();
      });
    }
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
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      builder: (_) => _VaultEditorSheet(
        existing: existing,
        initialPackage: existing?.packageName ??
            (widget.initialPackage.isNotEmpty ? widget.initialPackage : _currentPkg),
      ),
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

  Future<void> _copy(VaultEntry e, String which) async {
    final r = await NativeBridge.vaultCopy(e.id, which);
    if (!mounted) return;
    showGlassSnack(context, switch (r) {
      'copied_user' => '已复制账号',
      'copied_pass' => '已复制密码',
      'empty' => which == 'user' ? '没有账号可复制' : '没有密码可复制',
      _ => '复制失败',
    });
  }

  @override
  Widget build(BuildContext context) {
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;
    final store = context.watch<CloneStore>();
    final titles = <String, String>{
      for (final c in store.clones) 'w:${c.packageName}': c.customName,
      for (final c in store.clones) c.packageName: c.customName,
      for (final c in store.clones) c.uid: c.customName,
    };
    final filtered = filterVaultEntries(_entries, _query);
    final groups = groupVaultEntries(
      filtered,
      currentPackage: _currentPkg.isNotEmpty ? _currentPkg : widget.initialPackage,
      titles: titles,
    );

    return Scaffold(
      body: BlobBackground(
        child: SafeArea(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 16, 0),
                child: Row(children: [
                  IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.arrow_back_rounded)),
                  Expanded(child: Text('密码本', style: tt.headlineMedium)),
                  FilledButton.tonalIcon(
                    onPressed: () => _edit(),
                    icon: const Icon(Icons.add_rounded),
                    label: const Text('添加'),
                  ),
                ]),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
                child: Text(
                  '在这里管理账号和密码。分身里的工具箱只能查看、一键填入和复制，不再负责添加。',
                  style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Glass(
                  radius: 18,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: TextField(
                    controller: _search,
                    onChanged: (v) => setState(() => _query = v),
                    decoration: InputDecoration(
                      hintText: '搜索备注名、账号、分身…',
                      border: InputBorder.none,
                      prefixIcon: const Icon(Icons.search_rounded),
                      suffixIcon: _query.isEmpty
                          ? null
                          : IconButton(
                              onPressed: () {
                                _search.clear();
                                setState(() => _query = '');
                              },
                              icon: const Icon(Icons.close_rounded),
                            ),
                    ),
                  ),
                ),
              ),
              Expanded(
                child: _loading
                    ? const Center(child: CircularProgressIndicator())
                    : RefreshIndicator(
                        onRefresh: _reload,
                        child: ListView(
                          physics: const AlwaysScrollableScrollPhysics(parent: BouncingScrollPhysics()),
                          padding: const EdgeInsets.fromLTRB(20, 12, 20, 40),
                          children: [
                            if (_error != null)
                              Padding(
                                padding: const EdgeInsets.only(bottom: 12),
                                child: Text(_error!, style: TextStyle(color: s.error)),
                              ),
                            if (groups.isEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 48),
                                child: Column(children: [
                                  Icon(Icons.key_off_rounded, size: 48, color: s.onSurfaceVariant),
                                  const SizedBox(height: 12),
                                  Text(
                                    _query.isEmpty ? '还没有账号' : '没有匹配的账号',
                                    style: tt.titleMedium,
                                  ),
                                  const SizedBox(height: 8),
                                  Text(
                                    _query.isEmpty
                                        ? '点右上角「添加」，选择所属分身后保存。'
                                        : '试试别的关键字。',
                                    textAlign: TextAlign.center,
                                    style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant),
                                  ),
                                  if (_query.isEmpty) ...[
                                    const SizedBox(height: 16),
                                    FilledButton.icon(
                                      onPressed: () => _edit(),
                                      icon: const Icon(Icons.add_rounded),
                                      label: const Text('添加第一条'),
                                    ),
                                  ],
                                ]),
                              )
                            else
                              for (final g in groups) ...[
                                Padding(
                                  padding: const EdgeInsets.fromLTRB(4, 8, 4, 8),
                                  child: Row(children: [
                                    if (g.entries.isNotEmpty)
                                      AppIcon(
                                        packageName: g.entries.first.packageName,
                                        label: g.title,
                                        size: 28,
                                      ),
                                    const SizedBox(width: 10),
                                    Expanded(
                                      child: Text(
                                        g.current ? '当前分身 · ${g.title}' : g.title,
                                        style: tt.titleMedium,
                                      ),
                                    ),
                                  ]),
                                ),
                                Glass(
                                  padding: const EdgeInsets.symmetric(vertical: 4),
                                  child: Column(
                                    children: [
                                      for (final e in g.entries)
                                        _EntryTile(
                                          entry: e,
                                          onEdit: () => _edit(existing: e),
                                          onDelete: () => _delete(e),
                                          onCopyUser: () => _copy(e, 'user'),
                                          onCopyPass: () => _copy(e, 'pass'),
                                        ),
                                    ],
                                  ),
                                ),
                              ],
                          ],
                        ),
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _EntryTile extends StatefulWidget {
  const _EntryTile({
    required this.entry,
    required this.onEdit,
    required this.onDelete,
    required this.onCopyUser,
    required this.onCopyPass,
  });

  final VaultEntry entry;
  final VoidCallback onEdit;
  final VoidCallback onDelete;
  final VoidCallback onCopyUser;
  final VoidCallback onCopyPass;

  @override
  State<_EntryTile> createState() => _EntryTileState();
}

class _EntryTileState extends State<_EntryTile> {
  var _showPass = false;

  @override
  Widget build(BuildContext context) {
    final e = widget.entry;
    final s = Theme.of(context).colorScheme;
    final passText = e.password.isEmpty
        ? '未填写密码'
        : _showPass
            ? e.password
            : '•' * e.password.length.clamp(6, 12);
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      title: Text(e.label, style: const TextStyle(fontWeight: FontWeight.w700)),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(e.username.isEmpty ? '未填写账号' : e.username),
          const SizedBox(height: 2),
          Row(children: [
            Expanded(child: Text(passText, style: TextStyle(color: s.onSurfaceVariant, fontSize: 12))),
            TextButton(
              onPressed: e.password.isEmpty ? null : () => setState(() => _showPass = !_showPass),
              child: Text(_showPass ? '隐藏' : '显示'),
            ),
          ]),
          if (e.note.isNotEmpty)
            Text(e.note, style: TextStyle(color: s.onSurfaceVariant, fontSize: 12)),
        ],
      ),
      isThreeLine: true,
      trailing: PopupMenuButton<String>(
        onSelected: (v) {
          switch (v) {
            case 'copy_user':
              widget.onCopyUser();
            case 'copy_pass':
              widget.onCopyPass();
            case 'edit':
              widget.onEdit();
            case 'delete':
              widget.onDelete();
          }
        },
        itemBuilder: (_) => const [
          PopupMenuItem(value: 'copy_user', child: Text('复制账号')),
          PopupMenuItem(value: 'copy_pass', child: Text('复制密码')),
          PopupMenuItem(value: 'edit', child: Text('编辑')),
          PopupMenuItem(value: 'delete', child: Text('删除')),
        ],
      ),
    );
  }
}

class _VaultEditorSheet extends StatefulWidget {
  const _VaultEditorSheet({this.existing, required this.initialPackage});

  final VaultEntry? existing;
  final String initialPackage;

  @override
  State<_VaultEditorSheet> createState() => _VaultEditorSheetState();
}

class _VaultEditorSheetState extends State<_VaultEditorSheet> {
  late final _label = TextEditingController(text: widget.existing?.label ?? '');
  late final _user = TextEditingController(text: widget.existing?.username ?? '');
  late final _pass = TextEditingController(text: widget.existing?.password ?? '');
  late final _note = TextEditingController(text: widget.existing?.note ?? '');
  late String _pkg = widget.existing?.packageName ?? widget.initialPackage;
  late String _cloneUid = widget.existing?.cloneUid ??
      (_pkg.isEmpty ? '' : CloneApp.uidFor(_pkg));
  var _obscure = true;
  var _busy = false;

  @override
  void dispose() {
    _label.dispose();
    _user.dispose();
    _pass.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_pkg.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('请选择所属分身')));
      return;
    }
    setState(() => _busy = true);
    final r = await NativeBridge.vaultUpsert({
      'id': widget.existing?.id ?? '',
      'pkg': _pkg.trim(),
      'clone': _cloneUid.isEmpty ? CloneApp.uidFor(_pkg.trim()) : _cloneUid,
      'label': _label.text.trim(),
      'user': _user.text.trim(),
      'pass': _pass.text,
      'note': _note.text.trim(),
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
    final s = Theme.of(context).colorScheme;
    final clones = context.watch<CloneStore>().clones;
    final bottom = MediaQuery.viewInsetsOf(context).bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottom),
      child: Glass(
        radius: 28,
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  width: 40,
                  height: 4,
                  margin: const EdgeInsets.only(bottom: 12),
                  decoration: BoxDecoration(
                    color: s.onSurfaceVariant.withValues(alpha: 0.35),
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              Text(
                widget.existing == null ? '添加账号' : '编辑账号',
                style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 4),
              Text(
                '保存在本机，方便工具箱一键填入。不设生物识别门禁。',
                style: Theme.of(context).textTheme.bodySmall?.copyWith(color: s.onSurfaceVariant),
              ),
              const SizedBox(height: 16),
              InputDecorator(
                decoration: const InputDecoration(
                  labelText: '所属分身',
                  prefixIcon: Icon(Icons.apps_rounded),
                  border: OutlineInputBorder(),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<String>(
                    isExpanded: true,
                    value: clones.any((c) => c.packageName == _pkg)
                        ? _pkg
                        : (_pkg.isEmpty ? null : _pkg),
                    hint: const Text('选择分身'),
                    items: [
                      for (final c in clones)
                        DropdownMenuItem(
                          value: c.packageName,
                          child: Row(children: [
                            AppIcon(packageName: c.packageName, label: c.customName, size: 28),
                            const SizedBox(width: 10),
                            Expanded(child: Text(c.customName, overflow: TextOverflow.ellipsis)),
                          ]),
                        ),
                      if (_pkg.isNotEmpty && clones.every((c) => c.packageName != _pkg))
                        DropdownMenuItem(value: _pkg, child: Text(_pkg)),
                    ],
                    onChanged: (v) {
                      if (v == null) return;
                      setState(() {
                        _pkg = v;
                        _cloneUid = CloneApp.uidFor(v);
                      });
                    },
                  ),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _label,
                decoration: const InputDecoration(
                  labelText: '备注名',
                  hintText: '例如主号、学号登录',
                  prefixIcon: Icon(Icons.badge_outlined),
                  border: OutlineInputBorder(),
                ),
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _user,
                decoration: const InputDecoration(
                  labelText: '账号',
                  hintText: '用户名 / 手机 / 学号',
                  prefixIcon: Icon(Icons.person_outline_rounded),
                  border: OutlineInputBorder(),
                ),
                textInputAction: TextInputAction.next,
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _pass,
                obscureText: _obscure,
                decoration: InputDecoration(
                  labelText: '密码',
                  prefixIcon: const Icon(Icons.lock_outline_rounded),
                  border: const OutlineInputBorder(),
                  suffixIcon: IconButton(
                    onPressed: () => setState(() => _obscure = !_obscure),
                    icon: Icon(_obscure ? Icons.visibility_rounded : Icons.visibility_off_rounded),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _note,
                decoration: const InputDecoration(
                  labelText: '备注（可选）',
                  prefixIcon: Icon(Icons.notes_rounded),
                  border: OutlineInputBorder(),
                ),
                maxLines: 2,
              ),
              const SizedBox(height: 20),
              Row(children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _busy ? null : () => Navigator.pop(context, false),
                    child: const Text('取消'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: _busy ? null : _save,
                    child: _busy
                        ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('保存'),
                  ),
                ),
              ]),
            ],
          ),
        ),
      ),
    );
  }
}

/// 打开密码本页的统一入口。
Future<void> openVaultScreen(
  BuildContext context, {
  String initialPackage = '',
  bool openEditor = false,
}) {
  return Navigator.of(context).push(
    CupertinoPageRoute(
      builder: (_) => VaultScreen(initialPackage: initialPackage, openEditor: openEditor),
    ),
  );
}
