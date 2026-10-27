package dev.lich.compat.jdk;

import dev.lich.compat.jdk.activity.InvalidActivityException;
import dev.lich.transform.CompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.InvocationTargetException;
import java.rmi.RemoteException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Code written against JDK types Java 11 removed runs on their look-alikes once the compat pass renamed them. */
class RemovedTypesTest {
    private final CompatTransformer compat = JdkCompat.register(CompatTransformer.builder()).build();

    /** As LiteLoader's {@code getMod} does before its startup has finished. */
    @Test
    void liteLoaderThrowsItsInvalidActivityException() throws Exception {
        String exception = "javax/activity/InvalidActivityException";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "test/LiteLoader", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getMod", "()Ljava/lang/Object;", null,
                new String[]{exception});
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, exception);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("Attempted to get a reference to a mod before loader startup is complete");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, exception, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        byte[] renamed = compat.transform("test.LiteLoader", writer.toByteArray());
        Class<?> liteLoader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("test.LiteLoader", renamed, 0, renamed.length);
            }
        }.define();

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> liteLoader.getMethod("getMod").invoke(null));
        InvalidActivityException cause = assertInstanceOf(InvalidActivityException.class, thrown.getCause());
        assertInstanceOf(RemoteException.class, cause);
        assertEquals("Attempted to get a reference to a mod before loader startup is complete", cause.getMessage());
        assertEquals(InvalidActivityException.class, liteLoader.getMethod("getMod").getExceptionTypes()[0]);
    }
}
