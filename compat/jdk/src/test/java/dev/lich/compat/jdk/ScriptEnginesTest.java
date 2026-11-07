package dev.lich.compat.jdk;

import dev.lich.transform.CompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@code new ScriptEngineManager(null)} finds the Nashorn on the class path once the compat pass has seen the call. */
class ScriptEnginesTest {
    private final CompatTransformer compat = JdkCompat.register(CompatTransformer.builder()).build();

    /** As Solar Flux Reborn's {@code SolarScriptEngine.newEngine} does. */
    private static byte[] solarFlux() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/SolarScriptEngine", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "newEngine", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "javax/script/ScriptEngineManager");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "javax/script/ScriptEngineManager", "<init>", "(Ljava/lang/ClassLoader;)V", false);
        mv.visitLdcInsn("Nashorn");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "javax/script/ScriptEngineManager", "getEngineByName",
                "(Ljava/lang/String;)Ljavax/script/ScriptEngine;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private Object newEngine(byte[] bytes) throws Exception {
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("test.SolarScriptEngine", bytes, 0, bytes.length);
            }
        }.define();
        return type.getMethod("newEngine").invoke(null);
    }

    @Test
    void aNullLoaderFindsTheNashornOnTheClassPath() throws Exception {
        byte[] original = solarFlux();
        assertNull(newEngine(original), "Java 25 has no engine of its own");
        assertNotNull(newEngine(compat.transform("test.SolarScriptEngine", original)));
    }

    @Test
    void aLoaderGivenStaysTheLoader() {
        ClassLoader own = new ClassLoader() {
        };
        assertSame(own, ScriptEngines.withNashorn(own));
        assertSame(ClassLoader.getSystemClassLoader(), ScriptEngines.withNashorn(null));
    }
}
