package com.parallelspace.parallel_space;

/**
 * 分身窗口 insets 决策（无 Android 依赖，便于单测）。
 *
 * <p>1.3.30 调用 {@code setDecorFitsSystemWindows(true)} 并在 BTheme 上设置
 * {@code windowOptOutEdgeToEdgeEnforcement}。两处在 REDMI K80 / Android 16 上都不能
 * 把客户 Activity 的顶栏从状态栏下面挪开：
 *
 * <ul>
 *   <li>AOSP {@code PhoneWindow.setDecorFitsSystemWindows} 在 {@code mEdgeToEdgeEnforced}
 *       时直接 return。
 *   <li>Android 16 对 targetSdk 36 禁用 opt-out（behavior-changes-16）。BTheme 的 opt-out
 *       也只作用于 ProxyActivity；Bcore 在 onCreate 前把 ActivityInfo 换成客户主题。
 * </ul>
 *
 * 状态栏仍可见时，用 statusBars 与 displayCutout 的较大值做内容 padding。
 * 状态栏实际隐藏才视为有意全屏，不 padding。{@code FLAG_FULLSCREEN} 不参与判断：
 * Bcore {@code ActivityCompat.fix} 会按客户主题的 windowFullscreen 提前加上该旗标，
 * 而 edge-to-edge 下状态栏图标仍然画着。
 */
public final class CloneInsetPolicy {
    private CloneInsetPolicy() {}

    public static final class Bars {
        public final boolean statusVisible;
        public final boolean navVisible;
        public final int statusTop;
        public final int navBottom;
        public final int cutoutTop;
        public final int cutoutBottom;
        public final int cutoutLeft;
        public final int cutoutRight;
        public final int systemLeft;
        public final int systemRight;

        public Bars(boolean statusVisible, boolean navVisible, int statusTop, int navBottom,
                    int cutoutTop, int cutoutBottom, int cutoutLeft, int cutoutRight,
                    int systemLeft, int systemRight) {
            this.statusVisible = statusVisible;
            this.navVisible = navVisible;
            this.statusTop = statusTop;
            this.navBottom = navBottom;
            this.cutoutTop = cutoutTop;
            this.cutoutBottom = cutoutBottom;
            this.cutoutLeft = cutoutLeft;
            this.cutoutRight = cutoutRight;
            this.systemLeft = systemLeft;
            this.systemRight = systemRight;
        }
    }

    public static final class Padding {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Padding(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @Override
        public String toString() {
            return left + "," + top + "," + right + "," + bottom;
        }
    }

    /**
     * targetSdk ≥ 35 且系统 API ≥ 35 时默认强制 edge-to-edge。
     * targetSdk ≥ 36 且系统 API ≥ 36 时，主题 opt-out 无效。
     */
    public static boolean edgeToEdgeEnforced(int targetSdk, int sdkInt, boolean themeOptOut) {
        if (sdkInt < 35 || targetSdk < 35) return false;
        boolean optOutHonored = themeOptOut && !(targetSdk >= 36 && sdkInt >= 36);
        return !optOutHonored;
    }

    /** 与 AOSP PhoneWindow：enforced 时 setDecorFitsSystemWindows(true) 不改布局。 */
    public static boolean decorFitsTrueChangesLayout(int targetSdk, int sdkInt, boolean themeOptOut) {
        return !edgeToEdgeEnforced(targetSdk, sdkInt, themeOptOut);
    }

    /**
     * @param edgeToEdgeEnforced 从窗口读到的 {@code mEdgeToEdgeEnforced}；读不到时为 null
     */
    public static boolean useManualInsets(Boolean edgeToEdgeEnforced, int sdkInt) {
        if (edgeToEdgeEnforced != null) return edgeToEdgeEnforced;
        return sdkInt >= 35;
    }

    /** 状态栏不可见：游戏 / 视频真全屏，不要把内容往下推。 */
    public static boolean preserveFullscreen(boolean statusVisible) {
        return !statusVisible;
    }

    /**
     * 只有确实加了边距才吃掉 systemBars / displayCutout。
     * 边距全是 0（真全屏，或系统把 insets 报成 0）时不吞，避免把客户自己的处理掐掉。
     */
    public static boolean consumeSystemBars(Padding padding) {
        return padding.left != 0 || padding.top != 0 || padding.right != 0 || padding.bottom != 0;
    }

    public static Padding padding(Bars bars) {
        int top = bars.statusVisible ? Math.max(nonNeg(bars.statusTop), nonNeg(bars.cutoutTop)) : 0;
        int bottom = bars.navVisible ? Math.max(nonNeg(bars.navBottom), nonNeg(bars.cutoutBottom)) : 0;
        boolean anyBar = bars.statusVisible || bars.navVisible;
        int left = anyBar ? Math.max(nonNeg(bars.systemLeft), nonNeg(bars.cutoutLeft)) : 0;
        int right = anyBar ? Math.max(nonNeg(bars.systemRight), nonNeg(bars.cutoutRight)) : 0;
        return new Padding(left, top, right, bottom);
    }

    private static int nonNeg(int v) {
        return Math.max(0, v);
    }
}
