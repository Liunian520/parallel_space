package com.parallelspace.parallel_space;

/**
 * AppOps CAMERA 是否允许短路成 MODE_ALLOWED。
 *
 * <p>1.3.30 对名字里带 CAMERA 的 noteOp 无条件返回 0。宿主被系统拒绝时这是假授权。
 * 只有确认宿主已经授予相机权限才短路；拒绝或读不到结果时交给原来的 hook / 系统。
 */
public final class CameraOpDecision {
    public static final int MODE_ALLOWED = 0;

    private CameraOpDecision() {}

    /**
     * @param cameraOp 这次调用是不是相机相关 op
     * @param hostGranted 宿主相机权限：true 已允许，false 已拒绝，null 未知
     * @return {@link #MODE_ALLOWED} 表示可以短路；null 表示不要改返回值
     */
    public static Integer forceModeAllowed(boolean cameraOp, Boolean hostGranted) {
        if (cameraOp && Boolean.TRUE.equals(hostGranted)) return MODE_ALLOWED;
        return null;
    }
}
