package com.parallelspace.parallel_space;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 设备标识、备份决策、通知 tag/id/渠道和取消边界。不需要 Android SDK。
 *
 * <p>javac 后：
 * java -cp android/app/src/test/java:android/app/src/main/java com.parallelspace.parallel_space.CloneIsolationTest
 */
public final class CloneIsolationTest {
    private static int failed;

    public static void main(String[] args) throws Exception {
        validIds();
        enableKeepsExisting();
        disableKeepsId();
        regenerateChanges();
        missingMeansOff();
        fileSurvivesRewrite();
        corruptFileIsNotOverwritten();
        callRewriteRules();
        restoreV1();
        restoreOverwriteKeepsArchived();
        restoreAdditionalDoesNotCopy();
        restoreFreshKeepsArchived();
        notificationIdsAndTags();
        cancelBoundary();
        channelRoundTrip();
        displayName();
        if (failed > 0) {
            System.err.println(failed + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("CloneIsolationTest ok");
    }

    private static void validIds() {
        check(!CloneIdentityPolicy.isValidAndroidId(null), "null");
        check(!CloneIdentityPolicy.isValidAndroidId(""), "empty");
        check(!CloneIdentityPolicy.isValidAndroidId("0"), "zero short");
        check(!CloneIdentityPolicy.isValidAndroidId("0000000000000000"), "all zero");
        check(!CloneIdentityPolicy.isValidAndroidId("ABCDEF1234567890"), "upper");
        check(CloneIdentityPolicy.isValidAndroidId("0123456789abcdef"), "ok");
        Random zeros = new Random() {
            @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) 0); }
        };
        String id = CloneIdentityPolicy.randomAndroidId(zeros);
        check(CloneIdentityPolicy.isValidAndroidId(id), "forced nonzero");
        check(CloneIdentityPolicy.randomAndroidId(new Random(7)).equals(CloneIdentityPolicy.randomAndroidId(new Random(7))), "stable");
    }

    private static void enableKeepsExisting() {
        CloneIdentityPolicy.Record again = CloneIdentityPolicy.enable(
                new CloneIdentityPolicy.Record(false, "0123456789abcdef"), "fedcba9876543210");
        check(again.enabled && "0123456789abcdef".equals(again.androidId), "reenable keeps");
        CloneIdentityPolicy.Record first = CloneIdentityPolicy.enable(null, "fedcba9876543210");
        check(first.enabled && "fedcba9876543210".equals(first.androidId), "first uses fresh");
    }

    private static void disableKeepsId() {
        CloneIdentityPolicy.Record off = CloneIdentityPolicy.disable(
                new CloneIdentityPolicy.Record(true, "0123456789abcdef"));
        check(!off.enabled && "0123456789abcdef".equals(off.androidId), "disable keeps");
    }

    private static void regenerateChanges() {
        CloneIdentityPolicy.Record next = CloneIdentityPolicy.regenerate("fedcba9876543210");
        check(next.enabled && "fedcba9876543210".equals(next.androidId), "regen");
    }

    private static void missingMeansOff() throws Exception {
        File dir = Files.createTempDirectory("id-miss").toFile();
        CloneIdentityPolicy.Record r = CloneIdentityFile.get(dir, "com.a", 0);
        check(!r.enabled && r.androidId == null, "missing off");
    }

    private static void fileSurvivesRewrite() throws Exception {
        File dir = Files.createTempDirectory("id-file").toFile();
        Random rnd = new Random(3);
        CloneIdentityPolicy.Record on = CloneIdentityFile.setEnabled(dir, "com.a", 0, true, rnd);
        check(on.enabled && CloneIdentityPolicy.isValidAndroidId(on.androidId), "saved");
        String first = on.androidId;
        CloneIdentityPolicy.Record off = CloneIdentityFile.setEnabled(dir, "com.a", 0, false, rnd);
        check(!off.enabled && first.equals(off.androidId), "file disable keeps");
        CloneIdentityPolicy.Record back = CloneIdentityFile.setEnabled(dir, "com.a", 0, true, new Random(99));
        check(back.enabled && first.equals(back.androidId), "file reenable same");
        CloneIdentityPolicy.Record other = CloneIdentityFile.setEnabled(dir, "com.a", 1, true, new Random(5));
        check(!first.equals(other.androidId), "other user different");
        CloneIdentityPolicy.Record changed = CloneIdentityFile.regenerate(dir, "com.a", 0, new Random(11));
        check(changed.enabled && !first.equals(changed.androidId), "regen file");
        String text = readText(CloneIdentityFile.storeFile(dir));
        Map<String, CloneIdentityPolicy.Record> parsed = CloneIdentityFile.parse(text);
        check(changed.androidId.equals(parsed.get("com.a#0").androidId), "roundtrip");
        check(other.androidId.equals(parsed.get("com.a#1").androidId), "user1 kept");
    }

    private static void corruptFileIsNotOverwritten() throws Exception {
        File dir = Files.createTempDirectory("id-bad").toFile();
        File f = CloneIdentityFile.storeFile(dir);
        writeText(f, "{not json");
        boolean threw = false;
        try {
            CloneIdentityFile.setEnabled(dir, "com.a", 0, true, new Random(1));
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "corrupt refuses");
        check("{not json".equals(readText(f)), "corrupt kept");
    }

    private static void callRewriteRules() {
        check(CloneIdentityPolicy.shouldRewriteCall(true, new String[]{"GET_secure", "android_id"}), "get secure");
        check(!CloneIdentityPolicy.shouldRewriteCall(false, new String[]{"GET_secure", "android_id"}), "off");
        check(!CloneIdentityPolicy.shouldRewriteCall(true, new String[]{"GET_secure", "adb_enabled"}), "other key");
        check(!CloneIdentityPolicy.shouldRewriteCall(true, new String[]{"android_id"}), "no method");
        check(CloneIdentityPolicy.shouldRewriteQuery(true, "settings"), "settings");
        check(!CloneIdentityPolicy.shouldRewriteQuery(true, "contacts"), "contacts");
        check(!CloneIdentityPolicy.shouldRewriteQuery(false, "settings"), "query off");
    }

    private static void restoreV1() {
        CloneIdentityPolicy.Decision over = CloneIdentityPolicy.planRestore(true, "overwrite", false, false, null);
        check(over.action == CloneIdentityPolicy.DISABLE_KEEP, "v1 overwrite");
        CloneIdentityPolicy.Decision extra = CloneIdentityPolicy.planRestore(true, "create", false, false, null);
        check(extra.action == CloneIdentityPolicy.UNCHANGED, "v1 additional");
        CloneIdentityPolicy.Decision fresh = CloneIdentityPolicy.planRestore(false, "create", false, false, null);
        check(fresh.action == CloneIdentityPolicy.UNCHANGED, "v1 fresh");
    }

    private static void restoreOverwriteKeepsArchived() throws Exception {
        File dir = Files.createTempDirectory("id-over").toFile();
        CloneIdentityFile.setEnabled(dir, "com.a", 0, true, new Random(1));
        CloneIdentityPolicy.Decision d = CloneIdentityPolicy.planRestore(
                true, "overwrite", true, true, "0123456789abcdef");
        check(d.action == CloneIdentityPolicy.APPLY, "overwrite apply");
        CloneIdentityPolicy.Record applied = CloneIdentityFile.applyDecision(dir, "com.a", 0, d, new Random(2));
        check(applied.enabled && "0123456789abcdef".equals(applied.androidId), "archived id");
        CloneIdentityPolicy.Decision off = CloneIdentityPolicy.planRestore(
                true, "overwrite", true, false, "fedcba9876543210");
        CloneIdentityPolicy.Record kept = CloneIdentityFile.applyDecision(dir, "com.a", 0, off, new Random(3));
        check(!kept.enabled && "fedcba9876543210".equals(kept.androidId), "archived disabled id");
    }

    private static void restoreAdditionalDoesNotCopy() throws Exception {
        File dir = Files.createTempDirectory("id-add").toFile();
        String original = "0123456789abcdef";
        writeText(CloneIdentityFile.storeFile(dir),
                CloneIdentityFile.toJson(mapOf("com.a#0", new CloneIdentityPolicy.Record(true, original))));
        CloneIdentityPolicy.Decision d = CloneIdentityPolicy.planRestore(
                true, "create", true, true, original);
        check(d.action == CloneIdentityPolicy.ASSIGN_NEW, "additional new");
        CloneIdentityPolicy.Record created = CloneIdentityFile.applyDecision(dir, "com.a", 1, d, new Random(4));
        check(created.enabled && !original.equals(created.androidId), "not duplicate");
        check(original.equals(CloneIdentityFile.get(dir, "com.a", 0).androidId), "original stays");
        CloneIdentityPolicy.Decision off = CloneIdentityPolicy.planRestore(
                true, "create", true, false, original);
        check(off.action == CloneIdentityPolicy.UNCHANGED, "additional off copies nothing");
    }

    private static void restoreFreshKeepsArchived() throws Exception {
        File dir = Files.createTempDirectory("id-fresh").toFile();
        CloneIdentityPolicy.Decision d = CloneIdentityPolicy.planRestore(
                false, "create", true, true, "0123456789abcdef");
        check(d.action == CloneIdentityPolicy.APPLY, "fresh apply");
        CloneIdentityPolicy.Record r = CloneIdentityFile.applyDecision(dir, "com.a", 0, d, new Random(8));
        check("0123456789abcdef".equals(r.androidId), "fresh id");
    }

    private static void notificationIdsAndTags() {
        int a = CloneNotificationPolicy.systemId("com.a", 0, 7, "t");
        int b = CloneNotificationPolicy.systemId("com.a", 0, 7, "t");
        check(a == b, "stable id");
        check(a >= 0x40000000, "high range");
        check(a != CloneNotificationPolicy.systemId("com.a", 0, 7, "u"), "tag changes id");
        check(a != CloneNotificationPolicy.systemId("com.a", 1, 7, "t"), "user changes id");
        check(a != CloneNotificationPolicy.systemId("com.b", 0, 7, "t"), "pkg changes id");
        check(a != CloneNotificationPolicy.systemId("com.a", 0, 8, "t"), "app id changes id");
        String tag = CloneNotificationPolicy.systemTag("com.a", 0, 7, "t");
        check(CloneNotificationPolicy.belongsTo(tag, "com.a", 0), "belongs");
        check(Integer.valueOf(7).equals(CloneNotificationPolicy.appIdOf(tag)), "app id");
        check("t".equals(CloneNotificationPolicy.appTagOf(tag)), "app tag");
        check("".equals(CloneNotificationPolicy.appTagOf(CloneNotificationPolicy.systemTag("com.a", 0, 1, null))), "null tag");
    }

    private static void cancelBoundary() throws Exception {
        String a = CloneNotificationPolicy.systemTag("com.a", 0, 1, "x");
        String b = CloneNotificationPolicy.systemTag("com.b", 0, 1, "x");
        String a2 = CloneNotificationPolicy.systemTag("com.a", 1, 1, "x");
        check(!CloneNotificationPolicy.belongsTo(null, "com.a", 0), "host null");
        check(!CloneNotificationPolicy.belongsTo("toolbox", "com.a", 0), "host tag");
        check(!CloneNotificationPolicy.belongsTo(b, "com.a", 0), "other pkg");
        check(!CloneNotificationPolicy.belongsTo(a2, "com.a", 0), "other user");
        check(CloneNotificationPolicy.belongsTo(a, "com.a", 0), "self");

        File dir = Files.createTempDirectory("notif").toFile();
        CloneNotificationIndex.remember(dir, "com.a", 0, a, 10);
        CloneNotificationIndex.remember(dir, "com.b", 0, b, 11);
        CloneNotificationIndex.remember(dir, "com.a", 0, CloneNotificationPolicy.systemTag("com.a", 0, 2, "y"), 12);
        List<CloneNotificationIndex.Posted> mine = CloneNotificationIndex.peek(dir, "com.a", 0);
        check(mine.size() == 2, "peek size");
        for (CloneNotificationIndex.Posted p : mine) {
            check(CloneNotificationPolicy.belongsTo(p.tag, "com.a", 0), "peek belongs");
            check(!CloneNotificationPolicy.belongsTo(p.tag, "com.b", 0), "peek not b");
        }
        List<CloneNotificationIndex.Posted> taken = CloneNotificationIndex.take(dir, "com.a", 0);
        check(taken.size() == 2, "take size");
        check(CloneNotificationIndex.peek(dir, "com.a", 0).isEmpty(), "a cleared");
        check(CloneNotificationIndex.peek(dir, "com.b", 0).size() == 1, "b remains");
        CloneNotificationIndex.remember(dir, "com.a", 0, a, 10);
        CloneNotificationIndex.remember(dir, "com.a", 0, a, 99);
        check(CloneNotificationIndex.peek(dir, "com.a", 0).size() == 1, "update replaces");
        check(CloneNotificationIndex.peek(dir, "com.a", 0).get(0).id == 99, "update id");
    }

    private static void channelRoundTrip() {
        String ch = CloneNotificationPolicy.channelId("msg", "com.a", 2);
        String other = CloneNotificationPolicy.channelId("msg", "com.b", 2);
        check(!ch.equals(other), "channel pkg");
        check("msg".equals(CloneNotificationPolicy.unwrapChannel(ch)), "unwrap channel");
        check(ch.equals(CloneNotificationPolicy.channelId(ch, "com.a", 2)), "idempotent");
        String g = CloneNotificationPolicy.groupId("g", "com.a", 0);
        check("g".equals(CloneNotificationPolicy.unwrapGroup(g)), "unwrap group");
        check(!g.equals(CloneNotificationPolicy.groupId("g", "com.a", 1)), "group user");
    }

    private static void displayName() {
        check("小号".equals(CloneNotificationPolicy.displayName(" 小号 ", "微信", "com.tencent.mm", 1)), "custom");
        check("微信 分身2".equals(CloneNotificationPolicy.displayName(null, "微信", "com.tencent.mm", 1)), "default");
        check("微信".equals(CloneNotificationPolicy.displayName("", "微信", "com.tencent.mm", 0)), "first");
    }

    /** Java 8 / Android 单元测试 classpath 没有 Files.readString、writeString。 */
    private static String readText(File file) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeText(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static Map<String, CloneIdentityPolicy.Record> mapOf(String key, CloneIdentityPolicy.Record record) {
        Map<String, CloneIdentityPolicy.Record> m = new LinkedHashMap<String, CloneIdentityPolicy.Record>();
        m.put(key, record);
        return m;
    }

    private static void check(boolean ok, String name) {
        if (!ok) {
            failed++;
            System.err.println("FAIL " + name);
        }
    }
}
