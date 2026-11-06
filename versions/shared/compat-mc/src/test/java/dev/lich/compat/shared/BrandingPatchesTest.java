package dev.lich.compat.shared;

import dev.lich.transform.ClassPatch;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Method;
import java.util.ArrayList;
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

    /** Stands in for guava's ImmutableList (the patch finds the builder by its name). */
    public static final class ImmutableList {
        private ImmutableList() {
        }

        public static final class Builder {
            private final List<Object> items = new ArrayList<>();

            public Builder add(Object item) {
                items.add(item);
                return this;
            }

            public Builder addAll(Iterable<?> more) {
                more.forEach(items::add);
                return this;
            }

            public List<Object> build() {
                return items;
            }
        }
    }

    /** Stands in for the main menu's draw target: records each string and its distance from the bottom. */
    public static final class Screen {
        public static final List<String> DRAWN = new ArrayList<>();
        public int height = 100;
        public net.minecraft.client.gui.FontRenderer font;

        public void drawString(net.minecraft.client.gui.FontRenderer font, String text, int x, int y, int color) {
            DRAWN.add((height - y) + ":" + text);
        }
    }

    @Test
    void lichFollowsFmlsExtraBrandingAndCountsItsMods() throws Exception {
        String sided = SidedHandler.class.getName().replace('.', '/');
        String builder = ImmutableList.Builder.class.getName().replace('.', '/');
        // static List computeBranding(SidedHandler h) { return new Builder().addAll(h.getAdditional...()).build(); }
        // (FML's own mod count is added after that call, so it ends up after Lich's lines.)
        Method method = patched("test/FmlHandler", new BrandingPatches.FmlBranding(), "computeBranding",
                "(L" + sided + ";)Ljava/util/List;", code -> {
                    code.visitTypeInsn(Opcodes.NEW, builder);
                    code.visitInsn(Opcodes.DUP);
                    code.visitMethodInsn(Opcodes.INVOKESPECIAL, builder, "<init>", "()V", false);
                    code.visitVarInsn(Opcodes.ALOAD, 0);
                    code.visitMethodInsn(Opcodes.INVOKEINTERFACE, sided, "getAdditionalBrandingInformation", "()Ljava/util/List;", true);
                    code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "addAll", "(Ljava/lang/Iterable;)L" + builder + ";", false);
                    code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "build", "()Ljava/util/List;", false);
                    code.visitInsn(Opcodes.ARETURN);
                });
        SidedHandler handler = () -> List.of("Powered by Forge 11.15.1.2318", "OptiFine");
        assertEquals(List.of("OptiFine", Branding.line("11.15.1.2318"), Branding.modCountLine()),
                method.invoke(null, handler));
        assertTrue(Branding.line("11.15.1.2318").matches("Lich \\S+ with Forge 11\\.15\\.1\\.2318 on Java \\d+"),
                Branding.line("11.15.1.2318"));
        assertEquals("0 mods loaded, 0 mods active", Branding.modCountLine());
    }

    @Test
    void vanillaMenuDrawsLichLinesAboveTheVersion() throws Exception {
        String screen = Screen.class.getName().replace('.', '/');
        // void draw(Screen s) { String v = "Minecraft 1.8.9"; s.drawString(s, v, 2, s.height - 10, -1); }
        Method method = patched("test/MainMenu", new BrandingPatches.MainMenu("Minecraft 1.8.9"), "draw",
                "(L" + screen + ";)V", code -> {
                    code.visitLdcInsn("Minecraft 1.8.9");
                    code.visitVarInsn(Opcodes.ASTORE, 1);
                    code.visitVarInsn(Opcodes.ALOAD, 0);
                    code.visitVarInsn(Opcodes.ALOAD, 0);
                    code.visitFieldInsn(Opcodes.GETFIELD, screen, "font", "Lnet/minecraft/client/gui/FontRenderer;");
                    code.visitVarInsn(Opcodes.ALOAD, 1);
                    code.visitInsn(Opcodes.ICONST_2);
                    code.visitVarInsn(Opcodes.ALOAD, 0);
                    code.visitFieldInsn(Opcodes.GETFIELD, screen, "height", "I");
                    code.visitIntInsn(Opcodes.BIPUSH, 10);
                    code.visitInsn(Opcodes.ISUB);
                    code.visitInsn(Opcodes.ICONST_M1);
                    code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, screen, "drawString",
                            "(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V", false);
                    code.visitInsn(Opcodes.RETURN);
                });
        Screen.DRAWN.clear();
        method.invoke(null, new Screen());
        assertEquals(List.of("30:Minecraft 1.8.9", "20:" + Branding.line(), "10:" + Branding.modCountLine()),
                Screen.DRAWN);
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
