package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 入口是 main，不是 JUnit。 */
public final class CloneNotificationInboxTest {
    public static void main(String[] args) {
        String tag0 = CloneNotificationPolicy.systemTag("com.a", 0, 7, "t");
        CloneNotificationPolicy.Parsed p0 = CloneNotificationPolicy.parse(tag0);
        check(p0 != null && "com.a".equals(p0.pkg) && p0.userId == 0 && p0.appId == 7 && "t".equals(p0.appTag), "parse first");
        check("w:com.a".equals(p0.uid), "uid 0");
        CloneNotificationPolicy.Parsed p1 = CloneNotificationPolicy.parse(CloneNotificationPolicy.systemTag("com.a", 1, 7, "t"));
        check(p1 != null && "w:com.a#1".equals(p1.uid) && p1.userId == 1, "uid 1");

        String splitTag = CloneNotificationPolicy.systemTag("com.a", 0, 3, "a" + CloneNotificationPolicy.SEP + "b");
        CloneNotificationPolicy.Parsed split = CloneNotificationPolicy.parse(splitTag);
        check(split != null && ("a" + CloneNotificationPolicy.SEP + "b").equals(split.appTag), "separator stays in app tag");

        check(CloneNotificationPolicy.parse(null) == null, "null tag");
        check(CloneNotificationPolicy.parse("toolbox") == null, "host tag");
        check(CloneNotificationPolicy.parse("ae1" + CloneNotificationPolicy.SEP + "-1" + CloneNotificationPolicy.SEP + "com.a" + CloneNotificationPolicy.SEP + "1" + CloneNotificationPolicy.SEP + "x") == null, "negative user");
        check(CloneNotificationPolicy.parse("ae1" + CloneNotificationPolicy.SEP + "1x" + CloneNotificationPolicy.SEP + "com.a" + CloneNotificationPolicy.SEP + "1" + CloneNotificationPolicy.SEP + "x") == null, "illegal user");
        check(CloneNotificationPolicy.parse("ae1" + CloneNotificationPolicy.SEP + "00" + CloneNotificationPolicy.SEP + "com.a" + CloneNotificationPolicy.SEP + "1" + CloneNotificationPolicy.SEP + "x") == null, "leading zero user");
        check(CloneNotificationPolicy.parse("ae1" + CloneNotificationPolicy.SEP + "2147483648" + CloneNotificationPolicy.SEP + "com.a" + CloneNotificationPolicy.SEP + "1" + CloneNotificationPolicy.SEP + "x") == null, "user overflow");
        check(CloneNotificationPolicy.parse("ae1" + CloneNotificationPolicy.SEP + "0" + CloneNotificationPolicy.SEP + "" + CloneNotificationPolicy.SEP + "1" + CloneNotificationPolicy.SEP + "x") == null, "empty pkg");
        check(CloneNotificationPolicy.parse("ae1" + CloneNotificationPolicy.SEP + "0" + CloneNotificationPolicy.SEP + "com.a") == null, "too few parts");

        String child = CloneNotificationPolicy.systemTag("com.mm", 1, 4, "child");
        String summary = CloneNotificationPolicy.systemTag("com.mm", 1, 5, "sum");
        String other = CloneNotificationPolicy.systemTag("com.mm", 0, 4, "child");
        String orphan = CloneNotificationPolicy.systemTag("com.gone", 2, 1, "x");
        List<CloneNotificationInbox.Active> active = Arrays.asList(
                active(child, 100, 10L, "子通知", "正文", false),
                active(child, 100, 20L, "新子通知", "新正文", false),
                active(summary, 101, 15L, "摘要", "", true),
                active(other, 102, 5L, "第一份", "a", false),
                active(orphan, 103, 30L, "孤儿", "还在", false),
                active("toolbox", 1, 99L, "宿主", "不要显示", false),
                active("ae1-bad", 8, 1L, "坏", "tag", false)
        );
        List<CloneNotificationInbox.Known> clones = Arrays.asList(
                new CloneNotificationInbox.Known("com.mm", 0, "大号"),
                new CloneNotificationInbox.Known("com.mm", 1, "小号")
        );
        List<CloneNotificationInbox.Row> rows = CloneNotificationInbox.present(active, clones);
        check(rows.size() == 4, "four visible rows, host and bad dropped, duplicate collapsed");
        check(rows.get(0).systemTag.equals(orphan) && !rows.get(0).canOpen && "当前没有这个分身".equals(rows.get(0).name), "orphan first by time");
        check("新子通知".equals(titleOf(rows, child)) && "新正文".equals(bodyOf(rows, child)), "newer duplicate wins");
        CloneNotificationInbox.Row sum = find(rows, summary);
        check(sum.groupSummary && sum.canOpen && "小号".equals(sum.name), "summary kept and labeled by clone");
        check("大号".equals(find(rows, other).name) && find(rows, other).canOpen, "first clone separate");
        check(CloneNotificationInbox.present(Arrays.asList(active("only-index", 1, 1L, "索引", "不显示", false)), clones).isEmpty(), "non ae1 hidden even if caller kept an index");

        check(!CloneNotificationInbox.allowCancel(child, 100, Arrays.asList(active(summary, 101, 15L, "", "", true))), "missing id is not cancelled");
        check(CloneNotificationInbox.allowCancel(child, 100, active), "exact tag and id");
        check(!CloneNotificationInbox.allowCancel("toolbox", 1, active), "host tag rejected");
        check(!CloneNotificationInbox.allowCancel(child, 101, active), "same tag wrong id rejected");

        System.out.println("CloneNotificationInboxTest ok");
    }

    private static CloneNotificationInbox.Active active(String tag, int id, long time, String title, String text, boolean summary) {
        return new CloneNotificationInbox.Active(tag, id, time, title, text, summary);
    }

    private static String titleOf(List<CloneNotificationInbox.Row> rows, String tag) {
        return find(rows, tag).title;
    }

    private static String bodyOf(List<CloneNotificationInbox.Row> rows, String tag) {
        return find(rows, tag).body;
    }

    private static CloneNotificationInbox.Row find(List<CloneNotificationInbox.Row> rows, String tag) {
        for (CloneNotificationInbox.Row row : rows) if (tag.equals(row.systemTag)) return row;
        throw new AssertionError("missing " + tag);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
