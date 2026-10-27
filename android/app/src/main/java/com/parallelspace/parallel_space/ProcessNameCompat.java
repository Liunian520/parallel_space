package com.parallelspace.parallel_space;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import androidx.annotation.Keep;

import java.io.FileInputStream;

/**
 * 给已打补丁的 Bcore {@code BlackBoxCore.getProcessName} 用。
 *
 * <p>上游实现只调用 {@code ActivityManager.getRunningAppProcesses()}。澎湃 OS / HyperOS
 *（以及 Android 15 上部分机型）在 {@code Application.attachBaseContext} 期间会让这个列表
 * 为空或不包含当前 pid，于是 BlackBox 抛出 {@code RuntimeException("processName = null")}，
 * {@code doAttachBaseContext} 中途退出，虚拟引擎永远 {@code ready=false}。
 *
 * <p>{@link Application#getProcessName()}（API 28+）和 {@code /proc/self/cmdline}
 * 在 attach 阶段就可用，不经过 ActivityManager。
 */
@Keep
public final class ProcessNameCompat {
    private ProcessNameCompat() {}

    @Keep
    public static String get(Context context) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                String name = Application.getProcessName();
                if (name != null && !name.isEmpty()) return name;
            } catch (Throwable ignored) {
            }
        }
        String cmdline = readCmdline();
        if (cmdline != null) return cmdline;
        try {
            Object service = context.getSystemService(Context.ACTIVITY_SERVICE);
            if (service instanceof android.app.ActivityManager am) {
                int pid = Process.myPid();
                var procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo info : procs) {
                        if (info.pid == pid && info.processName != null && !info.processName.isEmpty()) {
                            return info.processName;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 主进程的 cmdline 几乎总能读到。走到这里时宁可报包名（主进程），
        // 也不要抛 processName = null 把整个引擎初始化打断。
        String pkg = context.getPackageName();
        return pkg == null || pkg.isEmpty() ? "com.parallelspace.parallel_space" : pkg;
    }

    private static String readCmdline() {
        try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return null;
            int end = 0;
            while (end < n && buf[end] != 0) end++;
            if (end <= 0) return null;
            String s = new String(buf, 0, end, java.nio.charset.StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
