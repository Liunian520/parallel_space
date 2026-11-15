package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯逻辑回归。不需要 Android SDK。
 *
 * <p>javac 后：
 * java -cp android/app/src/test/java:android/app/src/main/java com.parallelspace.parallel_space.CloneDataPolicyTest
 */
public final class CloneDataPolicyTest {
    private static int failed;

    public static void main(String[] args) {
        formatCheck();
        pathSafety();
        skipRules();
        uniqueLabels();
        nextFreeUser();
        planOverwriteExisting();
        planOverwriteMissingUsesOriginalUser();
        planCreateNewWhenExists();
        planCreateNewWhenMissingKeepsUserId();
        planAllCreateNewAvoidsCollision();
        planLabelSuffixOnlyWhenNeeded();
        planEmptyPackageIsError();
        planUserLimit();
        if (failed > 0) {
            System.err.println(failed + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("CloneDataPolicyTest OK");
    }

    private static void formatCheck() {
        check(CloneDataPolicy.CHECK_OK.equals(CloneDataPolicy.checkFormat("alter_ego.clone_data", 1)),
                "v1 current format is ok");
        check(CloneDataPolicy.CHECK_NEED_UPGRADE.equals(CloneDataPolicy.checkFormat("alter_ego.clone_data", 99)),
                "newer format must ask the user to upgrade");
        check(CloneDataPolicy.CHECK_INVALID.equals(CloneDataPolicy.checkFormat("parallel_space.backup", 3)),
                "config-backup JSON is not a clone-data archive");
        check(CloneDataPolicy.CHECK_INVALID.equals(CloneDataPolicy.checkFormat(null, 1)), "null format");
        check(CloneDataPolicy.CHECK_INVALID.equals(CloneDataPolicy.checkFormat("alter_ego.clone_data", 0)),
                "version 0 is invalid");
    }

    private static void pathSafety() {
        check(CloneDataPolicy.isSafeZipEntry("clones/0/data/files/a.db"), "normal entry");
        check(CloneDataPolicy.isSafeZipEntry("manifest.json"), "manifest");
        check(!CloneDataPolicy.isSafeZipEntry("../etc/passwd"), "parent traversal");
        check(!CloneDataPolicy.isSafeZipEntry("clones/0/data/../../blackbox/system"), "nested traversal");
        check(!CloneDataPolicy.isSafeZipEntry("/data/user/0/x"), "absolute");
        check(!CloneDataPolicy.isSafeZipEntry("clones/0/data/foo/./bar"), "dot segment rejected");
        check(!CloneDataPolicy.isSafeZipEntry(""), "empty");
        check(!CloneDataPolicy.isSafeZipEntry(null), "null");
        check(CloneDataPolicy.isSafeZipEntry("clones/0/apk/base.apk"), "apk");
        check("files/a.db".equals(CloneDataPolicy.normalizePath("/files/a.db/")), "normalize slashes");
        check("files/a.db".equals(CloneDataPolicy.normalizePath("files\\a.db")), "normalize backslash");
    }

    private static void skipRules() {
        check(CloneDataPolicy.shouldSkipDataEntry("code_cache"), "top code_cache");
        check(CloneDataPolicy.shouldSkipDataEntry("code_cache/foo"), "code_cache child");
        check(CloneDataPolicy.shouldSkipDataEntry("cache/code_cache/x"), "nested code_cache");
        check(CloneDataPolicy.shouldSkipDataEntry("lib"), "top lib");
        check(CloneDataPolicy.shouldSkipDataEntry("lib/arm64/foo.so"), "lib child");
        check(!CloneDataPolicy.shouldSkipDataEntry("cache"), "cache is kept (sessions may live here)");
        check(!CloneDataPolicy.shouldSkipDataEntry("files/session"), "files kept");
        check(!CloneDataPolicy.shouldSkipDataEntry("databases/t.db"), "databases kept");
        check(!CloneDataPolicy.shouldSkipDataEntry("shared_prefs/x.xml"), "shared_prefs kept");
        check(!CloneDataPolicy.shouldSkipDataEntry("no_backup/x"), "no_backup kept");
        check(!CloneDataPolicy.shouldSkipDataEntry("app_webview/Cookies"), "webview kept");
        check(!CloneDataPolicy.shouldSkipDataEntry("app_webview/Default/Cookies"), "webview nested");
    }

    private static void uniqueLabels() {
        check("微信工作".equals(CloneDataPolicy.uniqueLabel("微信工作", Arrays.asList("微信"))),
                "no collision keeps name");
        check("微信工作 复制".equals(CloneDataPolicy.uniqueLabel("微信工作", Arrays.asList("微信工作"))),
                "first copy suffix");
        check("微信工作 复制2".equals(CloneDataPolicy.uniqueLabel(
                "微信工作", Arrays.asList("微信工作", "微信工作 复制"))),
                "second copy suffix");
        check("".equals(CloneDataPolicy.uniqueLabel("", Arrays.asList("微信"))),
                "empty label is not rewritten");
        check(CloneDataPolicy.uniqueLabel(null, Arrays.asList("x")) == null, "null label");
    }

    private static void nextFreeUser() {
        Set<String> used = new LinkedHashSet<String>();
        used.add("a.b#0");
        used.add("a.b#1");
        used.add("other#2");
        check(CloneDataPolicy.nextFreeUser("a.b", used) == 2, "skips 0 and 1");
        check(CloneDataPolicy.nextFreeUser("other", used) == 0, "other still has 0");
        Set<String> full = new LinkedHashSet<String>();
        for (int i = 0; i < CloneDataPolicy.MAX_USERS; i++) full.add("p#" + i);
        check(CloneDataPolicy.nextFreeUser("p", full) == CloneDataPolicy.MAX_USERS, "full");
    }

    private static void planOverwriteExisting() {
        List<CloneDataPolicy.CloneRef> bak = Arrays.asList(
                ref("com.t.app", 0, "工作", "App", true));
        Set<String> inst = set("com.t.app#0");
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                bak, CloneDataPolicy.MODE_OVERWRITE, inst, Arrays.asList("工作"));
        check(plan.size() == 1 && "overwrite".equals(plan.get(0).action), "overwrite action");
        check(plan.get(0).targetUserId == 0, "same userId");
        check(!plan.get(0).needsApk, "apk already installed");
        check("工作".equals(plan.get(0).label), "keep remark on overwrite");
    }

    private static void planOverwriteMissingUsesOriginalUser() {
        List<CloneDataPolicy.CloneRef> bak = Arrays.asList(
                ref("com.t.app", 3, "出差", "App", true));
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                bak, CloneDataPolicy.MODE_OVERWRITE, new LinkedHashSet<String>(), new ArrayList<String>());
        check("create".equals(plan.get(0).action), "missing clone is created");
        check(plan.get(0).targetUserId == 3, "keep original userId on a new phone");
        check(plan.get(0).needsApk, "needs apk");
        check("出差".equals(plan.get(0).label), "remark kept");
    }

