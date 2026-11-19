package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.List;

/**
 * 入口是 main，不是 JUnit。夹具与 Dart vault_app_group_test 一致。
 */
public final class VaultAppHeadingTest {
    public static void main(String[] args) {
        List<VaultAppHeading.Row> rows = fixture(true);
        List<VaultAppHeading.Section> current = VaultAppHeading.sections(rows, "com.z");
        check(pkgs(current).equals(List.of("com.z", "com.a", "com.m")), "com.z section first");
        check(ids(current.get(1)).equals(List.of("a", "d", "c")), "within com.a: current, label, id");
        check(count(current) == 5, "five rows kept");

        List<VaultAppHeading.Section> plain = VaultAppHeading.sections(fixture(false), "");
        check(pkgs(plain).equals(List.of("com.a", "com.m", "com.z")), "empty foreground sorts by pkg");
        check(ids(plain.get(0)).equals(List.of("d", "a", "c")), "label code units put 乙 before 甲");

        List<VaultAppHeading.Row> withBlank = new ArrayList<>(fixture(true));
        withBlank.add(new VaultAppHeading.Row("z", "", "无名", false));
        List<VaultAppHeading.Section> blank = VaultAppHeading.sections(withBlank, "com.z");
        check(blank.get(blank.size() - 1).pkg.isEmpty(), "blank pkg last");
        check(blank.get(blank.size() - 1).rows.get(0).id.equals("z"), "blank pkg row kept");
        check(count(blank) == 6, "blank pkg is not dropped");
        check(VaultAppHeading.heading("", VaultAppHeading.Kind.UNAVAILABLE, "").equals("应用信息不可用"), "blank heading");

        check(VaultAppHeading.heading("com.qq", VaultAppHeading.Kind.PENDING, "").equals("com.qq"), "pending");
        check(VaultAppHeading.heading("com.qq", VaultAppHeading.Kind.NAMED, "微信").equals("微信"), "named");
        check(VaultAppHeading.heading("com.qq", VaultAppHeading.Kind.NAMED, "  ").equals("com.qq · 应用信息不可用"), "blank name");
        check(VaultAppHeading.heading("com.qq", VaultAppHeading.Kind.UNAVAILABLE, "").equals("com.qq · 应用信息不可用"), "unavailable");
        check(VaultAppHeading.heading("com.qq", VaultAppHeading.Kind.RELIABLY_UNINSTALLED, "").equals("com.qq · 未安装"), "reliable only");
        check(!VaultAppHeading.heading("com.qq", VaultAppHeading.Kind.UNAVAILABLE, "").contains("未安装"), "unavailable is not uninstalled");

        check(VaultAppHeading.kindFromLookup(false, null, true, false) == VaultAppHeading.Kind.UNAVAILABLE, "name not found");
        check(VaultAppHeading.kindFromLookup(false, null, false, true) == VaultAppHeading.Kind.UNAVAILABLE, "other failure");
        check(VaultAppHeading.kindFromLookup(false, null, false, false) == VaultAppHeading.Kind.PENDING, "not looked up");
        check(VaultAppHeading.kindFromLookup(true, "  ", false, false) == VaultAppHeading.Kind.UNAVAILABLE, "blank label");
        check(VaultAppHeading.kindFromLookup(true, "微信", false, false) == VaultAppHeading.Kind.NAMED, "found name");
        check(VaultAppHeading.kindFromLookup(true, "微信", true, true) == VaultAppHeading.Kind.UNAVAILABLE, "failure wins");

        System.out.println("VaultAppHeadingTest ok");
    }

    private static List<VaultAppHeading.Row> fixture(boolean currentA) {
        List<VaultAppHeading.Row> rows = new ArrayList<>();
        rows.add(new VaultAppHeading.Row("b", "com.z", "乙", false));
        rows.add(new VaultAppHeading.Row("a", "com.a", "甲", currentA));
        rows.add(new VaultAppHeading.Row("c", "com.a", "甲", false));
        rows.add(new VaultAppHeading.Row("d", "com.a", "乙", false));
        rows.add(new VaultAppHeading.Row("e", "com.m", "", false));
        return rows;
    }

    private static List<String> pkgs(List<VaultAppHeading.Section> sections) {
        List<String> out = new ArrayList<>();
        for (VaultAppHeading.Section section : sections) out.add(section.pkg);
        return out;
    }

    private static List<String> ids(VaultAppHeading.Section section) {
        List<String> out = new ArrayList<>();
        for (VaultAppHeading.Row row : section.rows) out.add(row.id);
        return out;
    }

    private static int count(List<VaultAppHeading.Section> sections) {
        int n = 0;
        for (VaultAppHeading.Section section : sections) n += section.rows.size();
        return n;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
