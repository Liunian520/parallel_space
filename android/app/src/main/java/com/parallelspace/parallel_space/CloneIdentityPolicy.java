package com.parallelspace.parallel_space;

import java.util.Locale;
import java.util.Random;

/**
 * 分身 Android ID 的纯规则：校验、生成、开关迁移、备份恢复。
 * 不读文件，也不调用 Android API。默认关闭，避免升级后悄悄换掉已经在用的标识。
 */
public final class CloneIdentityPolicy {
    public static final String ANDROID_ID = "android_id";
    public static final int ID_LENGTH = 16;

    public static final int UNCHANGED = 0;
    /** 写入备份里的开关和标识。 */
    public static final int APPLY = 1;
    /** 旧备份没有标识：关闭隔离，但保留本机已经生成过的标识。 */
    public static final int DISABLE_KEEP = 2;
    /** 再新建一份分身：开启隔离时另给一个不同的标识。 */
    public static final int ASSIGN_NEW = 3;

    private CloneIdentityPolicy() {}

    public static boolean isValidAndroidId(String id) {
        if (id == null || id.length() != ID_LENGTH) return false;
        boolean nonzero = false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
            if (c != '0') nonzero = true;
        }
        return nonzero;
    }

    /** 16 位小写十六进制。全 0 时改最后一位，保证不是空标识。 */
    public static String randomAndroidId(Random random) {
        if (random == null) throw new IllegalArgumentException("random");
        byte[] raw = new byte[8];
        random.nextBytes(raw);
        char[] out = new char[ID_LENGTH];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        String id = new String(out);
        if (!isValidAndroidId(id)) {
            out[ID_LENGTH - 1] = '1';
            id = new String(out);
        }
        return id;
    }

    public static String normalizeKey(String pkg, int userId) {
        if (pkg == null) return "";
        return pkg.trim() + "#" + userId;
    }

    /**
     * 再次打开时沿用已有合法标识；没有才用新生成的。
     * 关闭只改开关，不把标识清掉。
     */
    public static Record enable(Record current, String freshId) {
        if (current != null && isValidAndroidId(current.androidId)) {
            return new Record(true, current.androidId);
        }
        if (!isValidAndroidId(freshId)) throw new IllegalArgumentException("fresh id");
        return new Record(true, freshId);
    }

    public static Record disable(Record current) {
        String id = current == null ? null : current.androidId;
        if (!isValidAndroidId(id)) id = null;
        return new Record(false, id);
    }

    public static Record regenerate(String freshId) {
        if (!isValidAndroidId(freshId)) throw new IllegalArgumentException("fresh id");
        return new Record(true, freshId);
    }

    public static boolean isSettingsGetMethod(String name) {
        return "GET_secure".equals(name) || "GET_system".equals(name) || "GET_global".equals(name);
    }

    /** settings 的 GET_* 且参数里带 android_id 时才改返回值。 */
    public static boolean shouldRewriteCall(boolean isolationEnabled, String[] args) {
        if (!isolationEnabled || args == null) return false;
        boolean key = false;
        boolean method = false;
        for (String s : args) {
            if (s == null) continue;
            if (ANDROID_ID.equals(s)) key = true;
            else if (isSettingsGetMethod(s)) method = true;
        }
        return key && method;
    }

    /** 查询的 authority 是 settings 时，按行替换 android_id。 */
    public static boolean shouldRewriteQuery(boolean isolationEnabled, String authority) {
        if (!isolationEnabled || authority == null) return false;
        String a = authority.toLowerCase(Locale.US);
        return "settings".equals(a) || a.startsWith("settings");
    }

    /**
     * @param sourceSlotExists 恢复前，备份里的 pkg#userId 在本机是否已经有分身
     * @param restoreAction    overwrite 或 create
     * @param archivePresent   备份 meta 里有没有 identity 对象
     */
    public static Decision planRestore(
            boolean sourceSlotExists,
            String restoreAction,
            boolean archivePresent,
            boolean archiveEnabled,
            String archiveId) {
        boolean overwrite = "overwrite".equals(restoreAction);
        boolean additional = "create".equals(restoreAction) && sourceSlotExists;
        if (!archivePresent) {
            if (overwrite) return Decision.disableKeep();
            return Decision.unchanged();
        }
        if (additional) {
            if (archiveEnabled && isValidAndroidId(archiveId)) return Decision.assignNew();
            return Decision.unchanged();
        }
        if (archiveEnabled && !isValidAndroidId(archiveId)) return Decision.assignNew();
        String id = isValidAndroidId(archiveId) ? archiveId : null;
        return Decision.apply(archiveEnabled, id);
    }

    public static final class Record {
        public final boolean enabled;
        public final String androidId;

        public Record(boolean enabled, String androidId) {
            this.enabled = enabled;
            this.androidId = androidId;
        }
    }

    public static final class Decision {
        public final int action;
        public final boolean enabled;
        public final String androidId;

        private Decision(int action, boolean enabled, String androidId) {
            this.action = action;
            this.enabled = enabled;
            this.androidId = androidId;
        }

        public static Decision unchanged() {
            return new Decision(UNCHANGED, false, null);
        }

        public static Decision disableKeep() {
            return new Decision(DISABLE_KEEP, false, null);
        }

        public static Decision assignNew() {
            return new Decision(ASSIGN_NEW, true, null);
        }

        public static Decision apply(boolean enabled, String androidId) {
            return new Decision(APPLY, enabled, androidId);
        }
    }
}
