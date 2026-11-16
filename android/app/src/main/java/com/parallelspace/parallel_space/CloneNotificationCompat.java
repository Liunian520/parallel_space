package com.parallelspace.parallel_space;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.frameworks.BNotificationManager;

/**
 * 分身进程里改写通知的 tag、id、渠道和展示名，再调用真实的通知服务。
 * 不要通过 NotificationManager.notify 转发，否则会重新进入这个代理。
 *
 * <p>由 bcore 字节码补丁调用。{@link #FALLBACK} 表示交给原来的 BlackBox 实现。
 * 通知仍然挂在宿主 UID 下。
 */
public final class CloneNotificationCompat {
    /** 补丁用来判断「这次没处理」。不要拿它当通知结果返回给系统。 */
    public static final Object FALLBACK = new Object();

    private CloneNotificationCompat() {}

    public static Object before(Object stub, Method method, Object[] args) {
        if (method == null) return FALLBACK;
        String name = method.getName();
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || name == null) return FALLBACK;
        if (name.startsWith("cancelAll")) {
            try {
                cancelRecorded(baseOf(stub), guest);
            } catch (Throwable ignored) {
            }
            return 0;
        }
        if ("setNotificationsEnabledForPackage".equals(name)) return 0;
        try {
            if (name.contains("ActiveNotification")) {
                return filterActive(baseOf(stub), method, args, guest);
            }
            if ("enqueueNotification".equals(name)) {
                return enqueue(baseOf(stub), method, args, -1, -1);
            }
            if ("cancelNotification".equals(name)) {
                return cancel(baseOf(stub), method, args, -1, -1);
            }
        } catch (Throwable ignored) {
            return FALLBACK;
        }
        return FALLBACK;
    }

    public static Object enqueue(Object who, Method method, Object[] args, int tagIndex, int idIndex) {
        Object isolated = enqueueIsolated(who, method, args, tagIndex, idIndex);
        if (isolated != FALLBACK) return isolated;
        return legacyEnqueue(args, tagIndex, idIndex);
    }

    private static Object enqueueIsolated(Object who, Method method, Object[] args, int tagIndex, int idIndex) {
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || who == null || method == null || args == null) return FALLBACK;
        try {
            int noteAt = indexOf(args, Notification.class);
            if (noteAt < 0) return FALLBACK;
            int tagAt = tagIndex >= 0 ? tagIndex : indexBefore(args, noteAt, String.class);
            int idAt = idIndex >= 0 ? idIndex : indexBefore(args, noteAt, Integer.class);
            if (tagAt < 0 || idAt < 0) return FALLBACK;
            if (args[tagAt] != null && !(args[tagAt] instanceof String)) return FALLBACK;
            if (!(args[idAt] instanceof Integer)) return FALLBACK;
            String appTag = (String) args[tagAt];
            int appId = (Integer) args[idAt];
            Object[] copy = args.clone();
            copy[noteAt] = decorate((Notification) args[noteAt], guest);
            String sysTag = CloneNotificationPolicy.systemTag(guest.pkg, guest.userId, appId, appTag);
            int sysId = CloneNotificationPolicy.systemId(guest.pkg, guest.userId, appId, appTag);
            copy[tagAt] = sysTag;
            copy[idAt] = sysId;
            try {
                method.invoke(who, copy);
            } catch (Throwable invoked) {
                return 0;
            }
            try {
                CloneNotificationIndex.remember(CloneIdentityCompat.filesDir(), guest.pkg, guest.userId, sysTag, sysId);
            } catch (Throwable ignored) {
            }
            return 0;
        } catch (Throwable ignored) {
            return FALLBACK;
        }
    }

    public static Object cancel(Object who, Method method, Object[] args, int tagIndex, int idIndex) {
        Object isolated = cancelIsolated(who, method, args, tagIndex, idIndex);
        if (isolated != FALLBACK) return isolated;
        return legacyCancel(args, tagIndex, idIndex);
    }

    private static Object cancelIsolated(Object who, Method method, Object[] args, int tagIndex, int idIndex) {
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || who == null || method == null || args == null) return FALLBACK;
        try {
            int tagAt = tagIndex;
            int idAt = idIndex;
            if (tagAt < 0 || idAt < 0 || tagAt >= args.length || idAt >= args.length) {
                tagAt = -1;
                idAt = -1;
                for (int i = 0; i < args.length; i++) {
                    if (tagAt < 0 && (args[i] == null || args[i] instanceof String)) tagAt = i;
                    else if (idAt < 0 && args[i] instanceof Integer) idAt = i;
                }
            }
            if (tagAt < 0 || idAt < 0) return FALLBACK;
            if (args[tagAt] != null && !(args[tagAt] instanceof String)) return FALLBACK;
            if (!(args[idAt] instanceof Integer)) return FALLBACK;
            String appTag = (String) args[tagAt];
            int appId = (Integer) args[idAt];
            String sysTag = CloneNotificationPolicy.systemTag(guest.pkg, guest.userId, appId, appTag);
            int sysId = CloneNotificationPolicy.systemId(guest.pkg, guest.userId, appId, appTag);
            Object[] copy = args.clone();
            copy[tagAt] = sysTag;
            copy[idAt] = sysId;
            try {
                method.invoke(who, copy);
            } catch (Throwable invoked) {
                return 0;
            }
            try {
                CloneNotificationIndex.forget(CloneIdentityCompat.filesDir(), guest.pkg, guest.userId, sysTag);
            } catch (Throwable ignored) {
            }
            return 0;
        } catch (Throwable ignored) {
            return FALLBACK;
        }
    }

    public static Object createChannels(Object who, Method method, Object[] args) {
        Object isolated = withChannels(who, method, args);
        if (isolated != FALLBACK) return isolated;
        return legacyChannels(args, true);
    }

    public static Object createGroups(Object who, Method method, Object[] args) {
        Object isolated = createGroupsIsolated(who, method, args);
        if (isolated != FALLBACK) return isolated;
        return legacyChannels(args, false);
    }

    private static Object createGroupsIsolated(Object who, Method method, Object[] args) {
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || who == null || method == null) return FALLBACK;
        List<?> list = findList(args);
        if (list == null) return FALLBACK;
        String[] ids = new String[list.size()];
        try {
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                if (!(o instanceof NotificationChannelGroup)) continue;
                NotificationChannelGroup group = (NotificationChannelGroup) o;
                ids[i] = group.getId();
                if (!setTextField(group, "mId", CloneNotificationPolicy.groupId(group.getId(), guest.pkg, guest.userId))) {
                    restoreGroups(list, ids);
                    return FALLBACK;
                }
            }
            try {
                method.invoke(who, args);
            } catch (Throwable invoked) {
                return 0;
            }
            return 0;
        } catch (Throwable ignored) {
            return FALLBACK;
        } finally {
            restoreGroups(list, ids);
        }
    }

    public static Object deleteChannel(Object who, Method method, Object[] args) {
        Object isolated = deleteId(who, method, args, true);
        if (isolated != FALLBACK) return isolated;
        return legacyDelete(args, true);
    }

    public static Object deleteGroup(Object who, Method method, Object[] args) {
        Object isolated = deleteId(who, method, args, false);
        if (isolated != FALLBACK) return isolated;
        return legacyDelete(args, false);
    }

    public static Object getChannel(Object who, Method method, Object[] args) {
        Object isolated = getOne(who, method, args, true);
        if (isolated != FALLBACK) return isolated;
        return legacyGetOne(args, true);
    }

    public static Object getGroup(Object who, Method method, Object[] args) {
        Object isolated = getOne(who, method, args, false);
        if (isolated != FALLBACK) return isolated;
        return legacyGetOne(args, false);
    }

    public static Object getChannels(Object who, Method method, Object[] args) {
        Object isolated = getMany(who, method, args, true);
        if (isolated != FALLBACK) return isolated;
        return legacyGetMany(true);
    }

    public static Object getGroups(Object who, Method method, Object[] args) {
        Object isolated = getMany(who, method, args, false);
        if (isolated != FALLBACK) return isolated;
        return legacyGetMany(false);
    }

    /** 宿主界面进程删除分身时调用。这里没有通知代理，可以直接 cancel。 */
    public static void cancelPosted(Context ctx, String pkg, int userId) {
        if (ctx == null || pkg == null) return;
        List<CloneNotificationIndex.Posted> mine = CloneNotificationIndex.take(ctx.getFilesDir(), pkg, userId);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        for (CloneNotificationIndex.Posted p : mine) {
            if (!CloneNotificationPolicy.belongsTo(p.tag, pkg, userId)) continue;
            nm.cancel(p.tag, p.id);
        }
    }

    private static Object withChannels(Object who, Method method, Object[] args) {
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || who == null || method == null) return FALLBACK;
        List<?> list = findList(args);
        if (list == null) return FALLBACK;
        String[] ids = new String[list.size()];
        String[] groups = new String[list.size()];
        try {
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                if (!(o instanceof NotificationChannel)) continue;
                NotificationChannel ch = (NotificationChannel) o;
                ids[i] = ch.getId();
                groups[i] = ch.getGroup();
                if (!setTextField(ch, "mId", CloneNotificationPolicy.channelId(ch.getId(), guest.pkg, guest.userId))) {
                    restoreChannels(list, ids, groups);
                    return FALLBACK;
                }
                if (ch.getGroup() != null) {
                    ch.setGroup(CloneNotificationPolicy.groupId(ch.getGroup(), guest.pkg, guest.userId));
                }
            }
            try {
                method.invoke(who, args);
            } catch (Throwable invoked) {
                return 0;
            }
            return 0;
        } catch (Throwable ignored) {
            return FALLBACK;
        } finally {
            restoreChannels(list, ids, groups);
        }
    }

    private static Object deleteId(Object who, Method method, Object[] args, boolean channel) {
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || who == null || method == null || args == null) return FALLBACK;
        try {
            int at = lastString(args, guest.pkg);
            if (at < 0) return FALLBACK;
            String orig = (String) args[at];
            Object[] copy = args.clone();
            copy[at] = channel
                    ? CloneNotificationPolicy.channelId(orig, guest.pkg, guest.userId)
                    : CloneNotificationPolicy.groupId(orig, guest.pkg, guest.userId);
            method.invoke(who, copy);
            return 0;
        } catch (Throwable ignored) {
            return FALLBACK;
        }
    }

    private static Object getOne(Object who, Method method, Object[] args, boolean channel) {
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || who == null || method == null || args == null) return FALLBACK;
        try {
            int at = lastString(args, guest.pkg);
            if (at < 0) return FALLBACK;
            String orig = (String) args[at];
            Object[] copy = args.clone();
            copy[at] = channel
                    ? CloneNotificationPolicy.channelId(orig, guest.pkg, guest.userId)
                    : CloneNotificationPolicy.groupId(orig, guest.pkg, guest.userId);
            Object result = method.invoke(who, copy);
            if (result instanceof NotificationChannel) {
                setTextField(result, "mId", orig);
                NotificationChannel ch = (NotificationChannel) result;
                if (ch.getGroup() != null) ch.setGroup(CloneNotificationPolicy.unwrapGroup(ch.getGroup()));
            } else if (result instanceof NotificationChannelGroup) {
                setTextField(result, "mId", orig);
            }
            return result;
        } catch (Throwable ignored) {
            return FALLBACK;
        }
    }

    private static Object getMany(Object who, Method method, Object[] args, boolean channels) {
        CloneIdentityCompat.Guest guest = CloneIdentityCompat.guest();
        if (guest == null || who == null || method == null) return FALLBACK;
        try {
            Object raw = method.invoke(who, args);
            List<?> list = asList(raw);
            if (list == null) return raw;
            String suffix = (channels ? CloneNotificationPolicy.CHANNEL_MARK : CloneNotificationPolicy.GROUP_MARK)
                    + guest.userId + ":" + guest.pkg;
            List<Object> kept = new ArrayList<Object>();
            for (Object o : list) {
                String id = idOf(o);
                if (id == null || !id.contains(suffix)) continue;
                if (o instanceof NotificationChannel) {
                    setTextField(o, "mId", CloneNotificationPolicy.unwrapChannel(id));
                    NotificationChannel ch = (NotificationChannel) o;
                    if (ch.getGroup() != null) ch.setGroup(CloneNotificationPolicy.unwrapGroup(ch.getGroup()));
                } else if (o instanceof NotificationChannelGroup) {
                    setTextField(o, "mId", CloneNotificationPolicy.unwrapGroup(id));
                }
                kept.add(o);
            }
            Object sliced = sliceLike(raw, kept);
            return sliced == null ? FALLBACK : sliced;
        } catch (Throwable ignored) {
            return FALLBACK;
        }
    }

    private static void cancelRecorded(Object base, CloneIdentityCompat.Guest guest) {
        List<CloneNotificationIndex.Posted> mine =
                CloneNotificationIndex.peek(CloneIdentityCompat.filesDir(), guest.pkg, guest.userId);
        for (CloneNotificationIndex.Posted p : mine) {
            if (!CloneNotificationPolicy.belongsTo(p.tag, guest.pkg, guest.userId)) continue;
            invokeCancel(base, p.tag, p.id);
            CloneNotificationIndex.forget(CloneIdentityCompat.filesDir(), guest.pkg, guest.userId, p.tag);
        }
    }

    private static void invokeCancel(Object base, String tag, int id) {
        if (base == null) return;
        for (Method m : base.getClass().getMethods()) {
            if (!"cancelNotificationWithTag".equals(m.getName())) continue;
            Object[] argv = new Object[m.getParameterTypes().length];
            int strings = 0;
            boolean idSet = false;
            boolean filled = true;
            Class<?>[] types = m.getParameterTypes();
            int stringSlots = 0;
            for (Class<?> t : types) if (t == String.class) stringSlots++;
            int tagSlot = stringSlots;
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t == String.class) {
                    strings++;
                    if (strings == tagSlot) argv[i] = tag;
                    else argv[i] = hostPkg();
                } else if (t == int.class) {
                    if (!idSet) {
                        argv[i] = id;
                        idSet = true;
                    } else argv[i] = 0;
                } else if (t == Integer.class) {
                    if (!idSet) {
                        argv[i] = id;
                        idSet = true;
                    } else argv[i] = 0;
                } else if (t == boolean.class) {
                    argv[i] = false;
                } else {
                    filled = false;
                    break;
                }
            }
            if (!filled || !idSet) continue;
            try {
                m.invoke(base, argv);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object filterActive(Object base, Method method, Object[] args, CloneIdentityCompat.Guest guest) {
        if (base == null) return FALLBACK;
        Object raw = method.invoke(base, args);
        if (raw instanceof StatusBarNotification[]) {
            StatusBarNotification[] all = (StatusBarNotification[]) raw;
            List<StatusBarNotification> kept = new ArrayList<StatusBarNotification>();
            for (StatusBarNotification sbn : all) {
                if (sbn != null && keepAndRestore(sbn, guest)) kept.add(sbn);
            }
            return kept.toArray(new StatusBarNotification[0]);
        }
        List<?> list = asList(raw);
        if (list == null) return FALLBACK;
        List<Object> kept = new ArrayList<Object>();
        for (Object o : list) {
            if (o instanceof StatusBarNotification && keepAndRestore((StatusBarNotification) o, guest)) kept.add(o);
        }
        Object sliced = sliceLike(raw, kept);
        return sliced == null ? FALLBACK : sliced;
    }

    private static boolean keepAndRestore(StatusBarNotification sbn, CloneIdentityCompat.Guest guest) {
        if (!CloneNotificationPolicy.belongsTo(sbn.getTag(), guest.pkg, guest.userId)) return false;
        Integer appId = CloneNotificationPolicy.appIdOf(sbn.getTag());
        String appTag = CloneNotificationPolicy.appTagOf(sbn.getTag());
        try {
            if (appId != null) setIntField(sbn, "mId", appId);
            setTextField(sbn, "mTag", appTag == null ? "" : appTag);
            Notification n = sbn.getNotification();
            if (n != null && Build.VERSION.SDK_INT >= 26 && n.getChannelId() != null) {
                n.setChannelId(CloneNotificationPolicy.unwrapChannel(n.getChannelId()));
            }
            if (n != null && n.getGroup() != null) n.setGroup(CloneNotificationPolicy.unwrapGroup(n.getGroup()));
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static Notification decorate(Notification source, CloneIdentityCompat.Guest guest) {
        Notification n = source.clone();
        if (Build.VERSION.SDK_INT >= 26) {
            n.setChannelId(CloneNotificationPolicy.channelId(n.getChannelId(), guest.pkg, guest.userId));
        }
        if (n.getGroup() != null) {
            n.setGroup(CloneNotificationPolicy.groupId(n.getGroup(), guest.pkg, guest.userId));
        }
        String name = displayName(guest);
        if (name != null && !name.isEmpty()) {
            String shown = name.length() > 80 ? name.substring(0, 80) : name;
            n.extras.putString("android.substName", shown);
            CharSequence sub = n.extras.getCharSequence("android.subText");
            if (sub == null || sub.length() == 0) n.extras.putCharSequence("android.subText", shown);
            else if (!sub.toString().startsWith(shown)) {
                n.extras.putCharSequence("android.subText", shown + " · " + sub);
            }
        }
        return n;
    }

    private static String displayName(CloneIdentityCompat.Guest guest) {
        String custom = TaskLabelCompat.customName(guest.pkg, guest.userId);
        String app = null;
        try {
            Application application = top.niunaijun.blackbox.app.BActivityThread.getApplication();
            if (application != null && application.getApplicationInfo() != null) {
                CharSequence label = application.getApplicationInfo().loadLabel(application.getPackageManager());
                if (label != null) app = label.toString();
            }
        } catch (Throwable ignored) {
        }
        return CloneNotificationPolicy.displayName(custom, app, guest.pkg, guest.userId);
    }

    private static Object baseOf(Object stub) {
        Class<?> c = stub == null ? null : stub.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("mBase");
                f.setAccessible(true);
                return f.get(stub);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String hostPkg() {
        try {
            return BlackBoxCore.getHostPkg();
        } catch (Throwable ignored) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<?> findList(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            List<?> list = asList(a);
            if (list != null) return list;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<?> asList(Object raw) {
        if (raw instanceof List) return (List<?>) raw;
        if (raw == null) return null;
        try {
            Method m = raw.getClass().getMethod("getList");
            Object v = m.invoke(raw);
            if (v instanceof List) return (List<?>) v;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object sliceLike(Object original, List<?> items) {
        if (original instanceof List) return items;
        if (original == null) return null;
        try {
            Constructor<?> ctor = original.getClass().getConstructor(List.class);
            return ctor.newInstance(items);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int indexOf(Object[] args, Class<?> type) {
        for (int i = 0; i < args.length; i++) {
            if (type.isInstance(args[i])) return i;
        }
        return -1;
    }

    private static int indexBefore(Object[] args, int before, Class<?> type) {
        for (int i = before - 1; i >= 0; i--) {
            if (args[i] == null && type == String.class) return i;
            if (type.isInstance(args[i])) return i;
        }
        return -1;
    }

    private static int lastString(Object[] args, String pkg) {
        int found = -1;
        for (int i = 0; i < args.length; i++) {
            if (!(args[i] instanceof String)) continue;
            String s = (String) args[i];
            if (s.isEmpty() || s.equals(pkg) || s.equals(hostPkg())) continue;
            found = i;
        }
        return found;
    }

    private static String idOf(Object o) {
        if (o instanceof NotificationChannel) return ((NotificationChannel) o).getId();
        if (o instanceof NotificationChannelGroup) return ((NotificationChannelGroup) o).getId();
        return null;
    }

    private static void restoreChannels(List<?> list, String[] ids, String[] groups) {
        if (list == null || ids == null) return;
        for (int i = 0; i < list.size() && i < ids.length; i++) {
            if (ids[i] == null) continue;
            Object o = list.get(i);
            if (o instanceof NotificationChannel) {
                setTextField(o, "mId", ids[i]);
                ((NotificationChannel) o).setGroup(groups[i]);
            }
        }
    }

    private static void restoreGroups(List<?> list, String[] ids) {
        if (list == null || ids == null) return;
        for (int i = 0; i < list.size() && i < ids.length; i++) {
            if (ids[i] == null) continue;
            if (list.get(i) instanceof NotificationChannelGroup) setTextField(list.get(i), "mId", ids[i]);
        }
    }

    private static boolean setTextField(Object target, String field, String value) {
        try {
            Field f = findField(target.getClass(), field);
            if (f == null) return false;
            f.setAccessible(true);
            f.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setIntField(Object target, String field, int value) throws Exception {
        Field f = findField(target.getClass(), field);
        if (f == null) return;
        f.setAccessible(true);
        f.setInt(target, value);
    }

    /** 隔离路径不能处理时，走原来的 BlackBox 通知服务，避免把 FALLBACK 交回给应用。 */
    private static Object legacyEnqueue(Object[] args, int tagIndex, int idIndex) {
        try {
            Notification notification = firstNotification(args);
            if (notification != null) {
                BNotificationManager.get().enqueueNotificationWithTag(intAt(args, idIndex), stringAt(args, tagIndex), notification);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Object legacyCancel(Object[] args, int tagIndex, int idIndex) {
        try {
            BNotificationManager.get().cancelNotificationWithTag(intAt(args, idIndex), stringAt(args, tagIndex));
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Object legacyChannels(Object[] args, boolean channels) {
        try {
            List<?> list = findList(args);
            if (list == null) return 0;
            for (Object item : list) {
                if (channels && item instanceof NotificationChannel) {
                    BNotificationManager.get().createNotificationChannel((NotificationChannel) item);
                } else if (!channels && item instanceof NotificationChannelGroup) {
                    BNotificationManager.get().createNotificationChannelGroup((NotificationChannelGroup) item);
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Object legacyDelete(Object[] args, boolean channel) {
        try {
            String id = lastPlainString(args);
            if (id == null) return 0;
            if (channel) BNotificationManager.get().deleteNotificationChannel(id);
            else BNotificationManager.get().deleteNotificationChannelGroup(id);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static Object legacyGetOne(Object[] args, boolean channel) {
        try {
            if (!channel) return null;
            return BNotificationManager.get().getNotificationChannel(lastPlainString(args));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object legacyGetMany(boolean channels) {
        try {
            String pkg = BActivityThread.getAppPackageName();
            if (channels) return BNotificationManager.get().getNotificationChannels(pkg);
            return BNotificationManager.get().getNotificationChannelGroups(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Notification firstNotification(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof Notification) return (Notification) arg;
        return null;
    }

    private static String stringAt(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length) return null;
        return args[index] instanceof String ? (String) args[index] : null;
    }

    private static int intAt(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length || !(args[index] instanceof Integer)) return 0;
        return (Integer) args[index];
    }

    private static String lastPlainString(Object[] args) {
        if (args == null) return null;
        String found = null;
        for (Object arg : args) if (arg instanceof String) found = (String) arg;
        return found;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
