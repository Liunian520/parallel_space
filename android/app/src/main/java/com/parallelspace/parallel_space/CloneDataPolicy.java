package com.parallelspace.parallel_space;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 分身数据备份的纯逻辑：归档格式校验、zip 路径安全、跳过规则、
 * 恢复时覆盖/新建的 userId 与备注名决策。不依赖 Android SDK。
 */
public final class CloneDataPolicy {
    public static final String FORMAT = "alter_ego.clone_data";
    public static final int VERSION = 1;
    public static final int MAX_USERS = 64;

    public static final String MODE_OVERWRITE = "overwrite";
    public static final String MODE_CREATE_NEW = "createNew";

    public static final String ACTION_OVERWRITE = "overwrite";
    public static final String ACTION_CREATE = "create";
    public static final String ACTION_ERROR = "error";

    public static final String CHECK_OK = "ok";
    public static final String CHECK_INVALID = "invalid";
    public static final String CHECK_NEED_UPGRADE = "need_upgrade";

    private CloneDataPolicy() {}

    /** 规范化归档内相对路径：反斜杠改斜杠，去掉首尾斜杠。 */
    public static String normalizePath(String relative) {
        if (relative == null) return "";
        String n = relative.replace('\\', '/').trim();
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        while (n.endsWith("/") && n.length() > 1) n = n.substring(0, n.length() - 1);
        return n;
    }

