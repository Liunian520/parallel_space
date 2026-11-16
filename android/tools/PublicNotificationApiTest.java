import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 对着公开 android.jar 核对：Notification 上不能调用 setChannelId/setGroup，
 * Builder、渠道构造函数和 StatusBarNotification 的公开构造函数必须存在。
 *
 * <p>用法：{@code java -cp android.jar:. PublicNotificationApiTest}
 */
public final class PublicNotificationApiTest {
    public static void main(String[] args) throws Exception {
        Class<?> notification = Class.forName("android.app.Notification");
        assertAbsent(notification, "setChannelId");
        assertAbsent(notification, "setGroup");
        require(notification, "getChannelId");
        require(notification, "getGroup");
        notification.getField("extras");

        Class<?> builder = Class.forName("android.app.Notification$Builder");
        builder.getMethod("setChannelId", String.class);
        builder.getMethod("setGroup", String.class);
        builder.getMethod("setExtras", Class.forName("android.os.Bundle"));
        builder.getMethod("recoverBuilder", Class.forName("android.content.Context"), notification);
        builder.getMethod("build");

        Class<?> channel = Class.forName("android.app.NotificationChannel");
        channel.getConstructor(String.class, CharSequence.class, int.class);
        channel.getMethod("setGroup", String.class);
        channel.getMethod("setDescription", String.class);
        channel.getMethod("setSound", Class.forName("android.net.Uri"), Class.forName("android.media.AudioAttributes"));
        channel.getMethod("setAllowBubbles", boolean.class);
        channel.getMethod("setConversationId", String.class, String.class);
        channel.getMethod("setBlockable", boolean.class);

        Class<?> group = Class.forName("android.app.NotificationChannelGroup");
        group.getConstructor(String.class, CharSequence.class);
        group.getMethod("setDescription", String.class);
        group.getMethod("getChannels");

        Class<?> sbn = Class.forName("android.service.notification.StatusBarNotification");
        boolean ctor = false;
        for (Constructor<?> c : sbn.getConstructors()) {
            if (c.getParameterCount() == 10) ctor = true;
        }
        if (!ctor) throw new AssertionError("StatusBarNotification 缺少 10 参数公开构造函数");
        System.out.println("PublicNotificationApiTest ok");
    }

    private static void assertAbsent(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName())) {
                throw new AssertionError(type.getSimpleName() + "." + name + " 在公开 SDK 里存在，通知隔离不能调用它");
            }
        }
    }

    private static void require(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (name.equals(method.getName())) return;
        }
        throw new AssertionError("缺少公开方法 " + type.getSimpleName() + "." + name);
    }
}
