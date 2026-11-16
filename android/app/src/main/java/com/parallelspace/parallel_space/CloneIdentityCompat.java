package com.parallelspace.parallel_space;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

/**
 * 挂在 settings 的 ContentProvider 代理上：分身打开隔离后，把 android_id 的读取结果换成已保存的标识。
 * 关闭时原样返回，不生成一次性随机数。
 *
 * <p>由 bcore 字节码补丁调用，方法签名不能改。
 */
public final class CloneIdentityCompat {
    private CloneIdentityCompat() {}

    public static Object rewrite(Object result, Method method, Object[] args) {
        try {
            if (result == null || method == null) return result;
            Guest guest = guest();
            if (guest == null) return result;
            CloneIdentityPolicy.Record record = CloneIdentityFile.get(filesDir(), guest.pkg, guest.userId);
            if (!record.enabled || !CloneIdentityPolicy.isValidAndroidId(record.androidId)) return result;
            String name = method.getName();
            if ("call".equals(name)) {
                if (!CloneIdentityPolicy.shouldRewriteCall(true, strings(args))) return result;
                return rewriteBundle(result, record.androidId);
            }
            if ("query".equals(name)) {
                if (!CloneIdentityPolicy.shouldRewriteQuery(true, authority(args))) return result;
                if (result instanceof Cursor) {
                    boolean onlyId = mentionsAndroidId(args);
                    return new AndroidIdCursor((Cursor) result, record.androidId, onlyId);
                }
            }
            return result;
        } catch (Throwable ignored) {
            return result;
        }
    }

    private static Object rewriteBundle(Object result, String androidId) {
        if (!(result instanceof Bundle)) return result;
        Bundle copy = new Bundle((Bundle) result);
        copy.putString("value", androidId);
        return copy;
    }

    private static String[] strings(Object[] args) {
        if (args == null) return new String[0];
        int n = 0;
        for (Object a : args) if (a instanceof String) n++;
        String[] out = new String[n];
        int i = 0;
        for (Object a : args) if (a instanceof String) out[i++] = (String) a;
        return out;
    }

    private static boolean mentionsAndroidId(Object[] args) {
        if (args == null) return false;
        for (Object a : args) {
            if (a instanceof String && CloneIdentityPolicy.ANDROID_ID.equals(a)) return true;
            if (a instanceof String[]) {
                for (String s : (String[]) a) {
                    if (CloneIdentityPolicy.ANDROID_ID.equals(s)) return true;
                }
            }
        }
        return false;
    }

    private static String authority(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof Uri) return ((Uri) a).getAuthority();
            if (a instanceof String) {
                String s = (String) a;
                if (s.startsWith("content://")) {
                    Uri u = Uri.parse(s);
                    if (u.getAuthority() != null) return u.getAuthority();
                }
                if ("settings".equals(s)) return s;
            }
        }
        return null;
    }

    static File filesDir() {
        return BlackBoxCore.getContext().getFilesDir();
    }

    static Guest guest() {
        try {
            if (!BlackBoxCore.get().isBlackProcess()) return null;
            String pkg = BActivityThread.getAppPackageName();
            if (pkg == null || pkg.trim().isEmpty()) return null;
            String host = BlackBoxCore.getHostPkg();
            if (pkg.equals(host)) return null;
            int user = BActivityThread.getUserId();
            if (user < 0) user = 0;
            return new Guest(pkg, user);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static final class Guest {
        final String pkg;
        final int userId;

        Guest(String pkg, int userId) {
            this.pkg = pkg;
            this.userId = userId;
        }
    }

    /**
     * 有 name 列时只改 android_id 那一行。没有 name 列、但查询参数就是 android_id 时，改 value 列。
     */
    static final class AndroidIdCursor extends CursorWrapper {
        private final String androidId;
        private final boolean onlyThisQuery;
        private final int nameCol;
        private final int valueCol;

        AndroidIdCursor(Cursor cursor, String androidId, boolean onlyThisQuery) {
            super(cursor);
            this.androidId = androidId;
            this.onlyThisQuery = onlyThisQuery;
            this.nameCol = cursor.getColumnIndex("name");
            this.valueCol = cursor.getColumnIndex("value");
        }

        @Override
        public String getString(int columnIndex) {
            if (columnIndex == valueCol && valueCol >= 0 && matchesRow()) return androidId;
            return super.getString(columnIndex);
        }

        private boolean matchesRow() {
            if (nameCol >= 0) return CloneIdentityPolicy.ANDROID_ID.equals(super.getString(nameCol));
            return onlyThisQuery;
        }
    }
}
