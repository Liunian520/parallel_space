package com.parallelspace.parallel_space;

/**
 * 什么时候可以按引擎名单删掉界面上的分身。
 * 空名单不算「确认一台都没装」：Binder 失败时 BlackBox 会返回空列表而不抛错。
 */
public final class CloneRetention {
    private CloneRetention() {}

    /** 只有权威、并且至少列出一台时，缺席的分身才可以删。 */
    public static boolean mayRemoveAbsent(boolean authoritative, int listedCount) {
        return authoritative && listedCount > 0;
    }

    /**
     * 列举本身成功，但一台都没返回，而磁盘上还有分身目录时，不能再标成完整。
     * 没有磁盘证据的空名单仍可以标完整；界面层同样不会因此删掉已保存的分身。
     */
    public static boolean reportComplete(boolean enumeratedOk, int listedCount, boolean diskHasClones) {
        if (!enumeratedOk) return false;
        return !(listedCount == 0 && diskHasClones);
    }
}
