package com.parallelspace.parallel_space;

/**
 * 纯逻辑回归。不需要 Android SDK。
 *
 * <p>javac 后：
 * java -cp android/app/src/test/java:android/app/src/main/java com.parallelspace.parallel_space.CameraAttributionPolicyTest
 */
public final class CameraAttributionPolicyTest {
    private static int failed;

    public static void main(String[] args) {
        // K80 日志：框架发 -1/-1，旧代码改成 10316/7421 → errorNotTrusted
        eq("frameworkSentinelUidKept", -1, CameraAttributionPolicy.topLevelUid(-1));
        eq("frameworkSentinelPidKept", -1, CameraAttributionPolicy.topLevelPid(-1));
        eq("explicitHostUidBecomesCalling", -1, CameraAttributionPolicy.topLevelUid(10316));
        eq("virtualUidBecomesCalling", -1, CameraAttributionPolicy.topLevelUid(10001));
        eq("explicitPidBecomesCalling", -1, CameraAttributionPolicy.topLevelPid(7421));
        eq("zeroPidBecomesCalling", -1, CameraAttributionPolicy.topLevelPid(0));
        eq("nextSentinelKept", -1, CameraAttributionPolicy.nextUid(-1, 10001, 10316));
        eq("nextVirtualToHost", 10316, CameraAttributionPolicy.nextUid(10001, 10001, 10316));
        eq("nextOtherAppUntouched", 10200, CameraAttributionPolicy.nextUid(10200, 10001, 10316));
        eq("connectIntVirtualUidToCalling", -1, CameraAttributionPolicy.intArg(true, 10001, 10001, 10316));
        eq("connectIntSentinelKept", -1, CameraAttributionPolicy.intArg(true, -1, 10001, 10316));
        eq("connectIntOtherUntouched", 33, CameraAttributionPolicy.intArg(true, 33, 10001, 10316));
        eq("nonConnectVirtualUidToHost", 10316, CameraAttributionPolicy.intArg(false, 10001, 10001, 10316));
        eq("unknownBUidNoChange", 10001, CameraAttributionPolicy.intArg(true, 10001, -2, 10316));
        if (failed > 0) {
            System.err.println(failed + " failed");
            System.exit(1);
        }
        System.out.println("CameraAttributionPolicyTest OK");
    }

    private static void eq(String name, int want, int got) {
        if (want != got) {
            failed++;
            System.err.println("FAIL " + name + ": want " + want + " got " + got);
        }
    }
}
