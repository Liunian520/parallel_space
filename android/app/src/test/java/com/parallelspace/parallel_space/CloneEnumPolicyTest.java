package com.parallelspace.parallel_space;

/**
 * 入口是 main，不是 JUnit。
 */
public final class CloneEnumPolicyTest {
    public static void main(String[] args) {
        check(CloneEnumPolicy.authoritative(true, true, 0), "ready and every user listed");
        check(!CloneEnumPolicy.authoritative(false, true, 0), "engine not ready is not an empty success");
        check(!CloneEnumPolicy.authoritative(true, false, 0), "unknown user list is partial");
        check(!CloneEnumPolicy.authoritative(true, true, 1), "one failed user makes the list partial");
        check(!CloneEnumPolicy.authoritative(true, true, 3), "several failed users stay partial");
        System.out.println("CloneEnumPolicyTest ok");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