    /**
     * zip 条目必须落在目标目录内，禁止绝对路径和 {@code ..} 穿越。
     */
    public static boolean isSafeZipEntry(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.startsWith("../")) return false;
        if (n.contains(":") && !n.contains("/")) return false;
        String[] parts = n.split("/");
        for (String p : parts) {
            if (p.equals("..") || p.equals(".")) return false;
            if (p.isEmpty()) continue;
        }
        return true;
    }

    /**
     * 相对数据目录根的跳过规则：
     * <ul>
     *   <li>{@code code_cache}（任意层级）是可重建的 ART/JIT 缓存</li>
     *   <li>数据目录顶层 {@code lib} 是从 APK 解出的 so，恢复后由引擎按本机 ABI 重建</li>
     * </ul>
     * 保守起见，{@code cache}、{@code files}、{@code databases}、{@code shared_prefs}、
     * {@code no_backup}、{@code app_*} 都保留（会话常在这些目录里）。
     */
    public static boolean shouldSkipDataEntry(String relativeToDataRoot) {
        String n = normalizePath(relativeToDataRoot);
        if (n.isEmpty()) return false;
        String[] parts = n.split("/");
        if (parts.length > 0 && "lib".equals(parts[0])) return true;
        for (String p : parts) {
            if ("code_cache".equals(p)) return true;
        }
        return false;
    }

    public static String checkFormat(String format, int version) {
        if (format == null || !FORMAT.equals(format)) return CHECK_INVALID;
        if (version < 1) return CHECK_INVALID;
        if (version > VERSION) return CHECK_NEED_UPGRADE;
        return CHECK_OK;
    }

    /**
     * 备注名冲突时加「复制」后缀。空备注不改（沿用应用名，允许重名）。
     * 已占用集合应包含本机已有分身的显示名，以及本批恢复里已经定下的名字。
     */
    public static String uniqueLabel(String desired, Collection<String> taken) {
        if (desired == null || desired.isEmpty()) return desired;
        Set<String> used = taken == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(taken);
        if (!used.contains(desired)) return desired;
        String first = desired + " 复制";
        if (!used.contains(first)) return first;
        int i = 2;
        while (used.contains(desired + " 复制" + i)) {
            i++;
            if (i > 999) break;
        }
        return desired + " 复制" + i;
    }

    public static final class CloneRef {
        public final String packageName;
        public final int userId;
        public final String customLabel;
        public final String appName;
        public final boolean hasApk;

        public CloneRef(String packageName, int userId, String customLabel, String appName, boolean hasApk) {
            this.packageName = packageName == null ? "" : packageName;
            this.userId = userId;
            this.customLabel = customLabel;
            this.appName = appName == null ? "" : appName;
            this.hasApk = hasApk;
        }

        public String key() {
            return key(packageName, userId);
        }

        public static String key(String pkg, int userId) {
            return pkg + "#" + userId;
        }
    }

    public static final class PlanItem {
        public final int index;
        public final String packageName;
        public final int sourceUserId;
        public final int targetUserId;
        public final String action;
        public final String label;
        public final boolean needsApk;
        public final String error;

        public PlanItem(int index, String packageName, int sourceUserId, int targetUserId,
                        String action, String label, boolean needsApk, String error) {
            this.index = index;
            this.packageName = packageName;
            this.sourceUserId = sourceUserId;
            this.targetUserId = targetUserId;
            this.action = action;
            this.label = label;
            this.needsApk = needsApk;
            this.error = error;
        }
    }

    /**
     * @param mode {@link #MODE_OVERWRITE} 或 {@link #MODE_CREATE_NEW}
     * @param installedKeys 本机已安装的 {@code pkg#userId}
     * @param existingLabels 本机已有分身的显示名（自定义名优先）
     */
    public static List<PlanItem> planRestore(
            List<CloneRef> backup,
            String mode,
            Set<String> installedKeys,
            Collection<String> existingLabels) {
        List<PlanItem> out = new ArrayList<PlanItem>();
        if (backup == null) return out;
        Set<String> used = new LinkedHashSet<String>();
        if (installedKeys != null) used.addAll(installedKeys);
        Set<String> labels = new LinkedHashSet<String>();
        if (existingLabels != null) labels.addAll(existingLabels);
        boolean createNew = MODE_CREATE_NEW.equals(mode);
        for (int i = 0; i < backup.size(); i++) {
            CloneRef b = backup.get(i);
            if (b.packageName.isEmpty()) {
                out.add(new PlanItem(i, b.packageName, b.userId, -1, ACTION_ERROR, "", false, "缺少包名"));
                continue;
            }
            String srcKey = CloneRef.key(b.packageName, b.userId);
            boolean exists = used.contains(srcKey);
            String action;
            int target;
            if (!createNew && exists) {
                action = ACTION_OVERWRITE;
                target = b.userId;
            } else if (createNew && exists) {
                action = ACTION_CREATE;
                target = nextFreeUser(b.packageName, used);
            } else {
                action = ACTION_CREATE;
                target = used.contains(srcKey) ? nextFreeUser(b.packageName, used) : nextFreeUser(b.packageName, used);
                // 原 userId 空闲时 nextFreeUser 会从 0 扫到它之前的空位；
                // 若希望尽量沿用备份里的 userId（换机恢复），优先用原 id。
                if (!used.contains(srcKey) && b.userId >= 0 && b.userId < MAX_USERS) {
                    target = b.userId;
                }
            }
            if (target < 0 || target >= MAX_USERS) {
                out.add(new PlanItem(i, b.packageName, b.userId, target, ACTION_ERROR,
                        b.customLabel == null ? "" : b.customLabel, false, "分身数量已达上限"));
                continue;
            }
            boolean needsApk = !used.contains(CloneRef.key(b.packageName, target));
            used.add(CloneRef.key(b.packageName, target));
            String desired = b.customLabel == null ? "" : b.customLabel.trim();
            String label = desired;
            if (!desired.isEmpty() && (action.equals(ACTION_CREATE) || labels.contains(desired))) {
                // 覆盖同一实例时保留原备注；新建时若重名则加「复制」
                if (action.equals(ACTION_CREATE)) {
                    label = uniqueLabel(desired, labels);
                }
            }
            if (label != null && !label.isEmpty()) labels.add(label);
            out.add(new PlanItem(i, b.packageName, b.userId, target, action, label, needsApk, null));
        }
        return out;
    }

    public static int nextFreeUser(String pkg, Set<String> usedKeys) {
        for (int i = 0; i < MAX_USERS; i++) {
            if (usedKeys == null || !usedKeys.contains(CloneRef.key(pkg, i))) return i;
        }
        return MAX_USERS;
    }
}
