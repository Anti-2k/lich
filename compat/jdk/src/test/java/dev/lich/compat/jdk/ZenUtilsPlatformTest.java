package dev.lich.compat.jdk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ZenUtils' {@code Reference}, shaped as it is: {@code IS_CLEANROOM = !IS_JAVA_1_8}. */
class ZenUtilsPlatformTest {
    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static byte[] reference() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Reference", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "IS_CLEANROOM", "Z", null, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        Label java8 = new Label();
        Label set = new Label();
        mv.visitInsn(Opcodes.ICONST_0); // IS_JAVA_1_8 on Java 25
        mv.visitJumpInsn(Opcodes.IFNE, java8);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, set);
        mv.visitLabel(java8);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(set);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "test/Reference", "IS_CLEANROOM", "Z");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void itIsNotCleanroomOnJava25() throws ReflectiveOperationException {
        ClassNode node = new ClassNode();
        new ClassReader(reference()).accept(node, 0);
        assertTrue(new ZenUtilsPlatform().apply(node));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Class<?> patched = new Loader().define("test.Reference", writer.toByteArray());
        assertFalse(patched.getField("IS_CLEANROOM").getBoolean(null));
    }

    @Test
    void withoutThePatchItWouldBe() throws ReflectiveOperationException {
        assertEquals(true, new Loader().define("test.Reference", reference()).getField("IS_CLEANROOM").getBoolean(null));
    }
}
