package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 悬浮密码本的分段和标题。不读包管理器，也不写密码本。
 */
public final class VaultAppHeading {
    public enum Kind { PENDING, NAMED, UNAVAILABLE, RELIABLY_UNINSTALLED }

    public static final class Row {
        public final String id;
        public final String pkg;
        public final String label;
        public final boolean currentClone;

        public Row(String id, String pkg, String label, boolean currentClone) {
            this.id = id == null ? "" : id;
            this.pkg = pkg == null ? "" : pkg;
            this.label = label == null ? "" : label;
            this.currentClone = currentClone;
        }
    }

    public static final class Section {
        public final String pkg;
        public final List<Row> rows;

        public Section(String pkg, List<Row> rows) {
            this.pkg = pkg == null ? "" : pkg;
            this.rows = rows;
        }
    }

    private VaultAppHeading() {}

    public static String heading(String pkg, Kind kind, String label) {
        String p = pkg == null ? "" : pkg;
        String name = label == null ? "" : label.trim();
        if (kind == Kind.RELIABLY_UNINSTALLED) {
            return p.isEmpty() ? "未安装" : p + " \u00B7 未安装";
        }
        if (kind == Kind.NAMED && !name.isEmpty()) return name;
        if (kind == Kind.PENDING) return p.isEmpty() ? "应用信息不可用" : p;
        return p.isEmpty() ? "应用信息不可用" : p + " \u00B7 应用信息不可用";
    }

    /** 查找失败或名称为空时返回 UNAVAILABLE。本方法不返回 RELIABLY_UNINSTALLED。 */
    public static Kind kindFromLookup(boolean found, String label, boolean nameNotFound, boolean failed) {
        if (failed || nameNotFound) return Kind.UNAVAILABLE;
        if (!found) return Kind.PENDING;
        if (label == null || label.trim().isEmpty()) return Kind.UNAVAILABLE;
        return Kind.NAMED;
    }

    public static List<Section> sections(List<Row> rows, String foregroundPkg) {
        String fg = foregroundPkg == null ? "" : foregroundPkg;
        Map<String, List<Row>> map = new LinkedHashMap<>();
        if (rows != null) {
            for (Row row : rows) {
                if (row == null) continue;
                map.computeIfAbsent(row.pkg, key -> new ArrayList<>()).add(row);
            }
        }
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(Comparator.comparingInt((String pkg) -> pkgRank(pkg, fg)).thenComparing(pkg -> pkg));
        List<Section> out = new ArrayList<>();
        for (String key : keys) {
            List<Row> grouped = map.get(key);
            grouped.sort(
                Comparator.comparingInt((Row row) -> row.currentClone ? 0 : 1)
                    .thenComparing(row -> row.label)
                    .thenComparing(row -> row.id)
            );
            out.add(new Section(key, grouped));
        }
        return out;
    }

    private static int pkgRank(String pkg, String foregroundPkg) {
        if (pkg.isEmpty()) return 2;
        if (!foregroundPkg.isEmpty() && pkg.equals(foregroundPkg)) return 0;
        return 1;
    }
}
