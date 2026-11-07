package dev.lich.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodrefTagsTest {
    private final List<String> fixed = new ArrayList<>();

    /** A class with {@code static int answer()}, returning 42. */
    private static byte[] owner() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Owner", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "answer", "()I", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.BIPUSH, 42);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A class whose {@code static int call()} calls {@code owner.answer()} with {@code itf} set, as TickCentral leaves
     * it, after a {@code long} and a {@code double} constant (which take two constant pool slots each).
     */
    private static byte[] caller(String name, String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(1234567890123L);
        mv.visitInsn(Opcodes.POP2);
        mv.visitLdcInsn(2.5);
        mv.visitInsn(Opcodes.POP2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "answer", "()I", true);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        if (owner.equals(name)) {
            MethodVisitor answer = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "answer", "()I", null, null);
            answer.visitCode();
            answer.visitIntInsn(Opcodes.BIPUSH, 42);
            answer.visitInsn(Opcodes.IRETURN);
            answer.visitMaxs(0, 0);
            answer.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Object call(Map<String, byte[]> classes, String name) throws Exception {
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String binaryName) throws ClassNotFoundException {
                byte[] bytes = classes.get(binaryName.replace('.', '/'));
                if (bytes == null) {
                    throw new ClassNotFoundException(binaryName);
                }
                return defineClass(binaryName, bytes, 0, bytes.length);
            }
        };
        try {
            return loader.loadClass(name.replace('/', '.')).getMethod("call").invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : new RuntimeException(e.getCause());
        } catch (IncompatibleClassChangeError e) {
            throw new RuntimeException(e);
        }
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    return call;
                }
            }
        }
        throw new AssertionError("no call");
    }

    @Test
    void aStaticCallToAClassThroughAnInterfaceRefLinksOnceFixed() throws Exception {
        byte[] caller = caller("test/Caller", "test/Owner");
        // What Java 9+ does with it as written, unlike Java 8.
        Throwable refused = assertThrows(Throwable.class,
                () -> call(Map.of("test/Owner", owner(), "test/Caller", caller), "test/Caller"));
        Throwable error = refused instanceof RuntimeException wrapped && wrapped.getCause() != null ? wrapped.getCause() : refused;
        assertInstanceOf(IncompatibleClassChangeError.class, error);

        byte[] fixedCaller = MethodrefTags.fix(caller, "test/Owner"::equals, fixed::add);
        assertFalse(onlyCall(fixedCaller).itf);
        assertEquals(List.of("test/Owner"), fixed);
        assertEquals(42, call(Map.of("test/Owner", owner(), "test/Caller", fixedCaller), "test/Caller"));
    }

    @Test
    void aCallToTheClassItselfIsFixedWithoutLookingItUp() throws Exception {
        byte[] self = caller("test/Self", "test/Self");
        byte[] fixedSelf = MethodrefTags.fix(self, owner -> false, fixed::add);
        assertEquals(List.of("test/Self"), fixed);
        assertEquals(42, call(Map.of("test/Self", fixedSelf), "test/Self"));
    }

    @Test
    void interfacesAndOwnersThatCantBeFoundKeepTheirRefs() {
        byte[] caller = caller("test/Caller", "test/Owner");
        assertSame(caller, MethodrefTags.fix(caller, owner -> false, fixed::add));
        assertTrue(fixed.isEmpty());
        assertTrue(onlyCall(caller).itf);
    }

    @Test
    void whatIsntAClassFileComesBackAsItIs() {
        byte[] notAClass = {1, 2, 3};
        assertSame(notAClass, MethodrefTags.fix(notAClass, owner -> true, fixed::add));
        byte[] truncated = java.util.Arrays.copyOf(caller("test/Caller", "test/Owner"), 40);
        assertSame(truncated, MethodrefTags.fix(truncated, owner -> true, fixed::add));
        assertTrue(fixed.isEmpty());
        assertSame(null, MethodrefTags.fix(null, owner -> true, fixed::add));
    }
}
