import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/clone_app.dart';
import 'clone_card.dart';

/// 自适应列数的懒加载网格（SliverGrid.builder，只构建可见卡片），长按拖拽实时排序。
///
/// 列数按可用宽度计算：手机 2 列，大屏 / 折叠屏展开 / 平板 3~6 列；
/// 卡片高度随系统字体缩放增加，避免大字体下溢出。
class ReorderableCloneGrid extends StatefulWidget {
  const ReorderableCloneGrid({
    super.key,
    required this.clones,
    required this.onReorder,
    required this.onTap,
    required this.onOpen,
    required this.onDelete,
    required this.onPin,
    this.mockingUid,
    this.padding = const EdgeInsets.fromLTRB(20, 8, 20, 140),
  });

  final List<CloneApp> clones;
  final void Function(String fromUid, String toUid) onReorder;
  final ValueChanged<CloneApp> onTap;
  final ValueChanged<CloneApp> onOpen;
  final ValueChanged<CloneApp> onDelete;
  final ValueChanged<CloneApp> onPin;
  final String? mockingUid;
  final EdgeInsets padding;

  /// 列数（公开以便测试）。
  static int columnsFor(double width) => (width / 190).floor().clamp(2, 6);

  /// 卡片高度：基础 160，字体放大时按比例增加（textScaler 最大 1.3）。
  static double extentFor(TextScaler scaler, {bool withChip = true}) =>
      (withChip ? 170.0 : 150.0) + (scaler.scale(14) - 14) / 14 * 90;

  @override
  State<ReorderableCloneGrid> createState() => _ReorderableCloneGridState();
}

class _ReorderableCloneGridState extends State<ReorderableCloneGrid> {
  static const _gap = 14.0;
  String? _dragging;

  @override
  Widget build(BuildContext context) {
    final items = widget.clones;
    final extent = ReorderableCloneGrid.extentFor(MediaQuery.textScalerOf(context));
    return LayoutBuilder(builder: (context, box) {
      final cols = ReorderableCloneGrid.columnsFor(box.maxWidth - widget.padding.horizontal);
      final w = (box.maxWidth - widget.padding.horizontal - _gap * (cols - 1)) / cols;
      return CustomScrollView(
        physics: const BouncingScrollPhysics(parent: AlwaysScrollableScrollPhysics()),
        slivers: [
          SliverPadding(
            padding: widget.padding,
            sliver: SliverGrid.builder(
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: cols,
                mainAxisSpacing: _gap,
                crossAxisSpacing: _gap,
                mainAxisExtent: extent,
              ),
              itemCount: items.length,
              findChildIndexCallback: (key) {
                final i = items.indexWhere((c) => ValueKey(c.uid) == key);
                return i < 0 ? null : i;
              },
              itemBuilder: (context, i) => _cell(items[i], w, extent),
            ),
          ),
        ],
      );
    });
  }

  Widget _cell(CloneApp c, double w, double h) {
    final card = CloneCard(
      clone: c,
      onTap: () => widget.onTap(c),
      onOpen: () => widget.onOpen(c),
      onDelete: () => widget.onDelete(c),
      onPin: () => widget.onPin(c),
      mocking: widget.mockingUid == c.uid,
    );
    return RepaintBoundary(
      key: ValueKey(c.uid),
      child: DragTarget<String>(
        onWillAcceptWithDetails: (d) {
          if (d.data != c.uid && widget.clones.any((e) => e.uid == d.data)) {
            HapticFeedback.selectionClick();
            widget.onReorder(d.data, c.uid);
          }
          return true;
        },
        builder: (context, _, _) => LongPressDraggable<String>(
          data: c.uid,
          hapticFeedbackOnStart: true,
          onDragStarted: () => setState(() => _dragging = c.uid),
          onDragEnd: (_) => setState(() => _dragging = null),
          feedback: SizedBox(
            width: w,
            height: h,
            child: Transform.scale(
              scale: 1.05,
              child: Material(
                type: MaterialType.transparency,
                child: CloneCard(clone: c, onTap: () {}, onOpen: () {}, onDelete: () {}, onPin: () {}, dragging: true),
              ),
            ),
          ),
          childWhenDragging: Opacity(opacity: _dragging == c.uid ? 0.25 : 1, child: card),
          child: card,
        ),
      ),
    );
  }
}
