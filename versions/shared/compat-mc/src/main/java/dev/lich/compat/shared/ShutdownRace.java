package dev.lich.compat.shared;

import dev.lich.transform.ClassPatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Fixes a race in 1.8.9's and 1.12.2's {@code IntegratedServer.initiateShutdown()} that freezes the client on "Save and Quit to
 * Title". The client queues a task for the server thread (log out every player) and waits for it with
 * {@code Futures.getUnchecked}. Meanwhile the server may already have seen the client's disconnect packet and shut
 * itself down: a task queued after its tick loop ended never runs, so the client waits forever (found 2026-10-31 by
 * the modpack harness: CubeSoft and SolitaryCraft froze there under Lich, SolitaryCraft on Java 8 too).
 *
 * <p>The patch makes the wait end once the server reports itself stopped. By then stopping has saved and removed
 * every player, which is all the task would have done. While the server runs, the wait is the game's own.
 */
public final class ShutdownRace implements ClassPatch {
    public static final String CLASS = "net.minecraft.server.integrated.IntegratedServer";
    static final String INITIATE_SHUTDOWN = "func_71263_m";
    private static final String FUTURES = "com/google/common/util/concurrent/Futures";
    private static final String FUTURE = "Ljava/util/concurrent/Future;";

    @Override
    public boolean apply(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(INITIATE_SHUTDOWN) || !method.desc.equals("()V")) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(FUTURES) && call.name.equals("getUnchecked")
                        && call.desc.equals("(" + FUTURE + ")Ljava/lang/Object;")) {
                    // Futures.getUnchecked(task) → Futures.getUnchecked(ServerShutdowns.untilStopped(task, this))
                    InsnList wait = new InsnList();
                    wait.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    wait.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/lich/compat/shared/ServerShutdowns",
                            "untilStopped", "(" + FUTURE + "Ljava/lang/Object;)" + FUTURE, false));
                    method.instructions.insertBefore(call, wait);
                    return true;
                }
            }
        }
        return false;
    }
}
