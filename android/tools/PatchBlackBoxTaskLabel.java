import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 把 Bcore {@code TaskDescriptionCompat.getTaskDescriptionLabel(int, CharSequence)} 的方法体换成
 * {@code TaskLabelCompat.label(int, CharSequence)}，去掉最近任务里的「[B0]」前缀。
 *
 * <p>只改这一个方法，其余方法原样拷贝。可重复执行。
 * <p>用法：{@code java -cp asm.jar:. PatchBlackBoxTaskLabel TaskDescriptionCompat.class}
 */
public final class PatchBlackBoxTaskLabel implements Opcodes {
    private static final String METHOD = "getTaskDescriptionLabel";
    private static final String DESC = "(ILjava/lang/CharSequence;)Ljava/lang/String;";
    private static final String OWNER = "com/parallelspace/parallel_space/TaskLabelCompat";

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        byte[] in = Files.readAllBytes(path);
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        final int[] replaced = {0};
        cr.accept(new ClassVisitor(ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (METHOD.equals(name) && DESC.equals(desc)) {
                    replaced[0]++;
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    mv.visitCode();
                    mv.visitVarInsn(ILOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKESTATIC, OWNER, "label", DESC, false);
                    mv.visitInsn(ARETURN);
                    mv.visitMaxs(2, 2);
                    mv.visitEnd();
                    return new MethodVisitor(ASM9) {};
                }
                return super.visitMethod(access, name, desc, sig, ex);
            }
        }, 0);
        if (replaced[0] != 1) throw new IllegalStateException("expected 1 method, got " + replaced[0]);
        Files.write(path, cw.toByteArray());
        System.out.println("patched " + path);
    }
}
