package dev.lich.compat.lwjgl2;

import dev.lich.transform.CompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Lwjgl2CompatTest {
    private final CompatTransformer compat = Lwjgl2Compat.register(CompatTransformer.builder()).build();

    /** Every generated rename points at a static method LWJGL 3 really has. */
    @Test
    void everyRenameTargetExistsInLwjgl3() throws IOException {
        List<String> missing = new ArrayList<>();
        int renames = 0;
        try (InputStream in = Lwjgl2Compat.class.getResourceAsStream("/dev/lich/compat/lwjgl2/renames.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.strip().split(" ");
                renames++;
                if (!hasStaticMethod(f[3], f[4], f[5])) {
                    missing.add(line);
                }
            }
        }
        assertTrue(renames > 700, "renames: " + renames);
        assertEquals(List.of(), missing);
    }

    private static boolean hasStaticMethod(String owner, String name, String descriptor) throws IOException {
        for (String type = owner; type != null; ) {
            ClassNode node = read(type);
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(descriptor) && (method.access & Opcodes.ACC_STATIC) != 0) {
                    return true;
                }
            }
            type = node.superName;
        }
        return false;
    }

    private static ClassNode read(String internalName) throws IOException {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
            if (in == null) {
                throw new IOException("No class " + internalName);
            }
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            return node;
        }
    }

    @Test
    void lwjgl2CallsGoToLwjgl3AndTheMirrors() {
        byte[] bytes = generate(mv -> {
            // GL11.glGetFloat(GL_MODELVIEW_MATRIX, buffer): renamed.
            mv.visitLdcInsn(0x0BA6);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glGetFloat", "(ILjava/nio/FloatBuffer;)V", false);
            // GL11.glVertexPointer(3, 0, buffer): mirrored.
            mv.visitInsn(Opcodes.ICONST_3);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glVertexPointer", "(IILjava/nio/FloatBuffer;)V", false);
            // AL.create(): mirrored although LWJGL 3 has an AL class.
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/openal/AL", "create", "()V", false);
            // EFX10 became EXTEfx.
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/openal/EFX10", "alGenEffects", "()I", false);
            mv.visitInsn(Opcodes.POP);
            // new PointerBuffer(bytes).flip(): a constructor and an instance method.
            mv.visitTypeInsn(Opcodes.NEW, "org/lwjgl/PointerBuffer");
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/lwjgl/PointerBuffer", "<init>", "(Ljava/nio/ByteBuffer;)V", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/PointerBuffer", "flip", "()Lorg/lwjgl/PointerBuffer;", false);
            mv.visitInsn(Opcodes.POP);
            // Members LWJGL 3 has as they were stay as they are.
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glGetError", "()I", false);
            mv.visitInsn(Opcodes.POP);
        });
        assertEquals(List.of(
                "org/lwjgl/opengl/GL11.glGetFloatv(ILjava/nio/FloatBuffer;)V",
                "dev/lich/compat/lwjgl2/opengl/Lwjgl2GL11.glVertexPointer(IILjava/nio/FloatBuffer;)V",
                "dev/lich/compat/lwjgl2/openal/Lwjgl2AL.create()V",
                "org/lwjgl/openal/EXTEfx.alGenEffects()I",
                "dev/lich/compat/lwjgl2/Lwjgl2PointerBuffer.create(Ljava/nio/ByteBuffer;)Lorg/lwjgl/PointerBuffer;",
                "dev/lich/compat/lwjgl2/Lwjgl2PointerBuffer.flip(Lorg/lwjgl/PointerBuffer;)Lorg/lwjgl/PointerBuffer;",
                "org/lwjgl/opengl/GL11.glGetError()I"), calls(compat.transform("test.Renderer", bytes)));
    }

    @Test
    void classesWithoutLwjglAreLeftAlone() {
        byte[] bytes = generate(mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
            mv.visitInsn(Opcodes.POP2);
        });
        assertTrue(bytes == compat.transform("test.Renderer", bytes));
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.getFirst().instructions) {
            if (insn instanceof MethodInsnNode call) {
                assertTrue(call.getOpcode() == Opcodes.INVOKESTATIC, call.owner + "." + call.name + " should be static");
                calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static byte[] generate(Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/Renderer", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
