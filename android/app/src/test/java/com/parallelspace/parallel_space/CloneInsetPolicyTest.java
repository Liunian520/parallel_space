package com.parallelspace.parallel_space;

/**
 * 纯逻辑回归。不需要 Android SDK。
 *
 * <p>javac 后：
 * java -cp android/app/src/test/java:android/app/src/main/java com.parallelspace.parallel_space.CloneInsetPolicyTest
 */
public final class CloneInsetPolicyTest {
    private static int failed;

    public static void main(String[] args) {
        edgeToEdgeOptOutIsIgnoredOnApi36Target36();
        optOutStillWorksOnApi35AndForTarget35();
        decorFitsTrueDoesNotChangeLayoutWhenEnforced();
        unknownEnforcementOnApi35PlusUsesManualInsets();
        visibleStatusBarPadsTopByStatusAndCutout();
        hiddenStatusBarIsIntentionalFullscreen();
        visibleNavPadsBottom();
        negativeInsetsAreClamped();
        zeroInsetsAreNotConsumed();
        cameraOpIsNotForcedWhenHostDeniesOrUnknown();
        if (failed > 0) {
            System.err.println(failed + " assertion(s) failed");
            System.exit(1);
        }
        System.out.println("CloneInsetPolicyTest OK");
    }

    private static void edgeToEdgeOptOutIsIgnoredOnApi36Target36() {
        // Android 16 behavior-changes-16: targetSdk 36 on API 36 disables the opt-out.
        // REDMI K80 OS3.0.309.0.WOKCNXM firmware package is user-16.0.
        check(!CloneInsetPolicy.decorFitsTrueChangesLayout(36, 36, true),
                "theme opt-out must not make decorFits(true) work on API 36 target 36");
        check(CloneInsetPolicy.edgeToEdgeEnforced(36, 36, true),
                "edge-to-edge stays enforced when the opt-out attribute is set");
    }

    private static void optOutStillWorksOnApi35AndForTarget35() {
        check(!CloneInsetPolicy.edgeToEdgeEnforced(35, 35, true), "API 35 target 35 honors opt-out");
        check(CloneInsetPolicy.edgeToEdgeEnforced(35, 35, false), "API 35 target 35 without opt-out is enforced");
        check(!CloneInsetPolicy.edgeToEdgeEnforced(36, 35, true),
                "target 36 on an Android 15 device still honors opt-out");
        check(!CloneInsetPolicy.edgeToEdgeEnforced(35, 36, true),
                "target 35 on Android 16 still honors opt-out");
        check(!CloneInsetPolicy.edgeToEdgeEnforced(30, 36, false),
                "guest targetSdk below 35 is not enforced by this rule");
        check(!CloneInsetPolicy.edgeToEdgeEnforced(36, 34, false), "API 34 has no enforcement");
    }

    private static void decorFitsTrueDoesNotChangeLayoutWhenEnforced() {
        check(!CloneInsetPolicy.decorFitsTrueChangesLayout(36, 36, false),
                "PhoneWindow.setDecorFitsSystemWindows returns immediately when enforced");
        check(CloneInsetPolicy.decorFitsTrueChangesLayout(34, 34, false),
                "pre-35 decorFits(true) still changes layout");
    }

    private static void unknownEnforcementOnApi35PlusUsesManualInsets() {
        check(CloneInsetPolicy.useManualInsets(Boolean.TRUE, 36), "enforced window uses manual insets");
        check(!CloneInsetPolicy.useManualInsets(Boolean.FALSE, 36),
                "unenforced window keeps decorFits and must not double-pad");
        check(CloneInsetPolicy.useManualInsets(null, 36),
                "API 36 with unreadable mEdgeToEdgeEnforced still pads manually");
        check(!CloneInsetPolicy.useManualInsets(null, 34),
                "API 34 without the field stays on the legacy path");
    }

