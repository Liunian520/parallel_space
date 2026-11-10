package com.parallelspace.parallel_space;

/**
 * 分身调用 cameraserver（media.camera）时，AttributionSourceState / 旧版 int clientUid 该怎么改写。
 *
 * <p>根因（1.3.32）：Android 16 的 {@code CameraManager.getClientAttribution()} 发出的是
 * {@code uid = USE_CALLING_UID(-1)}、{@code pid = USE_CALLING_PID(-1)}。1.3.28–1.3.31 把 uid 改成宿主
 * uid 10316、pid 改成 myPid。cameraserver 的
 * {@code AttributionAndPermissionUtils::resolveClientUid} 在 flag {@code data_delivery_permission_checks}
 * 关闭时只认 {@code clientUid == USE_CALLING_UID} 或「可信调用者」（系统 / cameraserver 自身），
 * 普通应用传显式 uid（哪怕就是自己的 uid）也会被当作转发，返回 errorNotTrusted。
 * {@code connectDevice} 还会先把 pid 置成 USE_CALLING_PID，所以报错里 client PID 显示 -1。
 *
 * <p>改法：顶层（真正的调用方）uid / pid 一律交回 USE_CALLING_*，由 cameraserver 按 Binder 调用方
 * （= 宿主进程）解析；这是框架自己发的值，API 31–36 的 cameraserver 都接受。next 链里的条目
 * 代表其他应用，不改成 -1，只把分身虚拟身份换成宿主。
 */
public final class CameraAttributionPolicy {
    public static final int USE_CALLING_UID = -1;
    public static final int USE_CALLING_PID = -1;

    private CameraAttributionPolicy() {}

    /** 顶层 AttributionSourceState.uid：非可信调用者只能用 USE_CALLING_UID。 */
    public static int topLevelUid(int original) {
        return USE_CALLING_UID;
    }

    /** 顶层 AttributionSourceState.pid：同理交回 USE_CALLING_PID。 */
    public static int topLevelPid(int original) {
        return USE_CALLING_PID;
    }

    /** next 链里的 uid：USE_CALLING_UID 保持；分身虚拟 uid 换成宿主 uid；其它不动。 */
    public static int nextUid(int original, int bUid, int hostUid) {
        if (original == USE_CALLING_UID) return original;
        if (bUid > 0 && original == bUid) return hostUid;
        return original;
    }

    /**
     * 旧版（API ≤ 35）ICameraService 方法的 int 参数。只改等于分身虚拟 uid 的值：
     * connect* 方法里那是 clientUid，交回 USE_CALLING_UID；其它方法换成宿主 uid（保持以前行为）。
     */
    public static int intArg(boolean connectCall, int value, int bUid, int hostUid) {
        if (bUid <= 0 || value != bUid) return value;
        return connectCall ? USE_CALLING_UID : hostUid;
    }
}
