package com.parallelspace.parallel_space;

/**
 * 虚拟分身列表算不算权威。不依赖 Android SDK。
 * 引擎未就绪、用户列表读失败、或任一 user 列举失败，都不能当成「这些就是全部」。
 */
public final class CloneEnumPolicy {
    private CloneEnumPolicy() {}

    public static boolean authoritative(boolean engineReady, boolean usersKnown, int failedUsers) {
        return engineReady && usersKnown && failedUsers == 0;
    }
}
