package dev.lich.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatTransformerTest {
    private final List<String> changes = new ArrayList<>();
    private final CompatTransformer compat = CompatTransformer.builder()
            .renameType("java/util/jar/Pack200", "shim/Pack200")
            .renameType("java/util/jar/Pack200$Unpacker", "shim/Pack200$Unpacker")
            .redirect("java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V", "shim/SystemShims")
            .redirect("java/lang/Thread", "stop", "()V", "shim/ThreadShims")
            .patch("test.Patched", node -> {
                node.access |= Opcodes.ACC_FINAL;
                return true;
            })
            .listener((className, what) -> changes.add(className + ": " + what))
            .build();

    @Test
    void classesWithNothingToDoComeBackAsTheSameArray() {
        byte[] bytes = generate("test.Plain", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            mv.visitInsn(Opcodes.POP2);
        });
        assertSame(bytes, compat.transform("test.Plain", bytes));
        assertTrue(changes.isEmpty());
        assertNull(compat.transform("test.Missing", null));
    }

    @Test
    void staticCallsAreRedirected() {
        byte[] bytes = generate("test.Fml", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V", false);
        });
        MethodInsnNode call = onlyCall(compat.transform("test.Fml", bytes));
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("shim/SystemShims", call.owner);
        assertEquals("setSecurityManager", call.name);
        assertEquals("(Ljava/lang/SecurityManager;)V", call.desc);
        assertEquals(List.of("test.Fml: redirected java/lang/System.setSecurityManager to shim/SystemShims"), changes);
    }

    @Test
    void instanceCallsBecomeStaticCallsThatTakeTheReceiver() {
        byte[] bytes = generate("test.Stopper", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "stop", "()V", false);
        });
        ClassNode node = read(compat.transform("test.Stopper", bytes));
        MethodInsnNode stop = calls(node).get(1);
        assertEquals(Opcodes.INVOKESTATIC, stop.getOpcode());
        assertEquals("shim/ThreadShims", stop.owner);
        assertEquals("(Ljava/lang/Thread;)V", stop.desc);
    }

    @Test
    void removedTypesAreRenamedEverywhere() {
        byte[] bytes = generate("test.Patcher", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/jar/Pack200", "newUnpacker", "()Ljava/util/jar/Pack200$Unpacker;", false);
            mv.visitInsn(Opcodes.POP);
        });
        MethodInsnNode call = onlyCall(compat.transform("test.Patcher", bytes));
        assertEquals("shim/Pack200", call.owner);
        assertEquals("()Lshim/Pack200$Unpacker;", call.desc);
    }

    @Test
    void patchesApplyByClassName() {
        byte[] bytes = generate("test.Patched", mv -> {
        });
        assertEquals(Opcodes.ACC_FINAL, read(compat.transform("test.Patched", bytes)).access & Opcodes.ACC_FINAL);
        assertSame(bytes, compat.transform("test.Other", bytes));
    }

    @Test
    void byteSearch() {
        byte[] haystack = "abcabd".getBytes();
        assertEquals(3, CompatTransformer.indexOf(haystack, "abd".getBytes()));
        assertEquals(-1, CompatTransformer.indexOf(haystack, "abe".getBytes()));
        assertEquals(-1, CompatTransformer.indexOf("ab".getBytes(), "abc".getBytes()));
    }

    /** A Java 8 class with one static method {@code run()V} whose body {@code code} writes. */
    private static byte[] generate(String name, Consumer<MethodVisitor> code) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        code.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        node.methods.getFirst().instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call);
            }
        });
        return calls;
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        List<MethodInsnNode> calls = calls(read(bytes));
        assertEquals(1, calls.size());
        return calls.getFirst();
    }
}
