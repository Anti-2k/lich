package dev.lich.compat.shared;

import dev.lich.transform.ClassPatch;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandingPatchesTest {
    /** Stands in for FML's IFMLSidedHandler. */
    public interface SidedHandler {
        List<String> getAdditionalBrandingInformation();
    }

    @Test
    void lichFollowsFmlsExtraBranding() throws Exception {
        String sided = SidedHandler.class.getName().replace('.', '/');
        // static List computeBranding(SidedHandler h) { return h.getAdditionalBrandingInformation(); }
        Method method = patched("test/FmlHandler", new BrandingPatches.FmlBranding(), "computeBranding",
                "(L" + sided + ";)Ljava/util/List;", code -> {
                    code.visitVarInsn(Opcodes.ALOAD, 0);
                    code.visitMethodInsn(Opcodes.INVOKEINTERFACE, sided, "getAdditionalBrandingInformation", "()Ljava/util/List;", true);
                    code.visitInsn(Opcodes.ARETURN);
                });
        SidedHandler handler = () -> List.of("OptiFine");
        assertEquals(List.of("OptiFine", Branding.line()), method.invoke(null, handler));
    }

    @Test
    void vanillaMenuLineGetsLichAppended() throws Exception {
        Method method = patched("test/MainMenu", new BrandingPatches.MainMenu("Minecraft 1.8.9"), "versionLine",
                "()Ljava/lang/String;", code -> {
                    code.visitLdcInsn("Minecraft 1.8.9");
                    code.visitInsn(Opcodes.ARETURN);
                });
        assertEquals("Minecraft 1.8.9 / " + Branding.line(), method.invoke(null));
        assertTrue(Branding.line().matches("Lich \\S+ on Java \\d+"), Branding.line());
    }

    @Test
    void leavesOtherClassesAlone() {
        ClassNode node = new ClassNode();
        node.name = "test/Empty";
        assertFalse(new BrandingPatches.FmlBranding().apply(node));
        assertFalse(new BrandingPatches.MainMenu("Minecraft 1.8.9").apply(node));
    }

    /** A class with one static method made by {@code body}, patched, loaded, and that method. */
    private Method patched(String name, ClassPatch patch, String methodName, String descriptor, Consumer<MethodVisitor> body)
            throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor code = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodName, descriptor, null, null);
        code.visitCode();
        body.accept(code);
        code.visitMaxs(0, 0);
        code.visitEnd();
        writer.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(writer.toByteArray()).accept(node, 0);
        assertTrue(patch.apply(node));
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(out);
        byte[] bytes = out.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
            }
        }.define();
        for (Method method : type.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError(methodName);
    }
}
