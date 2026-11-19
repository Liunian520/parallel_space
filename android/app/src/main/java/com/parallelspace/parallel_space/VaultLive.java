package com.parallelspace.parallel_space;

import java.util.List;

/**
 * 密码本刷新和点击决策。不读磁盘，也不写日志。
 * 点击只接受调用方此刻拿到的列表，不保留旧记录。
 */
public final class VaultLive {
    public enum Action { COPY, MISSING, EMPTY }

    public static final class Row {
        public final String id;
        public final String pkg;
        public final String clone;
        public final String label;
        public final String user;
        public final String pass;
        public final String note;

        public Row(String id, String pkg, String clone, String label, String user, String pass, String note) {
            this.id = id == null ? "" : id;
            this.pkg = pkg == null ? "" : pkg;
            this.clone = clone == null ? "" : clone;
            this.label = label == null ? "" : label;
            this.user = user == null ? "" : user;
            this.pass = pass == null ? "" : pass;
            this.note = note == null ? "" : note;
        }

        boolean same(Row other) {
            return other != null
                && id.equals(other.id)
                && pkg.equals(other.pkg)
                && clone.equals(other.clone)
                && label.equals(other.label)
                && user.equals(other.user)
                && pass.equals(other.pass)
                && note.equals(other.note);
        }
    }

    public static final class Decision {
        public final Action action;
        public final String text;

        private Decision(Action action, String text) {
            this.action = action;
            this.text = text == null ? "" : text;
        }
    }

    public static final class Fill {
        public final boolean missing;
        public final String user;
        public final String pass;

        private Fill(boolean missing, String user, String pass) {
            this.missing = missing;
            this.user = user == null ? "" : user;
            this.pass = pass == null ? "" : pass;
        }
    }

    private VaultLive() {}

    public static boolean sameSnapshot(List<Row> left, List<Row> right) {
        if (left == right) return true;
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            Row a = left.get(i);
            Row b = right.get(i);
            if (a == null || !a.same(b)) return false;
        }
        return true;
    }

    /** saved 与 max 都是像素。超出范围夹到 0..max。 */
    public static int clampScroll(int saved, int max) {
        if (saved <= 0 || max <= 0) return 0;
        return Math.min(saved, max);
    }

    public static Decision onField(List<Row> live, String id, String which) {
        Row found = find(live, id);
        if (found == null) return new Decision(Action.MISSING, "");
        boolean account = "user".equals(which) || "username".equals(which) || "account".equals(which);
        String text = account ? found.user : found.pass;
        if (text.isEmpty()) return new Decision(Action.EMPTY, "");
        return new Decision(Action.COPY, text);
    }

    public static Fill onFill(List<Row> live, String id) {
        Row found = find(live, id);
        if (found == null) return new Fill(true, "", "");
        return new Fill(false, found.user, found.pass);
    }

    private static Row find(List<Row> live, String id) {
        if (live == null || id == null || id.isEmpty()) return null;
        for (Row row : live) {
            if (row != null && id.equals(row.id)) return row;
        }
        return null;
    }
}
