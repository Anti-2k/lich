package dev.lich.compat.asm;

import org.objectweb.asm.MethodVisitor;

/**
 * ASM 5's {@code ClassWriter} took compressed stack map frames ({@code F_SAME}, {@code F_APPEND}, ...) in a Java 5
 * or older class and wrote them anyway; ASM 9 throws {@code IllegalArgumentException} instead. The JVM never reads
 * frames in those classes (type checking starts at Java 6; older classes are verified by inference), so dropping
 * the frame gives a class that runs exactly like ASM 5's. ASP + GS Patcher does this when it patches GraviSuite.
 * ASM 9 throws the same way for a writer that doesn't know its class version yet, though: then the class may
 * need its frames ({@link Asm5ClassWriter}), and only a class that really is old loses them.
 */
public final class Asm5Frames {
    // ASM 9 throws before it changes any of the writer's state, so the method can go on after it.
    private static final String OLD_CLASS = "Class versions V1_5 or less must use F_NEW frames.";

    private Asm5Frames() {
    }

    /** The instance method, redirected with its receiver as the argument. */
    public static void visitFrame(MethodVisitor visitor, int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        try {
            visitor.visitFrame(type, numLocal, local, numStack, stack);
        } catch (IllegalArgumentException e) {
            if (!OLD_CLASS.equals(e.getMessage())) {
                throw e;
            }
            if (Asm5ClassWriter.learnVersion(visitor)) {
                visitor.visitFrame(type, numLocal, local, numStack, stack);
            }
        }
    }
}