    private static void planCreateNewWhenExists() {
        List<CloneDataPolicy.CloneRef> bak = Arrays.asList(
                ref("com.t.app", 0, "工作", "App", true));
        Set<String> inst = set("com.t.app#0");
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                bak, CloneDataPolicy.MODE_CREATE_NEW, inst, Arrays.asList("工作"));
        check("create".equals(plan.get(0).action), "create new");
        check(plan.get(0).targetUserId == 1, "next free user is 1");
        check(plan.get(0).needsApk, "new user needs apk");
        check("工作 复制".equals(plan.get(0).label), "suffix when remark collides");
    }

    private static void planCreateNewWhenMissingKeepsUserId() {
        List<CloneDataPolicy.CloneRef> bak = Arrays.asList(
                ref("com.t.app", 0, "工作", "App", false));
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                bak, CloneDataPolicy.MODE_CREATE_NEW, new LinkedHashSet<String>(), new ArrayList<String>());
        check(plan.get(0).targetUserId == 0 && "create".equals(plan.get(0).action),
                "create-new on empty device still uses original userId");
        check("工作".equals(plan.get(0).label), "no suffix when name is free");
    }

    private static void planAllCreateNewAvoidsCollision() {
        List<CloneDataPolicy.CloneRef> bak = Arrays.asList(
                ref("a.one", 0, "甲", "A", true),
                ref("a.one", 1, "乙", "A", true),
                ref("b.two", 0, "甲", "B", true));
        Set<String> inst = set("a.one#0", "a.one#1", "b.two#0");
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                bak, CloneDataPolicy.MODE_CREATE_NEW, inst, Arrays.asList("甲", "乙"));
        check(plan.get(0).targetUserId == 2, "a.one next is 2");
        check(plan.get(1).targetUserId == 3, "second a.one is 3");
        check(plan.get(2).targetUserId == 1, "b.two next is 1");
        check("甲 复制".equals(plan.get(0).label), "first 甲 gets 复制");
        check("乙 复制".equals(plan.get(1).label), "乙 also collides");
        check("甲 复制2".equals(plan.get(2).label), "second 甲 in the same batch gets 复制2");
    }

    private static void planLabelSuffixOnlyWhenNeeded() {
        List<CloneDataPolicy.CloneRef> bak = Arrays.asList(
                ref("com.t.app", 0, "独有名", "App", true));
        Set<String> inst = set("com.t.app#0");
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                bak, CloneDataPolicy.MODE_CREATE_NEW, inst, Arrays.asList("其他"));
        check("独有名".equals(plan.get(0).label), "unique remark is kept as-is");
        List<CloneDataPolicy.PlanItem> empty = CloneDataPolicy.planRestore(
                Arrays.asList(ref("com.t.app", 0, "", "App", true)),
                CloneDataPolicy.MODE_CREATE_NEW, inst, Arrays.asList("App"));
        check("".equals(empty.get(0).label), "empty remark is not forced to 复制");
    }

    private static void planEmptyPackageIsError() {
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                Arrays.asList(ref("", 0, "x", "x", false)),
                CloneDataPolicy.MODE_OVERWRITE, new LinkedHashSet<String>(), new ArrayList<String>());
        check("error".equals(plan.get(0).action), "empty pkg");
    }

    private static void planUserLimit() {
        Set<String> full = new LinkedHashSet<String>();
        for (int i = 0; i < CloneDataPolicy.MAX_USERS; i++) full.add("p#" + i);
        List<CloneDataPolicy.PlanItem> plan = CloneDataPolicy.planRestore(
                Arrays.asList(ref("p", 0, "n", "n", true)),
                CloneDataPolicy.MODE_CREATE_NEW, full, new ArrayList<String>());
        check("error".equals(plan.get(0).action), "overflow is error not wrap");
    }

    private static CloneDataPolicy.CloneRef ref(String pkg, int user, String label, String name, boolean apk) {
        return new CloneDataPolicy.CloneRef(pkg, user, label, name, apk);
    }

    private static Set<String> set(String... keys) {
        return new LinkedHashSet<String>(Arrays.asList(keys));
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            failed++;
            System.err.println("FAIL: " + msg);
        }
    }
}
