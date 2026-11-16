package com.parallelspace.parallel_space;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每个分身已经投出去的系统通知 (tag, id)。取消和全部取消只看自己的记录。
 * 文件原子替换；解析失败时不覆盖。
 */
public final class CloneNotificationIndex {
    public static final String FILE_NAME = "clone_notification_index.json";
    private static final Object LOCK = new Object();

    private CloneNotificationIndex() {}

    public static final class Posted {
        public final String tag;
        public final int id;

        public Posted(String tag, int id) {
            this.tag = tag == null ? "" : tag;
            this.id = id;
        }
    }

    public static List<Posted> list(Map<String, List<Posted>> all, String key) {
        if (all == null || key == null) return Collections.emptyList();
        List<Posted> v = all.get(key);
        if (v == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<Posted>(v));
    }

    /** 同一 tag 只留一条，后写入的 id 覆盖旧的（通知更新）。 */
    public static void add(Map<String, List<Posted>> all, String key, String tag, int id) {
        if (all == null || key == null || key.isEmpty()) return;
        List<Posted> cur = all.get(key);
        if (cur == null) {
            cur = new ArrayList<Posted>();
            all.put(key, cur);
        }
        String t = tag == null ? "" : tag;
        for (int i = 0; i < cur.size(); i++) {
            if (t.equals(cur.get(i).tag)) {
                cur.set(i, new Posted(t, id));
                return;
            }
        }
        cur.add(new Posted(t, id));
    }

    public static void remove(Map<String, List<Posted>> all, String key, String tag) {
        if (all == null || key == null) return;
        List<Posted> cur = all.get(key);
        if (cur == null) return;
        String t = tag == null ? "" : tag;
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (t.equals(cur.get(i).tag)) cur.remove(i);
        }
        if (cur.isEmpty()) all.remove(key);
    }

    public static List<Posted> clear(Map<String, List<Posted>> all, String key) {
        if (all == null || key == null) return Collections.emptyList();
        List<Posted> cur = all.remove(key);
        if (cur == null) return Collections.emptyList();
        return cur;
    }

    public static String toJson(Map<String, List<Posted>> all) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"v\":1,\"clones\":{");
        boolean firstKey = true;
        if (all != null) {
            for (Map.Entry<String, List<Posted>> e : all.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                if (!firstKey) sb.append(',');
                firstKey = false;
                sb.append(quote(e.getKey())).append(":[");
                boolean first = true;
                for (Posted p : e.getValue()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"tag\":").append(quote(p.tag)).append(",\"id\":").append(p.id).append('}');
                }
                sb.append(']');
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    public static Map<String, List<Posted>> parse(String json) {
        if (json == null) throw new IllegalArgumentException("empty");
        Map<String, List<Posted>> out = new LinkedHashMap<String, List<Posted>>();
        int clones = json.indexOf("\"clones\"");
        if (clones < 0) return out;
        int brace = json.indexOf('{', clones);
        if (brace < 0) throw new IllegalArgumentException("clones");
        int i = brace + 1;
        while (i < json.length()) {
            while (i < json.length() && json.charAt(i) <= ' ') i++;
            if (i < json.length() && json.charAt(i) == '}') break;
            if (json.charAt(i) != '"') throw new IllegalArgumentException("key");
            String key = readString(json, i);
            i = endOfString(json, i);
            while (i < json.length() && json.charAt(i) != '[') i++;
            if (i >= json.length()) throw new IllegalArgumentException("array");
            int end = json.indexOf(']', i);
            if (end < 0) throw new IllegalArgumentException("array");
            String body = json.substring(i + 1, end);
            List<Posted> list = new ArrayList<Posted>();
            int p = 0;
            while (p < body.length()) {
                int tagKey = body.indexOf("\"tag\"", p);
                if (tagKey < 0) break;
                int q = body.indexOf('"', body.indexOf(':', tagKey) + 1);
                String tag = readString(body, q);
                int idKey = body.indexOf("\"id\"", q);
                if (idKey < 0) throw new IllegalArgumentException("id");
                int n = body.indexOf(':', idKey) + 1;
                while (n < body.length() && body.charAt(n) <= ' ') n++;
                int n2 = n;
                if (n2 < body.length() && (body.charAt(n2) == '-' || body.charAt(n2) == '+')) n2++;
                while (n2 < body.length() && body.charAt(n2) >= '0' && body.charAt(n2) <= '9') n2++;
                int id = Integer.parseInt(body.substring(n, n2));
                list.add(new Posted(tag, id));
                p = n2;
            }
            if (!list.isEmpty()) out.put(key, list);
            i = end + 1;
            while (i < json.length() && (json.charAt(i) == ',' || json.charAt(i) <= ' ')) i++;
        }
        return out;
    }

    public static void remember(File filesDir, String pkg, int userId, String systemTag, int systemId) {
        mutate(filesDir, CloneNotificationPolicy.key(pkg, userId), systemTag, systemId, true);
    }

    public static void forget(File filesDir, String pkg, int userId, String systemTag) {
        mutate(filesDir, CloneNotificationPolicy.key(pkg, userId), systemTag, 0, false);
    }

    public static List<Posted> peek(File filesDir, String pkg, int userId) {
        synchronized (LOCK) {
            return list(read(filesDir), keyOf(pkg, userId));
        }
    }

    /** 取出并清空这个分身的记录。其他分身的记录还在。 */
    public static List<Posted> take(File filesDir, String pkg, int userId) {
        synchronized (LOCK) {
            Map<String, List<Posted>> all = read(filesDir);
            String key = CloneNotificationPolicy.key(pkg, userId);
            List<Posted> mine = clear(all, key);
            write(filesDir, all);
            return mine;
        }
    }

    private static String keyOf(String pkg, int userId) {
        return CloneNotificationPolicy.key(pkg, userId);
    }

    private static void mutate(File filesDir, String key, String tag, int id, boolean add) {
        if (filesDir == null || key == null || key.startsWith("#")) return;
        synchronized (LOCK) {
            Map<String, List<Posted>> all = read(filesDir);
            if (add) add(all, key, tag, id);
            else remove(all, key, tag);
            write(filesDir, all);
        }
    }

    private static Map<String, List<Posted>> read(File filesDir) {
        File f = new File(filesDir, FILE_NAME);
        if (!f.isFile()) return new LinkedHashMap<String, List<Posted>>();
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) return new LinkedHashMap<String, List<Posted>>();
            return parse(text);
        } catch (RuntimeException e) {
            return new LinkedHashMap<String, List<Posted>>();
        } catch (IOException e) {
            return new LinkedHashMap<String, List<Posted>>();
        }
    }

    private static void write(File filesDir, Map<String, List<Posted>> all) {
        if (!filesDir.exists() && !filesDir.mkdirs() && !filesDir.isDirectory()) return;
        File dest = new File(filesDir, FILE_NAME);
        File tmp = new File(filesDir, FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(toJson(all).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                tmp.delete();
            }
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    private static String readString(String s, int quoteAt) {
        StringBuilder sb = new StringBuilder();
        int i = quoteAt + 1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\' && i < s.length()) {
                char n = s.charAt(i++);
                if (n == 'u' && i + 4 <= s.length()) {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                } else if (n == 'n') sb.append('\n');
                else sb.append(n);
            } else sb.append(c);
        }
        throw new IllegalArgumentException("string");
    }

    private static int endOfString(String s, int quoteAt) {
        int i = quoteAt + 1;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '"') return i;
        }
        return i;
    }
}
