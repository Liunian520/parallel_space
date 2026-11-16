package com.parallelspace.parallel_space;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * {@code files/clone_identity.json} 的读写。格式固定、字段可空，解析失败时拒绝覆盖原文件。
 * 不依赖 Android SDK，单测可以直接跑。
 */
public final class CloneIdentityFile {
    public static final String FILE_NAME = "clone_identity.json";
    private static final Object LOCK = new Object();

    private CloneIdentityFile() {}

    public static File storeFile(File filesDir) {
        return new File(filesDir, FILE_NAME);
    }

    public static String toJson(Map<String, CloneIdentityPolicy.Record> clones) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"v\":1,\"clones\":{");
        boolean first = true;
        if (clones != null) {
            for (Map.Entry<String, CloneIdentityPolicy.Record> e : clones.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(e.getKey())).append(":{");
                sb.append("\"enabled\":").append(e.getValue().enabled ? "true" : "false");
                if (CloneIdentityPolicy.isValidAndroidId(e.getValue().androidId)) {
                    sb.append(",\"androidId\":").append(quote(e.getValue().androidId));
                }
                sb.append('}');
            }
        }
        sb.append("}}");
        return sb.toString();
    }

    public static Map<String, CloneIdentityPolicy.Record> parse(String json) {
        if (json == null) throw new IllegalArgumentException("empty");
        Json p = new Json(json.trim());
        p.skip();
        p.expect('{');
        Map<String, CloneIdentityPolicy.Record> clones = new LinkedHashMap<String, CloneIdentityPolicy.Record>();
        boolean sawClones = false;
        while (true) {
            p.skip();
            if (p.peek('}')) {
                p.expect('}');
                break;
            }
            String key = p.string();
            p.skip();
            p.expect(':');
            p.skip();
            if ("clones".equals(key)) {
                sawClones = true;
                clones = p.clones();
            } else if (p.peek('{')) {
                p.skipValue();
            } else if (p.peek('[')) {
                p.skipValue();
            } else {
                p.skipPrimitive();
            }
            p.skip();
            if (p.peek(',')) {
                p.expect(',');
                continue;
            }
            if (p.peek('}')) {
                p.expect('}');
                break;
            }
            throw new IllegalArgumentException("object");
        }
        if (!sawClones && json.contains("\"clones\"")) {
            throw new IllegalArgumentException("clones");
        }
        return clones;
    }

    public static CloneIdentityPolicy.Record get(File filesDir, String pkg, int userId) {
        Map<String, CloneIdentityPolicy.Record> all = readOrEmpty(filesDir);
        CloneIdentityPolicy.Record r = all.get(CloneIdentityPolicy.normalizeKey(pkg, userId));
        if (r == null) return new CloneIdentityPolicy.Record(false, null);
        return r;
    }

    public static CloneIdentityPolicy.Record setEnabled(File filesDir, String pkg, int userId, boolean enabled, Random random) {
        String key = requireKey(pkg, userId);
        return update(filesDir, key, new Edit() {
            @Override
            public CloneIdentityPolicy.Record apply(CloneIdentityPolicy.Record current, Set<String> used) {
                if (!enabled) return CloneIdentityPolicy.disable(current);
                String fresh = CloneIdentityPolicy.randomAndroidId(random);
                int guard = 0;
                while (used.contains(fresh) && guard++ < 8) {
                    fresh = CloneIdentityPolicy.randomAndroidId(random);
                }
                return CloneIdentityPolicy.enable(current, fresh);
            }
        });
    }

    public static CloneIdentityPolicy.Record regenerate(File filesDir, String pkg, int userId, Random random) {
        String key = requireKey(pkg, userId);
        return update(filesDir, key, new Edit() {
            @Override
            public CloneIdentityPolicy.Record apply(CloneIdentityPolicy.Record current, Set<String> used) {
                String fresh = CloneIdentityPolicy.randomAndroidId(random);
                int guard = 0;
                while ((used.contains(fresh) || (current != null && fresh.equals(current.androidId))) && guard++ < 8) {
                    fresh = CloneIdentityPolicy.randomAndroidId(random);
                }
                return CloneIdentityPolicy.regenerate(fresh);
            }
        });
    }

    public static void remove(File filesDir, String pkg, int userId) {
        String key = CloneIdentityPolicy.normalizeKey(pkg, userId);
        if (key.isEmpty() || filesDir == null) return;
        synchronized (LOCK) {
            Loaded loaded = load(filesDir);
            if (!loaded.ok || !loaded.clones.containsKey(key)) return;
            loaded.clones.remove(key);
            write(filesDir, loaded.clones);
        }
    }

    /**
     * @return 应用后的记录；{@code UNCHANGED} 时返回本机当前记录。写入失败抛异常，原文件不动。
     */
    public static CloneIdentityPolicy.Record applyDecision(
            File filesDir,
            String pkg,
            int userId,
            CloneIdentityPolicy.Decision decision,
            Random random) {
        if (decision == null) throw new IllegalArgumentException("decision");
        String key = requireKey(pkg, userId);
        if (decision.action == CloneIdentityPolicy.UNCHANGED) {
            return get(filesDir, pkg, userId);
        }
        return update(filesDir, key, new Edit() {
            @Override
            public CloneIdentityPolicy.Record apply(CloneIdentityPolicy.Record current, Set<String> used) {
                if (decision.action == CloneIdentityPolicy.DISABLE_KEEP) {
                    return CloneIdentityPolicy.disable(current);
                }
                if (decision.action == CloneIdentityPolicy.ASSIGN_NEW) {
                    String fresh = unique(random, used, null);
                    return new CloneIdentityPolicy.Record(true, fresh);
                }
                if (decision.action == CloneIdentityPolicy.APPLY) {
                    if (decision.enabled && CloneIdentityPolicy.isValidAndroidId(decision.androidId)
                            && used.contains(decision.androidId)) {
                        return new CloneIdentityPolicy.Record(true, unique(random, used, decision.androidId));
                    }
                    if (decision.enabled && !CloneIdentityPolicy.isValidAndroidId(decision.androidId)) {
                        return new CloneIdentityPolicy.Record(true, unique(random, used, null));
                    }
                    String id = CloneIdentityPolicy.isValidAndroidId(decision.androidId) ? decision.androidId : null;
                    if (!decision.enabled && current != null && id == null) {
                        return CloneIdentityPolicy.disable(current);
                    }
                    return new CloneIdentityPolicy.Record(decision.enabled, id);
                }
                return current == null ? new CloneIdentityPolicy.Record(false, null) : current;
            }
        });
    }

    public static Set<String> usedIds(Map<String, CloneIdentityPolicy.Record> clones, String exceptKey) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<String>();
        if (clones == null) return ids;
        for (Map.Entry<String, CloneIdentityPolicy.Record> e : clones.entrySet()) {
            if (exceptKey != null && exceptKey.equals(e.getKey())) continue;
            if (e.getValue() != null && CloneIdentityPolicy.isValidAndroidId(e.getValue().androidId)) {
                ids.add(e.getValue().androidId);
            }
        }
        return ids;
    }

    private static String unique(Random random, Set<String> used, String alsoAvoid) {
        String fresh = CloneIdentityPolicy.randomAndroidId(random);
        int guard = 0;
        while ((used.contains(fresh) || fresh.equals(alsoAvoid)) && guard++ < 8) {
            fresh = CloneIdentityPolicy.randomAndroidId(random);
        }
        return fresh;
    }

    private static String requireKey(String pkg, int userId) {
        String key = CloneIdentityPolicy.normalizeKey(pkg, userId);
        if (key.startsWith("#") || pkg == null || pkg.trim().isEmpty()) {
            throw new IllegalArgumentException("pkg");
        }
        return key;
    }

    private interface Edit {
        CloneIdentityPolicy.Record apply(CloneIdentityPolicy.Record current, Set<String> usedByOthers);
    }

    private static CloneIdentityPolicy.Record update(File filesDir, String key, Edit edit) {
        synchronized (LOCK) {
            Loaded loaded = load(filesDir);
            if (!loaded.ok) {
                throw new IllegalStateException("标识文件损坏，已停止写入");
            }
            Set<String> used = usedIds(loaded.clones, key);
            CloneIdentityPolicy.Record next = edit.apply(loaded.clones.get(key), used);
            if (next == null) next = new CloneIdentityPolicy.Record(false, null);
            if (!next.enabled && next.androidId == null) loaded.clones.remove(key);
            else loaded.clones.put(key, next);
            write(filesDir, loaded.clones);
            return next;
        }
    }

    private static Map<String, CloneIdentityPolicy.Record> readOrEmpty(File filesDir) {
        Loaded loaded = load(filesDir);
        if (!loaded.ok) return Collections.emptyMap();
        return loaded.clones;
    }

    private static Loaded load(File filesDir) {
        File f = storeFile(filesDir);
        if (!f.isFile()) return Loaded.ok(new LinkedHashMap<String, CloneIdentityPolicy.Record>());
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) return Loaded.ok(new LinkedHashMap<String, CloneIdentityPolicy.Record>());
            return Loaded.ok(new LinkedHashMap<String, CloneIdentityPolicy.Record>(parse(text)));
        } catch (RuntimeException e) {
            return Loaded.bad();
        } catch (IOException e) {
            return Loaded.bad();
        }
    }

    private static void write(File filesDir, Map<String, CloneIdentityPolicy.Record> clones) {
        if (filesDir == null) throw new IllegalArgumentException("dir");
        if (!filesDir.exists() && !filesDir.mkdirs() && !filesDir.isDirectory()) {
            throw new IllegalStateException("无法创建目录");
        }
        File dest = storeFile(filesDir);
        File tmp = new File(filesDir, FILE_NAME + ".tmp");
        byte[] bytes = toJson(clones).getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw new IllegalStateException("写入标识失败");
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomic) {
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                tmp.delete();
                throw new IllegalStateException("替换标识文件失败");
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

    private static final class Loaded {
        final boolean ok;
        final Map<String, CloneIdentityPolicy.Record> clones;

        private Loaded(boolean ok, Map<String, CloneIdentityPolicy.Record> clones) {
            this.ok = ok;
            this.clones = clones;
        }

        static Loaded ok(Map<String, CloneIdentityPolicy.Record> clones) {
            return new Loaded(true, clones);
        }

        static Loaded bad() {
            return new Loaded(false, new LinkedHashMap<String, CloneIdentityPolicy.Record>());
        }
    }

    /** 只解析本文件写出的对象，够用即可。 */
    private static final class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length() && s.charAt(i) <= ' ') i++;
        }

        boolean peek(char c) {
            skip();
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            skip();
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c);
            i++;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) break;
                    char n = s.charAt(i++);
                    if (n == 'u') {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("unicode");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else if (n == 'n') sb.append('\n');
                    else sb.append(n);
                } else sb.append(c);
            }
            throw new IllegalArgumentException("string");
        }

        Map<String, CloneIdentityPolicy.Record> clones() {
            expect('{');
            Map<String, CloneIdentityPolicy.Record> out = new LinkedHashMap<String, CloneIdentityPolicy.Record>();
            while (true) {
                skip();
                if (peek('}')) {
                    expect('}');
                    return out;
                }
                String key = string();
                expect(':');
                out.put(key, record());
                skip();
                if (peek(',')) {
                    expect(',');
                    continue;
                }
                expect('}');
                return out;
            }
        }

        CloneIdentityPolicy.Record record() {
            expect('{');
            boolean enabled = false;
            String id = null;
            boolean any = false;
            while (true) {
                skip();
                if (peek('}')) {
                    expect('}');
                    break;
                }
                String key = string();
                expect(':');
                skip();
                any = true;
                if ("enabled".equals(key)) {
                    enabled = bool();
                } else if ("androidId".equals(key)) {
                    id = string();
                } else {
                    skipValue();
                }
                skip();
                if (peek(',')) {
                    expect(',');
                    continue;
                }
                expect('}');
                break;
            }
            if (!any) throw new IllegalArgumentException("record");
            if (!CloneIdentityPolicy.isValidAndroidId(id)) id = null;
            return new CloneIdentityPolicy.Record(enabled, id);
        }

        boolean bool() {
            skip();
            if (s.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return false;
            }
            throw new IllegalArgumentException("bool");
        }

        void skipPrimitive() {
            skip();
            if (peek('"')) {
                string();
                return;
            }
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']') break;
                i++;
            }
        }

        void skipValue() {
            skip();
            if (peek('{') || peek('[')) {
                char open = s.charAt(i);
                char close = open == '{' ? '}' : ']';
                int depth = 0;
                boolean inStr = false;
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (inStr) {
                        if (c == '\\' && i < s.length()) i++;
                        else if (c == '"') inStr = false;
                        continue;
                    }
                    if (c == '"') inStr = true;
                    else if (c == open) depth++;
                    else if (c == close) {
                        depth--;
                        if (depth == 0) return;
                    }
                }
                throw new IllegalArgumentException("nest");
            }
            skipPrimitive();
        }
    }
}
