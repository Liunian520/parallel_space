import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/clone_actions.dart';
import '../services/clone_notification_inbox.dart';
import '../services/native_bridge.dart';
import '../state/clone_store.dart';
import '../widgets/blob_background.dart';
import '../widgets/glass.dart';

class CloneNotificationsScreen extends StatefulWidget {
  const CloneNotificationsScreen({super.key});

  @override
  State<CloneNotificationsScreen> createState() => _CloneNotificationsScreenState();
}

class _CloneNotificationsScreenState extends State<CloneNotificationsScreen> {
  final _gate = RefreshGate();
  var _loading = true;
  var _failed = false;
  List<CloneNoteActive> _items = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final token = _gate.begin();
    final raw = await NativeBridge.cloneNotifications();
    if (!mounted || !_gate.accept(token)) return;
    final ok = raw?['ok'] == true;
    final list = raw?['items'];
    setState(() {
      _loading = false;
      _failed = !ok;
      _items = ok && list is List ? [for (final item in list) _active(item)].whereType<CloneNoteActive>().toList() : const [];
    });
  }

  CloneNoteActive? _active(Object? raw) {
    if (raw is! Map) return null;
    final tag = raw['tag'];
    final id = raw['id'];
    if (tag is! String || id is! num) return null;
    if (parseCloneNotificationTag(tag) == null) return null;
    return CloneNoteActive(
      tag: tag,
      id: id.toInt(),
      postTime: raw['postTime'] is num ? (raw['postTime'] as num).toInt() : 0,
      title: raw['title'] is String ? raw['title'] as String : '',
      text: raw['text'] is String ? raw['text'] as String : '',
      groupSummary: raw['groupSummary'] == true,
    );
  }

  Future<void> _open(String tag) async {
    final store = context.read<CloneStore>();
    final known = [for (final c in store.clones) CloneNoteKnown(pkg: c.packageName, userId: c.userId, name: c.customName)];
    final parsed = shouldLaunchCloneNotification(tag, known);
    if (parsed == null) {
      showGlassSnack(context, '当前没有这个分身');
      await _load();
      return;
    }
    final result = await NativeBridge.launchVirtual(parsed.pkg, userId: parsed.userId);
    if (!mounted) return;
    final code = result['r'] as String? ?? 'error';
    if (code != 'ok') {
      showGlassSnack(context, code == 'not_found' ? '分身不存在，请重新添加' : '启动失败：$code');
    }
  }

  Future<void> _clear(CloneNoteRow row) async {
    final code = await NativeBridge.cloneNotificationCancel(row.systemTag, row.systemId);
    if (!mounted) return;
    if (code != 'ok') showGlassSnack(context, '清除失败');
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<CloneStore>();
    final known = [for (final c in store.clones) CloneNoteKnown(pkg: c.packageName, userId: c.userId, name: c.customName)];
    final rows = presentCloneNotifications(_items, known);
    final tt = Theme.of(context).textTheme;
    final s = Theme.of(context).colorScheme;
    return Scaffold(
      body: BlobBackground(
        child: SafeArea(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 16, 0),
                child: Row(children: [
                  IconButton(onPressed: () => Navigator.pop(context), icon: const Icon(Icons.arrow_back_rounded)),
                  Expanded(child: Text('分身通知', style: tt.headlineMedium)),
                ]),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text('工作资料里的应用通知不在此列表。', style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant)),
                ),
              ),
              Expanded(
                child: RefreshIndicator(
                  onRefresh: _load,
                  child: _loading
                      ? ListView(physics: const AlwaysScrollableScrollPhysics(), children: const [SizedBox(height: 120), Center(child: CircularProgressIndicator())])
                      : ListView(
                          physics: const AlwaysScrollableScrollPhysics(parent: BouncingScrollPhysics()),
                          padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
                          children: [
                            if (_failed)
                              Padding(
                                padding: const EdgeInsets.only(top: 32),
                                child: Text('暂时读不到通知', style: tt.titleMedium),
                              )
                            else if (rows.isEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 32),
                                child: Text('现在没有正在显示的虚拟分身通知。', style: tt.titleMedium),
                              )
                            else
                              for (final row in rows)
                                Padding(
                                  padding: const EdgeInsets.only(bottom: 12),
                                  child: Glass(
                                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
                                    child: Column(
                                      crossAxisAlignment: CrossAxisAlignment.start,
                                      children: [
                                        Text(row.name, style: tt.titleMedium),
                                        if (row.groupSummary)
                                          Text('分组摘要', style: tt.labelMedium?.copyWith(color: s.onSurfaceVariant)),
                                        const SizedBox(height: 4),
                                        Text(row.title, style: tt.bodyLarge),
                                        if (row.body.isNotEmpty) Text(row.body, style: tt.bodyMedium?.copyWith(color: s.onSurfaceVariant)),
                                        const SizedBox(height: 8),
                                        Row(children: [
                                          if (row.canOpen)
                                            TextButton(onPressed: () => _open(row.systemTag), child: const Text('打开')),
                                          TextButton(onPressed: () => _clear(row), child: const Text('清除')),
                                        ]),
                                      ],
                                    ),
                                  ),
                                ),
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
