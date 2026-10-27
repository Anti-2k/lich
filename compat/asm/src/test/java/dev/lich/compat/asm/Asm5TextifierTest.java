package dev.lich.compat.asm;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lines ASM 5.0.3's Textifier printed, taken from its dump of Thermal Dynamics' item duct renderer. */
class Asm5TextifierTest {
    private static byte[] renderer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "test/Renderer", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render", "(Ljava/util/Iterator;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn(0.5);
        mv.visitInsn(Opcodes.POP2);
        mv.visitLdcInsn(0.6f);
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn(7L);
        mv.visitInsn(Opcodes.POP2);
        mv.visitLdcInsn("text");
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn(Type.getType("Ljava/lang/String;"));
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String print(Textifier textifier) {
        StringWriter out = new StringWriter();
        new ClassReader(renderer()).accept(new TraceClassVisitor(null, textifier, new PrintWriter(out)), ClassReader.SKIP_DEBUG);
        return out.toString();
    }

    @Test
    void printsInterfaceCallsAndNumbersAsAsm5Did() {
        String text = print(new Asm5Textifier());
        assertTrue(text.contains("    INVOKEINTERFACE java/util/Iterator.hasNext ()Z\n"), text);
        assertTrue(text.contains("    LDC 0.5\n"), text);
        assertTrue(text.contains("    LDC 0.6\n"), text);
        assertTrue(text.contains("    LDC 7\n"), text);
        assertTrue(text.contains("    LDC \"text\"\n"), text);
        assertTrue(text.contains("    LDC Ljava/lang/String;.class\n"), text);
    }

    /** What changed, so the shim is still needed: ASM 9's own output for the same method. */
    @Test
    void asm9PrintsThemDifferently() {
        String text = print(new Textifier());
        assertTrue(text.contains("Iterator.hasNext ()Z (itf)\n"), text);
        assertTrue(text.contains("    LDC 0.5D\n"), text);
    }

    /** A mod's subclass, rebased by the compat pass, calls the protected constructor with ASM 5's API level, raised to 9. */
    @Test
    void subclassesPrintTheirMethodsTheSameWay() {
        class ModTextifier extends Asm5Textifier {
            ModTextifier() {
                super(Opcodes.ASM9);
            }
        }
        assertEquals(print(new Asm5Textifier()), print(new ModTextifier()));
    }
}
