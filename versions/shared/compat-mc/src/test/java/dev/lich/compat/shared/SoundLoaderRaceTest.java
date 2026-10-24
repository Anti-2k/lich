package dev.lich.compat.shared;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoundLoaderRaceTest {
    /** The loader thread's work: slow, like starting OpenAL. */
    public static final class SlowLoader implements Runnable {
        public static final AtomicBoolean DONE = new AtomicBoolean();

        @Override
        public void run() {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            DONE.set(true);
        }
    }

    @Test
    void reloadWaitsForTheLoaderThread() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(soundManager()).accept(node, 0);
        assertTrue(new SoundLoaderRace().apply(node));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] patched = writer.toByteArray();

        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass(SoundLoaderRace.CLASS, patched, 0, patched.length);
            }
        }.define();
        Object soundManager = type.getConstructor().newInstance();
        type.getMethod(SoundLoaderRace.LOAD).invoke(soundManager);
        assertFalse(SlowLoader.DONE.get(), "the loader is still running");
        type.getMethod(SoundLoaderRace.RELOAD).invoke(soundManager);
        assertTrue(SlowLoader.DONE.get(), "reload returned before the loader finished");
    }

    @Test
    void leavesOtherClassesAlone() {
        ClassNode node = new ClassNode();
        node.name = "net/minecraft/client/audio/SoundManager";
        assertFalse(new SoundLoaderRace().apply(node));
    }

    /** The shape of 1.7.10's SoundManager: load() starts a loader thread, reload() does nothing else. */
    private static byte[] soundManager() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String name = SoundLoaderRace.CLASS.replace('.', '/');
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor load = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED, SoundLoaderRace.LOAD, "()V", null, null);
        load.visitCode();
        load.visitTypeInsn(Opcodes.NEW, "java/lang/Thread");
        load.visitInsn(Opcodes.DUP);
        String loader = SlowLoader.class.getName().replace('.', '/');
        load.visitTypeInsn(Opcodes.NEW, loader);
        load.visitInsn(Opcodes.DUP);
        load.visitMethodInsn(Opcodes.INVOKESPECIAL, loader, "<init>", "()V", false);
        load.visitLdcInsn("Sound Library Loader");
        load.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "(Ljava/lang/Runnable;Ljava/lang/String;)V", false);
        load.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "start", "()V", false);
        load.visitInsn(Opcodes.RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();

        MethodVisitor reload = writer.visitMethod(Opcodes.ACC_PUBLIC, SoundLoaderRace.RELOAD, "()V", null, null);
        reload.visitCode();
        reload.visitInsn(Opcodes.RETURN);
        reload.visitMaxs(0, 0);
        reload.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
