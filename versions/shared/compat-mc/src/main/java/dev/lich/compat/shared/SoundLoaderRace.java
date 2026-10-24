package dev.lich.compat.shared;

import dev.lich.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Fixes a race in the {@code SoundManager} of 1.7.10 and 1.8.9 (same code, same SRG names) that leaves
 * the game without sound, and then crashes it once music starts ({@code UnsatisfiedLinkError} in {@code AL10.nalGetSourcei}).
 *
 * <p>{@code reloadSoundSystem()} unloads the sound system only if it's flagged as loaded, then loads it.
 * Loading happens on a "Sound Library Loader" thread, which sets the flag once OpenAL is up. A reload
 * that comes while that thread is still working skips the unload and starts a second sound system, whose
 * OpenAL context fails ("Only one OpenAL context may be instantiated"). FML reloads resources right after
 * the first load. With Forge 1.7.10 on Java 25 that reload reliably wins the race; on Java 8, and with
 * 1.8.9 so far, it narrowly loses.
 *
 * <p>The patch remembers the loader thread, and a reload first waits for it to finish.
 */
public final class SoundLoaderRace implements ClassPatch {
    public static final String CLASS = "net.minecraft.client.audio.SoundManager";
    static final String RELOAD = "func_148596_a";
    static final String LOAD = "func_148608_i";
    static final String LOADER_FIELD = "lich$soundLoader";

    @Override
    public boolean apply(ClassNode node) {
        MethodNode reload = method(node, RELOAD);
        MethodNode load = method(node, LOAD);
        MethodInsnNode start = load == null ? null : threadStart(load);
        if (reload == null || start == null) {
            return false;
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
                LOADER_FIELD, "Ljava/lang/Thread;", null, null));

        // thread.start() → this.lich$soundLoader = thread; thread.start()
        InsnList remember = new InsnList();
        remember.add(new InsnNode(Opcodes.DUP));
        remember.add(new VarInsnNode(Opcodes.ALOAD, 0));
        remember.add(new InsnNode(Opcodes.SWAP));
        remember.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, LOADER_FIELD, "Ljava/lang/Thread;"));
        load.instructions.insertBefore(start, remember);

        // First thing in reload: SoundLoaders.await(this.lich$soundLoader)
        InsnList await = new InsnList();
        await.add(new VarInsnNode(Opcodes.ALOAD, 0));
        await.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, LOADER_FIELD, "Ljava/lang/Thread;"));
        await.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/lich/compat/shared/SoundLoaders", "await",
                "(Ljava/lang/Thread;)V", false));
        reload.instructions.insert(await);
        return true;
    }

    private static MethodNode method(ClassNode node, String name) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals("()V")) {
                return method;
            }
        }
        return null;
    }

    private static MethodInsnNode threadStart(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("java/lang/Thread") && call.name.equals("start") && call.desc.equals("()V")) {
                return call;
            }
        }
        return null;
    }
}
