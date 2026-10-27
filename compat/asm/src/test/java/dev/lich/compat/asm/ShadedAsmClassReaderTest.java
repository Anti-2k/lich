package dev.lich.compat.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The version check of a relocated ClassReader, as ASM writes it: {@code readShort(offset + 6) > V<n>}. */
class ShadedAsmClassReaderTest {
    private static ClassNode reader(int newest) {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/spongepowered/asm/lib/ClassReader", null, "java/lang/Object", null);
        MethodVisitor mv = node.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "([BIZ)V", null, null);
        mv.visitCode();
        Label supported = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitIntInsn(Opcodes.BIPUSH, 6);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/spongepowered/asm/lib/ClassReader", "readShort", "(I)S", false);
        mv.visitIntInsn(Opcodes.BIPUSH, newest);
        mv.visitJumpInsn(Opcodes.IF_ICMPLE, supported);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(supported);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return node;
    }

    private static int limit(ClassNode node) {
        for (var insn : node.methods.getFirst().instructions) {
            if (insn instanceof IntInsnNode push && push.operand != 6) {
                return push.operand;
            }
        }
        throw new AssertionError("no limit");
    }

    @Test
    void anAsm9StoppingAtJava21NowReadsJava25() {
        ClassNode asm95 = reader(Opcodes.V21);
        assertTrue(new ShadedAsmClassReader().apply(asm95));
        assertEquals(Opcodes.V25, limit(asm95));
    }

    /** Mixin reads these constants by reflection to decide which class files its ASM reads. */
    @Test
    void anAsm9sOpcodesGetTheMissingVersions() {
        ClassNode opcodes = new ClassNode();
        opcodes.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "org/spongepowered/asm/lib/Opcodes", null, "java/lang/Object", null);
        for (int version = 17; version <= 22; version++) {
            opcodes.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "V" + version, "I", null, 44 + version);
        }
        opcodes.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "ACC_PUBLIC", "I", null, 1);
        assertTrue(new ShadedAsmOpcodes().apply(opcodes));
        // In order, before ACC_PUBLIC, where Mixin stops reading.
        assertEquals(List.of("V17", "V18", "V19", "V20", "V21", "V22", "V23", "V24", "V25", "ACC_PUBLIC"),
                opcodes.fields.stream().map(f -> f.name).toList());
        assertEquals(Opcodes.V25, opcodes.fields.get(8).value);
        assertFalse(new ShadedAsmOpcodes().apply(opcodes), "nothing left to add");

        ClassNode asm52 = new ClassNode();
        asm52.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE, "org/spongepowered/asm/lib/Opcodes", null, "java/lang/Object", null);
        asm52.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "V1_8", "I", null, 52);
        assertFalse(new ShadedAsmOpcodes().apply(asm52));
    }

    @Test
    void mixin07sAsm5AndCurrentAsmAreLeftAlone() {
        ClassNode asm52 = reader(Opcodes.V1_8);
        assertFalse(new ShadedAsmClassReader().apply(asm52));
        assertEquals(Opcodes.V1_8, limit(asm52));

        ClassNode asm98 = reader(Opcodes.V25);
        assertFalse(new ShadedAsmClassReader().apply(asm98));
    }
}
