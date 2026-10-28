package com.parallelspace.parallel_space;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 替换 Bcore {@code TaskDescriptionCompat.getTaskDescriptionLabel(int, CharSequence)}。
 * 原实现返回 {@code String.format("[B%d]%s", userId, label)}，最近任务里会显示「[B0]应用名」。
 *
 * <p>这里改为：读宿主 filesDir/clone_names.json 里该实例的自定义名；没有时返回原应用名，
 * 额外实例（userId &gt; 0）加「 分身N」（N = userId + 1）。由分身进程调用，只读文件，不碰引擎状态。
 *
 * <p>不要改类名和方法签名：bcore 字节码补丁按此签名调用。
 */
public final class TaskLabelCompat {
    private TaskLabelCompat() {}

    private static volatile JSONObject cache;
    private static volatile long cacheMtime = -1L;

    public static String label(int userId, CharSequence original) {
        String base = original == null ? "" : original.toString();
        try {
            String pkg = currentPackage();
            if (pkg != null) {
                JSONObject names = load();
                if (names != null) {
                    String custom = names.optString(pkg + "#" + userId, "");
                    if (!custom.trim().isEmpty()) return custom.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return defaultLabel(base, userId);
    }

    /** 与 Dart 侧 CloneApp.defaultName 保持一致。 */
    public static String defaultLabel(String appLabel, int userId) {
        if (userId <= 0) return appLabel;
        return appLabel + " 分身" + (userId + 1);
    }

    private static String currentPackage() {
        try {
            Class<?> c = Class.forName("top.niunaijun.blackbox.app.BActivityThread");
            Object v = c.getMethod("getAppPackageName").invoke(null);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static File namesFile() {
        try {
            Class<?> c = Class.forName("top.niunaijun.blackbox.BlackBoxCore");
            Object host = c.getMethod("getHostPkg").invoke(null);
            if (host instanceof String) {
                File f = new File("/data/data/" + host + "/files/" + CloneNames.FILE);
                if (f.isFile()) return f;
                File u = new File("/data/user/0/" + host + "/files/" + CloneNames.FILE);
                if (u.isFile()) return u;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static JSONObject load() {
        File f = namesFile();
        if (f == null) return cache;
        long m = f.lastModified();
        if (cache != null && m == cacheMtime) return cache;
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            cache = new JSONObject(s);
            cacheMtime = m;
        } catch (Throwable ignored) {
        }
        return cache;
    }
}
