package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.List;

/**
 * 入口是 main，不是 JUnit。
 * 覆盖：内容相同不刷新、内容变化或删除要刷新、滚动夹紧、点击只看当前列表。
 */
public final class VaultLiveTest {
    public static void main(String[] args) {
        VaultLive.Row a = row("1", "主号", "alice", "old-secret");
        VaultLive.Row a2 = row("1", "主号", "alice", "new-secret");
        VaultLive.Row b = row("2", "小号", "bob", "other");
        List<VaultLive.Row> original = list(a, b);
        check(VaultLive.sameSnapshot(original, list(row("1", "主号", "alice", "old-secret"), row("2", "小号", "bob", "other"))), "identical content");
        check(!VaultLive.sameSnapshot(original, list(a2, b)), "password change");
        check(!VaultLive.sameSnapshot(original, list(a)), "deletion");
        check(!VaultLive.sameSnapshot(original, list(b, a)), "reorder");
        check(!VaultLive.sameSnapshot(original, list(a, b, row("3", "新", "c", "x"))), "addition");
        check(VaultLive.sameSnapshot(null, null), "both null");
        check(!VaultLive.sameSnapshot(original, null), "null is not the same list");

        check(VaultLive.clampScroll(120, 400) == 120, "scroll inside range");
        check(VaultLive.clampScroll(900, 400) == 400, "scroll past end");
        check(VaultLive.clampScroll(-5, 400) == 0, "negative scroll");
        check(VaultLive.clampScroll(20, 0) == 0, "empty viewport");

        VaultLive.Decision stale = VaultLive.onField(list(a2, b), "1", "pass");
        check(stale.action == VaultLive.Action.COPY && "new-secret".equals(stale.text), "copy uses the live password");
        check(VaultLive.onField(list(b), "1", "pass").action == VaultLive.Action.MISSING, "deleted id is missing");
        check(VaultLive.onField(list(row("1", "主号", "", "secret")), "1", "user").action == VaultLive.Action.EMPTY, "empty account");
        VaultLive.Fill fill = VaultLive.onFill(list(a2), "1");
        check(!fill.missing && "alice".equals(fill.user) && "new-secret".equals(fill.pass), "fill uses live fields");
        check(VaultLive.onFill(list(b), "1").missing, "fill of deleted id does not return old secrets");
        check(VaultLive.onFill(list(b), "1").user.isEmpty() && VaultLive.onFill(list(b), "1").pass.isEmpty(), "deleted fill is blank");

        System.out.println("VaultLiveTest ok");
    }

    private static VaultLive.Row row(String id, String label, String user, String pass) {
        return new VaultLive.Row(id, "com.tencent.mm", "w:com.tencent.mm#1", label, user, pass, "");
    }

    private static List<VaultLive.Row> list(VaultLive.Row... rows) {
        List<VaultLive.Row> out = new ArrayList<>();
        for (VaultLive.Row row : rows) out.add(row);
        return out;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
