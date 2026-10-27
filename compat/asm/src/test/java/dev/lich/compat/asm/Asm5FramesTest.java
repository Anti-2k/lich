package dev.lich.compat.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Asm5FramesTest {
    /** A Java 5 class whose method has a compressed frame after a branch, as ASP + GS Patcher writes into GraviSuite. */
    private static byte[] java5Class(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(Z)I", null, null);
        mv.visitCode();
        Label otherwise = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitJumpInsn(Opcodes.IFEQ, otherwise);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(otherwise);
        Asm5Frames.visitFrame(mv, Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void compressedFramesInJava5ClassesAreDroppedAndTheClassStillRuns() throws Exception {
        String name = Asm5FramesTest.class.getPackageName().replace('.', '/') + "/Java5Frames";
        Class<?> defined = MethodHandles.lookup().defineClass(java5Class(name));
        assertEquals(1, defined.getMethod("pick", boolean.class).invoke(null, true));
        assertEquals(2, defined.getMethod("pick", boolean.class).invoke(null, false));
    }

    @Test
    void otherFrameErrorsStillThrow() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "x/Y", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_STATIC, "m", "()V", null, null);
        mv.visitCode();
        // An unknown frame type is a real bug in the caller, not ASM 5 leniency.
        assertThrows(IllegalArgumentException.class, () -> Asm5Frames.visitFrame(mv, 99, 0, null, 0, null));
    }
}
