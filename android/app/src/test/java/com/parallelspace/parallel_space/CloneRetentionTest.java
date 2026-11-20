package com.parallelspace.parallel_space;

/**
 * 空列举不能当成「分身已经没了」。磁盘上还有目录时，完整标记也必须拿掉。
 */
public final class CloneRetentionTest {
    public static void main(String[] args) {
        check(!CloneRetention.mayRemoveAbsent(true, 0), "authoritative empty list does not delete");
        check(!CloneRetention.mayRemoveAbsent(false, 0), "failed empty list does not delete");
        check(!CloneRetention.mayRemoveAbsent(false, 3), "partial list does not delete");
        check(CloneRetention.mayRemoveAbsent(true, 2), "authoritative non-empty list may drop the absent one");

        check(CloneRetention.reportComplete(true, 2, false), "non-empty success stays complete");
        check(CloneRetention.reportComplete(true, 0, false), "empty and no disk can still be complete");
        check(!CloneRetention.reportComplete(true, 0, true), "empty while disk remains is not complete");
        check(!CloneRetention.reportComplete(false, 0, true), "enumeration failure stays incomplete");
        check(!CloneRetention.reportComplete(false, 4, false), "failed enumeration stays incomplete");
        System.out.println("CloneRetentionTest ok");
    }

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