    private static void visibleStatusBarPadsTopByStatusAndCutout() {
        // FLAG_FULLSCREEN is intentionally not an input. Bcore ActivityCompat adds it
        // for windowFullscreen themes before onCreate, while API 35+ still draws status icons.
        CloneInsetPolicy.Padding p = CloneInsetPolicy.padding(new CloneInsetPolicy.Bars(
                true, true, 120, 48, 136, 0, 0, 0, 0, 0));
        check(p.top == 136, "top is max(status, cutout), got " + p.top);
        check(p.bottom == 48, "bottom is nav, got " + p.bottom);
    }

    private static void hiddenStatusBarIsIntentionalFullscreen() {
        CloneInsetPolicy.Padding p = CloneInsetPolicy.padding(new CloneInsetPolicy.Bars(
                false, false, 120, 48, 136, 20, 12, 8, 4, 6));
        check(p.top == 0 && p.bottom == 0 && p.left == 0 && p.right == 0,
                "hidden system bars keep fullscreen, got " + p);
        check(CloneInsetPolicy.preserveFullscreen(false), "hidden status bar is intentional fullscreen");
        check(!CloneInsetPolicy.preserveFullscreen(true), "visible status bar is not fullscreen");
    }

    private static void visibleNavPadsBottom() {
        CloneInsetPolicy.Padding p = CloneInsetPolicy.padding(new CloneInsetPolicy.Bars(
                false, true, 100, 40, 90, 16, 10, 11, 3, 4));
        check(p.top == 0, "hidden status bar does not pad top");
        check(p.bottom == 40, "visible nav pads bottom, got " + p.bottom);
        check(p.left == 10 && p.right == 11, "side cutout still applies when a bar is visible");
    }

    private static void negativeInsetsAreClamped() {
        CloneInsetPolicy.Padding p = CloneInsetPolicy.padding(new CloneInsetPolicy.Bars(
                true, true, -5, -8, -1, -2, -3, -4, -6, -7));
        check(p.top == 0 && p.bottom == 0 && p.left == 0 && p.right == 0,
                "negative insets clamp to 0, got " + p);
    }

    private static void zeroInsetsAreNotConsumed() {
        CloneInsetPolicy.Padding padded = CloneInsetPolicy.padding(new CloneInsetPolicy.Bars(
                true, true, 80, 20, 0, 0, 0, 0, 0, 0));
        check(CloneInsetPolicy.consumeSystemBars(padded), "nonzero padding consumes system bars");
        CloneInsetPolicy.Padding hidden = CloneInsetPolicy.padding(new CloneInsetPolicy.Bars(
                false, false, 80, 20, 40, 10, 4, 4, 2, 2));
        check(!CloneInsetPolicy.consumeSystemBars(hidden), "intentional fullscreen does not consume");
        CloneInsetPolicy.Padding zero = CloneInsetPolicy.padding(new CloneInsetPolicy.Bars(
                true, true, 0, 0, 0, 0, 0, 0, 0, 0));
        check(!CloneInsetPolicy.consumeSystemBars(zero),
                "visible bars that report zero insets must not consume");
    }

    private static void cameraOpIsNotForcedWhenHostDeniesOrUnknown() {
        check(CameraOpDecision.forceModeAllowed(true, Boolean.TRUE) != null
                        && CameraOpDecision.forceModeAllowed(true, Boolean.TRUE) == CameraOpDecision.MODE_ALLOWED,
                "host grant may force MODE_ALLOWED");
        check(CameraOpDecision.forceModeAllowed(true, Boolean.FALSE) == null,
                "host denial must not force MODE_ALLOWED");
        check(CameraOpDecision.forceModeAllowed(true, null) == null,
                "unknown host grant must not force MODE_ALLOWED");
        check(CameraOpDecision.forceModeAllowed(false, Boolean.TRUE) == null,
                "non-camera ops are not forced");
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            failed++;
            System.err.println("FAIL " + message);
        }
    }
}
