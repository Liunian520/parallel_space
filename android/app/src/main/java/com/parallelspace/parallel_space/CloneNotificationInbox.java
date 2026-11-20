package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次活跃通知快照收成可显示的行，并决定能否清除。
 * 不读索引，也不提供全部清除。
 */
public final class CloneNotificationInbox {
    public static final String ORPHAN = "当前没有这个分身";
    public static final String NO_TITLE = "这条通知没有标题";

    public static final class Active {
        public final String tag;
        public final int id;
        public final long postTime;
        public final String title;
        public final String text;
        public final boolean groupSummary;

        public Active(String tag, int id, long postTime, String title, String text, boolean groupSummary) {
            this.tag = tag == null ? "" : tag;
            this.id = id;
            this.postTime = postTime;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
            this.groupSummary = groupSummary;
        }
    }

    public static final class Known {
        public final String pkg;
        public final int userId;
        public final String name;

        public Known(String pkg, int userId, String name) {
            this.pkg = pkg == null ? "" : pkg;
            this.userId = userId;
            this.name = name == null ? "" : name;
        }
    }

    public static final class Row {
        public final String systemTag;
        public final int systemId;
        public final String pkg;
        public final int userId;
        public final String uid;
        public final String name;
        public final String title;
        public final String body;
        public final boolean groupSummary;
        public final boolean canOpen;
        public final long postTime;

        public Row(String systemTag, int systemId, String pkg, int userId, String uid, String name,
                   String title, String body, boolean groupSummary, boolean canOpen, long postTime) {
            this.systemTag = systemTag;
            this.systemId = systemId;
            this.pkg = pkg;
            this.userId = userId;
            this.uid = uid;
            this.name = name;
            this.title = title;
            this.body = body;
            this.groupSummary = groupSummary;
            this.canOpen = canOpen;
            this.postTime = postTime;
        }
    }

    private CloneNotificationInbox() {}

    public static List<Row> present(List<Active> active, List<Known> clones) {
        Map<String, Active> newest = new LinkedHashMap<String, Active>();
        if (active != null) {
            for (Active item : active) {
                if (item == null) continue;
                CloneNotificationPolicy.Parsed parsed = CloneNotificationPolicy.parse(item.tag);
                if (parsed == null) continue;
                String key = item.tag + "\u0000" + item.id;
                Active prev = newest.get(key);
                if (prev == null || item.postTime >= prev.postTime) newest.put(key, item);
            }
        }
        List<Row> rows = new ArrayList<Row>();
        for (Active item : newest.values()) {
            CloneNotificationPolicy.Parsed parsed = CloneNotificationPolicy.parse(item.tag);
            if (parsed == null || !CloneNotificationPolicy.belongsTo(item.tag, parsed.pkg, parsed.userId)) continue;
            Known known = match(clones, parsed.pkg, parsed.userId);
            boolean open = known != null;
            String name = open && !known.name.trim().isEmpty() ? known.name.trim() : (open ? parsed.pkg : ORPHAN);
            String title = item.title.trim();
            String body = item.text.trim();
            if (title.isEmpty() && body.isEmpty()) title = NO_TITLE;
            rows.add(new Row(item.tag, item.id, parsed.pkg, parsed.userId, parsed.uid, name, title, body,
                    item.groupSummary, open, item.postTime));
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int byTime = Long.compare(b.postTime, a.postTime);
                if (byTime != 0) return byTime;
                int byTag = a.systemTag.compareTo(b.systemTag);
                if (byTag != 0) return byTag;
                return Integer.compare(a.systemId, b.systemId);
            }
        });
        return rows;
    }

    /** 只有解析成功、属于该 tag 自己的身份、且当前活跃列表仍有同一 tag 和 id 时才允许 cancel。 */
    public static boolean allowCancel(String tag, int id, List<Active> activeNow) {
        CloneNotificationPolicy.Parsed parsed = CloneNotificationPolicy.parse(tag);
        if (parsed == null || !CloneNotificationPolicy.belongsTo(tag, parsed.pkg, parsed.userId)) return false;
        if (activeNow == null) return false;
        for (Active item : activeNow) {
            if (item != null && tag.equals(item.tag) && item.id == id) return true;
        }
        return false;
    }

    private static Known match(List<Known> clones, String pkg, int userId) {
        if (clones == null) return null;
        for (Known clone : clones) {
            if (clone != null && pkg.equals(clone.pkg) && userId == clone.userId) return clone;
        }
        return null;
    }
}
