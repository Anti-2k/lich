package dev.lich.compat.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A coremod's ASM 4-level visitor (AdvSolarPatch) reading a Java 8 class with a lambda and a method handle in it. */
class Asm5MethodVisitorTest {
    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC, "test/Bootstrap", "make", "()V", false);

    private static byte[] classWithLambda() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/WithLambda", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "()V", null, null);
        mv.visitCode();
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;", BOOTSTRAP, Type.getType("()V"));
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn(BOOTSTRAP);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private interface Visitors {
        MethodVisitor make(MethodVisitor next);
    }

    private static byte[] readThroughAsm4(Visitors visitors) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classWithLambda()).accept(new ClassVisitor(Opcodes.ASM4, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return visitors.make(super.visitMethod(access, name, descriptor, signature, exceptions));
            }
        }, 0);
        return writer.toByteArray();
    }

    @Test
    void plainAsm9ThrowsHereWhichIsWhyTheStandInExists() {
        assertThrows(UnsupportedOperationException.class, () -> readThroughAsm4(next -> new MethodVisitor(Opcodes.ASM4, next) {
        }));
    }

    @Test
    void lambdasAndHandlesPassThroughAnAsm4LevelVisitor() {
        List<String> seen = new ArrayList<>();
        byte[] written = readThroughAsm4(next -> new Asm5MethodVisitor(Opcodes.ASM4, next) {
            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle handle, Object... arguments) {
                seen.add(name);
                super.visitInvokeDynamicInsn(name, descriptor, handle, arguments);
            }

            @Override
            public void visitLdcInsn(Object value) {
                seen.add(value.getClass().getSimpleName());
                super.visitLdcInsn(value);
            }
        });
        assertEquals(List.of("get", "Handle"), seen);

        // What came through is still there in the class written.
        List<String> kept = new ArrayList<>();
        new ClassReader(written).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(String indy, String indyDescriptor, Handle handle, Object... arguments) {
                        kept.add(indy);
                    }
                };
            }
        }, 0);
        assertEquals(List.of("get"), kept);
    }
}
