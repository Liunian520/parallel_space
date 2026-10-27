import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 把 Bcore {@code BlackBoxCore.getProcessName(Context)} 的方法体换成
 * {@code ProcessNameCompat.get(Context)}。
 *
 * <p>只改这一个方法。其它方法通过 {@code ClassWriter(ClassReader, 0)} 原样拷贝，
 * 避免重算 StackMap 把 R8 产物弄坏。可重复执行。
 *
 * <p>用法：{@code java -cp asm.jar PatchBlackBoxProcessName BlackBoxCore.class}
 */
public final class PatchBlackBoxProcessName implements Opcodes {
    private static final String METHOD = "getProcessName";
    private static final String DESC = "(Landroid/content/Context;)Ljava/lang/String;";
    private static final String OWNER = "com/parallelspace/parallel_space/ProcessNameCompat";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: PatchBlackBoxProcessName <BlackBoxCore.class>");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        byte[] in = Files.readAllBytes(path);
        byte[] out = patch(in);
        Files.write(path, out);
        System.out.println("patched " + path + " (" + in.length + " -> " + out.length + ")");
    }

    static byte[] patch(byte[] in) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        final int[] replaced = {0};
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (METHOD.equals(name) && DESC.equals(descriptor)) {
                    replaced[0]++;
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    mv.visitCode();
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitMethodInsn(INVOKESTATIC, OWNER, "get", DESC, false);
                    mv.visitInsn(ARETURN);
                    mv.visitMaxs(1, 1);
                    mv.visitEnd();
                    return new MethodVisitor(ASM9) {};
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        if (replaced[0] != 1) {
            throw new IllegalStateException("expected to patch exactly 1 getProcessName, got " + replaced[0]);
        }
        return cw.toByteArray();
    }
}
