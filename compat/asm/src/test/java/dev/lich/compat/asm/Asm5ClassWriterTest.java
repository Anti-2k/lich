package dev.lich.compat.asm;

import dev.lich.transform.CompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A coremod that adds a method with a branch to a class before {@code reader.accept(writer)} (EnderCore's elytra
 * patch) must keep its stack map frame in a Java 8 class, as it did under ASM 5.
 */
class Asm5ClassWriterTest {
    private static final String PACKAGE = Asm5ClassWriterTest.class.getPackageName().replace('.', '/') + "/";

    /** The coremod, compiled normally; the compat pass swaps its {@code new ClassWriter(reader, 0)}. */
    public static final class Coremod {
        public static byte[] addPick(byte[] original) {
            ClassReader reader = new ClassReader(original);
            ClassWriter writer = new ClassWriter(reader, 0);
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(Z)I", null, null);
            mv.visitCode();
            Label otherwise = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitJumpInsn(Opcodes.IFEQ, otherwise);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(otherwise);
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            reader.accept(writer, 0);
            return writer.toByteArray();
        }
    }

    /** Loads the coremod from the compat pass's output, in place of the compiled one. */
    private static final class CoremodLoader extends ClassLoader {
        private final String name;
        private final byte[] bytes;

        CoremodLoader(String name, byte[] bytes) {
            super(Asm5ClassWriterTest.class.getClassLoader());
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
            if (requested.equals(name)) {
                Class<?> loaded = findLoadedClass(requested);
                return loaded != null ? loaded : defineClass(requested, bytes, 0, bytes.length);
            }
            return super.loadClass(requested, resolve);
        }
    }

    private static byte[] emptyClass(int version, String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(version, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] coremodBytes() throws IOException {
        try (InputStream in = Asm5ClassWriterTest.class.getResourceAsStream("Asm5ClassWriterTest$Coremod.class")) {
            return in.readAllBytes();
        }
    }

    private static byte[] runCoremod(byte[] original) throws Exception {
        String name = Coremod.class.getName();
        CompatTransformer compat = AsmCompat.register(CompatTransformer.builder()).build();
        byte[] transformed = compat.transform(name, coremodBytes());
        Class<?> coremod = new CoremodLoader(name, transformed).loadClass(name);
        return (byte[]) coremod.getMethod("addPick", byte[].class).invoke(null, (Object) original);
    }

    private static boolean hasStackMapTable(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains("StackMapTable");
    }

    @Test
    void aFrameAddedBeforeTheClassHeaderStaysInAJava8Class() throws Exception {
        byte[] patched = runCoremod(emptyClass(Opcodes.V1_8, PACKAGE + "Patched8"));
        assertTrue(hasStackMapTable(patched));
        // Java 25 verifies the method: without its frame this is a VerifyError.
        Class<?> defined = MethodHandles.lookup().defineClass(patched);
        assertEquals(1, defined.getMethod("pick", boolean.class).invoke(null, true));
        assertEquals(2, defined.getMethod("pick", boolean.class).invoke(null, false));
    }

    @Test
    void aFrameAddedBeforeTheClassHeaderIsStillDroppedInAJava5Class() throws Exception {
        byte[] patched = runCoremod(emptyClass(Opcodes.V1_5, PACKAGE + "Patched5"));
        assertFalse(hasStackMapTable(patched));
        Class<?> defined = MethodHandles.lookup().defineClass(patched);
        assertEquals(1, defined.getMethod("pick", boolean.class).invoke(null, true));
        assertEquals(2, defined.getMethod("pick", boolean.class).invoke(null, false));
    }
}
