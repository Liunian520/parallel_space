import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

/**
 * 给 bcore 补上分身设备标识和通知隔离的调用点。
 *
 * <p>settings 的 {@code SystemProviderStub} / {@code ContentProviderStub.invoke} 在原逻辑之后
 * 调用 {@code CloneIdentityCompat.rewrite}。通知代理的 hook 改为调用 {@code CloneNotificationCompat}，
 * 真实服务对象仍是 hook 的第一个参数，避免再进 NotificationManager 造成递归。
 *
 * <p>可重复执行。用法：{@code java -cp asm.jar:. PatchBlackBoxIsolation classes.jar}
 */
public final class PatchBlackBoxIsolation implements Opcodes {
    private static final String INVOKE = "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String IDENTITY = "com/parallelspace/parallel_space/CloneIdentityCompat";
    private static final String NOTIFY = "com/parallelspace/parallel_space/CloneNotificationCompat";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: PatchBlackBoxIsolation classes.jar");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);
        Path tmp = jar.resolveSibling(jar.getFileName() + ".dir");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        // 用 jar 工具解出需要改的 class，改完再打回 jar。
        run(List.of("jar", "xf", jar.toAbsolutePath().toString()), tmp);
        int n = 0;
        n += patchIdentity(tmp.resolve("top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.class"));
        n += patchIdentity(tmp.resolve("top/niunaijun/blackbox/fake/service/context/providers/ContentProviderStub.class"));
        n += patchNotificationInvoke(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy.class"));
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$EnqueueNotificationWithTag.class"),
                "enqueue", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;II)Ljava/lang/Object;", true);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$CancelNotificationWithTag.class"),
                "cancel", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;II)Ljava/lang/Object;", true);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$CreateNotificationChannels.class"),
                "createChannels", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$CreateNotificationChannelGroups.class"),
                "createGroups", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$DeleteNotificationChannel.class"),
                "deleteChannel", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$DeleteNotificationChannelGroup.class"),
                "deleteGroup", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$GetNotificationChannel.class"),
                "getChannel", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$GetNotificationChannels.class"),
                "getChannels", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        n += patchHook(tmp.resolve("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$GetNotificationChannelGroups.class"),
                "getGroups", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        if (n != 12) throw new IllegalStateException("expected 12 patches, got " + n);
        List<String> update = new ArrayList<String>();
        update.add("jar");
        update.add("uf");
        update.add(jar.toAbsolutePath().toString());
        update.add("top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.class");
        update.add("top/niunaijun/blackbox/fake/service/context/providers/ContentProviderStub.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$EnqueueNotificationWithTag.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$CancelNotificationWithTag.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$CreateNotificationChannels.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$CreateNotificationChannelGroups.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$DeleteNotificationChannel.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$DeleteNotificationChannelGroup.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$GetNotificationChannel.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$GetNotificationChannels.class");
        update.add("top/niunaijun/blackbox/fake/service/INotificationManagerProxy$GetNotificationChannelGroups.class");
        run(update, tmp);
        deleteTree(tmp);
        System.out.println("patched " + n + " classes in " + jar);
    }

    private static int patchIdentity(Path classFile) throws Exception {
        byte[] in = Files.readAllBytes(classFile);
        if (contains(in, IDENTITY)) {
            System.out.println("skip identity " + classFile.getFileName());
            return 1;
        }
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        final String[] owner = {null};
        final int[] renamed = {0};
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                owner[0] = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if ("invoke".equals(name) && INVOKE.equals(desc)) {
                    renamed[0]++;
                    return super.visitMethod(ACC_PUBLIC, "invokeOriginal", desc, signature, exceptions);
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                MethodVisitor mv = super.visitMethod(ACC_PUBLIC, "invoke", INVOKE, null, null);
                mv.visitCode();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitMethodInsn(INVOKEVIRTUAL, owner[0], "invokeOriginal", INVOKE, false);
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitMethodInsn(INVOKESTATIC, IDENTITY, "rewrite",
                        "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                mv.visitInsn(ARETURN);
                mv.visitMaxs(4, 4);
                mv.visitEnd();
                super.visitEnd();
            }
        }, 0);
        if (renamed[0] != 1) throw new IllegalStateException("invoke count " + renamed[0] + " in " + classFile);
        byte[] out = cw.toByteArray();
        verify(out);
        Files.write(classFile, out);
        System.out.println("identity " + classFile.getFileName());
        return 1;
    }

    private static int patchNotificationInvoke(Path classFile) throws Exception {
        byte[] in = Files.readAllBytes(classFile);
        if (contains(in, NOTIFY)) {
            System.out.println("skip notify invoke " + classFile.getFileName());
            return 1;
        }
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        final int[] replaced = {0};
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if ("invoke".equals(name) && INVOKE.equals(desc)) {
                    replaced[0]++;
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    mv.visitCode();
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitMethodInsn(INVOKESTATIC, NOTIFY, "before",
                            "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", false);
                    mv.visitInsn(DUP);
                    mv.visitFieldInsn(GETSTATIC, NOTIFY, "FALLBACK", "Ljava/lang/Object;");
                    Label ret = new Label();
                    mv.visitJumpInsn(IF_ACMPNE, ret);
                    mv.visitInsn(POP);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitMethodInsn(INVOKESTATIC, "top/niunaijun/blackbox/utils/MethodParameterUtils",
                            "replaceAllAppPkg", "([Ljava/lang/Object;)V", false);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitMethodInsn(INVOKESPECIAL, "top/niunaijun/blackbox/fake/hook/ClassInvocationStub",
                            "invoke", INVOKE, false);
                    mv.visitInsn(ARETURN);
                    mv.visitLabel(ret);
                    mv.visitFrame(F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"});
                    mv.visitInsn(ARETURN);
                    mv.visitMaxs(4, 4);
                    mv.visitEnd();
                    return new MethodVisitor(ASM9) {};
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        }, 0);
        if (replaced[0] != 1) throw new IllegalStateException("notify invoke " + replaced[0]);
        byte[] out = cw.toByteArray();
        verify(out);
        Files.write(classFile, out);
        System.out.println("notify invoke");
        return 1;
    }

    private static int patchHook(Path classFile, String method, String desc, boolean withIndexes) throws Exception {
        byte[] in = Files.readAllBytes(classFile);
        if (contains(in, NOTIFY)) {
            System.out.println("skip hook " + method);
            return 1;
        }
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        final String[] owner = {null};
        final int[] replaced = {0};
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                owner[0] = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String methodDesc, String signature, String[] exceptions) {
                if ("hook".equals(name) && INVOKE.equals(methodDesc)) {
                    replaced[0]++;
                    MethodVisitor mv = super.visitMethod(access, name, methodDesc, signature, exceptions);
                    mv.visitCode();
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    if (withIndexes) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitMethodInsn(INVOKEVIRTUAL, owner[0], "getTagIndex", "()I", false);
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitMethodInsn(INVOKEVIRTUAL, owner[0], "getIdIndex", "()I", false);
                    }
                    mv.visitMethodInsn(INVOKESTATIC, NOTIFY, method, desc, false);
                    mv.visitInsn(ARETURN);
                    mv.visitMaxs(withIndexes ? 5 : 3, 4);
                    mv.visitEnd();
                    return new MethodVisitor(ASM9) {};
                }
                return super.visitMethod(access, name, methodDesc, signature, exceptions);
            }
        }, 0);
        if (replaced[0] != 1) throw new IllegalStateException("hook " + method + " -> " + replaced[0]);
        byte[] out = cw.toByteArray();
        verify(out);
        Files.write(classFile, out);
        System.out.println("hook " + method);
        return 1;
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.EXPAND_FRAMES);
        Analyzer<BasicValue> analyzer = new Analyzer<BasicValue>(new BasicInterpreter());
        for (MethodNode mn : cn.methods) {
            analyzer.analyze(cn.name, mn);
        }
    }

    private static boolean contains(byte[] data, String utf) {
        byte[] needle = utf.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void run(List<String> cmd, Path dir) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException(cmd.toString());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
