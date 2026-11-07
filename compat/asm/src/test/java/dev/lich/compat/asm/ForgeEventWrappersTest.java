package dev.lich.compat.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A wrapper as {@code ASMEventHandler.createWrapper} generates it, for a static handler on an interface. */
class ForgeEventWrappersTest {
    /** Shaped like Electroblob's Wizardry's {@code Imbuement}. */
    public interface Imbuement {
        static int onEvent() {
            return 42;
        }
    }

    public static final class PlainHandler {
        public static int onEvent() {
            return 7;
        }
    }

    private static final class Loader extends ClassLoader {
        Loader() {
            super(ForgeEventWrappersTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static int callThroughWrapper(Class<?> owner) throws ReflectiveOperationException {
        Method handler = owner.getMethod("onEvent");
        String name = "generated/Wrapper" + owner.getSimpleName();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(ForgeEventWrappers.classVersion(handler), Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null,
                "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()I", null, null);
        mv.visitCode();
        ForgeEventWrappers.visitMethodInsn(mv, Opcodes.INVOKESTATIC, Type.getInternalName(owner), "onEvent", "()I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return (Integer) new Loader().define(name.replace('/', '.'), writer.toByteArray()).getMethod("call").invoke(null);
    }

    @Test
    void aStaticHandlerOnAnInterfaceCanBeCalled() throws ReflectiveOperationException {
        assertEquals(42, callThroughWrapper(Imbuement.class));
    }

    @Test
    void otherHandlersKeepTheirWrapperAsItWas() throws ReflectiveOperationException {
        assertEquals(Opcodes.V1_6, ForgeEventWrappers.classVersion(PlainHandler.class.getMethod("onEvent")));
        assertEquals(7, callThroughWrapper(PlainHandler.class));
    }

    @Test
    void thePatchTouchesCreateWrapperOnly() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "test/ASMEventHandler", null, "java/lang/Object", null);
        for (String name : new String[]{"createWrapper", "other"}) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, name, "(Ljava/lang/reflect/Method;)Ljava/lang/Class;", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitIntInsn(Opcodes.BIPUSH, Opcodes.V1_6);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "test/Sink", "version", "(Ljava/lang/Object;I)V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLdcInsn("o");
            mv.visitLdcInsn("n");
            mv.visitLdcInsn("d");
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/objectweb/asm/MethodVisitor", "visitMethodInsn",
                    "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        writer.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, 0);

        assertTrue(new ForgeEventWrappers().apply(node));

        boolean patchedVersion = false;
        boolean patchedCall = false;
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            assertFalse(insn instanceof IntInsnNode constant && constant.operand == Opcodes.V1_6);
            if (insn instanceof MethodInsnNode call && call.owner.equals("dev/lich/compat/asm/ForgeEventWrappers")) {
                patchedVersion |= call.name.equals("classVersion");
                patchedCall |= call.name.equals("visitMethodInsn") && call.getOpcode() == Opcodes.INVOKESTATIC;
            }
        }
        assertTrue(patchedVersion && patchedCall);
        boolean untouched = false;
        for (AbstractInsnNode insn : node.methods.get(1).instructions) {
            untouched |= insn instanceof IntInsnNode constant && constant.operand == Opcodes.V1_6;
        }
        assertTrue(untouched, "another method's constants stay");
    }

    @Test
    void aWriterLooksThroughTheContextClassLoaderBeforeGivingUp() {
        String hidden = "only/in/Context";
        ClassWriter classFile = new ClassWriter(0);
        classFile.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, hidden, null, "java/util/ArrayList", null);
        classFile.visitEnd();
        Loader context = new Loader();
        context.define(hidden.replace('/', '.'), classFile.toByteArray());
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        thread.setContextClassLoader(context);
        try {
            assertEquals("java/util/ArrayList", new Asm5ClassWriter(0).getCommonSuperClass(hidden, "java/util/ArrayList"));
        } finally {
            thread.setContextClassLoader(before);
        }
    }
}
