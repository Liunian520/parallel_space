package com.parallelspace.parallel_space;

/**
 * 分身通知的 tag、id、渠道和分组怎么命名。纯函数，方便核对取消边界。
 * 通知仍由宿主进程发出，这里只避免不同分身互相覆盖或一起被取消。
 */
public final class CloneNotificationPolicy {
    public static final String PREFIX = "ae1";
    public static final char SEP = '\u001f';
    public static final String CHANNEL_MARK = "@ae1-";
    public static final String GROUP_MARK = "@ae1g-";

    private CloneNotificationPolicy() {}

    public static String key(String pkg, int userId) {
        return (pkg == null ? "" : pkg) + "#" + userId;
    }

    public static String systemTag(String pkg, int userId, int appId, String appTag) {
        return PREFIX + SEP + userId + SEP + (pkg == null ? "" : pkg) + SEP + appId + SEP + (appTag == null ? "" : appTag);
    }

    public static boolean belongsTo(String systemTag, String pkg, int userId) {
        if (systemTag == null || pkg == null || pkg.isEmpty()) return false;
        String head = PREFIX + SEP + userId + SEP + pkg + SEP;
        return systemTag.startsWith(head);
    }

    /** 从系统 tag 取回应用自己的 id。解析失败返回 null。 */
    public static Integer appIdOf(String systemTag) {
        String[] p = split(systemTag);
        if (p == null) return null;
        try {
            return Integer.valueOf(p[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 从系统 tag 取回应用自己的 tag。没有则是空串。 */
    public static String appTagOf(String systemTag) {
        String[] p = split(systemTag);
        if (p == null) return null;
        return p[4];
    }

    /**
     * 稳定的系统通知 id，落在 0x40000000 以上，避开宿主自己用的小整数。
     * 同一个分身、同一个应用 tag 和 id，每次算出来相同。
     */
    public static int systemId(String pkg, int userId, int appId, String appTag) {
        int h = 0x9e3779b9;
        h = mix(h, pkg == null ? "" : pkg);
        h = mix(h, userId);
        h = mix(h, appId);
        h = mix(h, appTag == null ? "" : appTag);
        return (h & 0x3fffffff) | 0x40000000;
    }

    public static String channelId(String original, String pkg, int userId) {
        String base = (original == null || original.isEmpty()) ? "misc" : original;
        if (base.contains(CHANNEL_MARK)) return base;
        return base + CHANNEL_MARK + userId + ":" + (pkg == null ? "" : pkg);
    }

    public static String unwrapChannel(String systemChannelId) {
        if (systemChannelId == null) return null;
        int i = systemChannelId.indexOf(CHANNEL_MARK);
        if (i < 0) return systemChannelId;
        return systemChannelId.substring(0, i);
    }

    public static String groupId(String original, String pkg, int userId) {
        if (original == null || original.isEmpty()) return original;
        if (original.contains(GROUP_MARK)) return original;
        return original + GROUP_MARK + userId + ":" + (pkg == null ? "" : pkg);
    }

    public static String unwrapGroup(String systemGroupId) {
        if (systemGroupId == null) return null;
        int i = systemGroupId.indexOf(GROUP_MARK);
        if (i < 0) return systemGroupId;
        return systemGroupId.substring(0, i);
    }

    /** 展示名：有自定义名用自定义名，否则「应用名」或「应用名 分身N」。空则退回包名。 */
    public static String displayName(String custom, String appLabel, String pkg, int userId) {
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        String base = (appLabel != null && !appLabel.trim().isEmpty()) ? appLabel.trim() : (pkg == null ? "" : pkg);
        if (userId <= 0) return base;
        return base + " 分身" + (userId + 1);
    }

    private static String[] split(String systemTag) {
        if (systemTag == null || !systemTag.startsWith(PREFIX + SEP)) return null;
        String[] p = systemTag.split(String.valueOf(SEP), 5);
        if (p.length != 5) return null;
        if (!PREFIX.equals(p[0])) return null;
        return p;
    }

    private static int mix(int h, int v) {
        h ^= v;
        h *= 0x7feb352d;
        return h;
    }

    private static int mix(int h, String s) {
        int x = 0;
        for (int i = 0; i < s.length(); i++) {
            x = x * 31 + s.charAt(i);
        }
        return mix(h, x);
    }
}
