package dev.lich.compat.v1_8_9;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownRaceTest {
    /** Stands in for {@code MinecraftServer}: stopped once its thread has finished. */
    public static final class Server {
        volatile boolean stopped;

        public boolean func_71241_aa() {
            return stopped;
        }
    }

    @Test
    void waitEndsOnceTheServerStoppedWithoutRunningTheTask() throws Exception {
        Server server = new Server();
        Future<?> neverRun = new FutureTask<>(() -> null);
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.stopped = true;
        });
        Future<?> result = ServerShutdowns.untilStopped(neverRun, server);
        assertNotSame(neverRun, result);
        assertTrue(result.isDone());
        assertNull(result.get());
    }

    @Test
    void aTaskTheServerRanIsReturnedAsItIs() {
        CompletableFuture<String> ran = CompletableFuture.completedFuture("logged out");
        assertSame(ran, ServerShutdowns.untilStopped(ran, new Server()));
    }

    @Test
    void wrapsTheFutureInInitiateShutdown() {
        ClassNode node = new ClassNode();
        node.name = ShutdownRace.CLASS.replace('.', '/');
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, ShutdownRace.INITIATE_SHUTDOWN, "()V", null, null);
        MethodInsnNode getUnchecked = new MethodInsnNode(Opcodes.INVOKESTATIC, "com/google/common/util/concurrent/Futures",
                "getUnchecked", "(Ljava/util/concurrent/Future;)Ljava/lang/Object;", false);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(getUnchecked);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);

        assertTrue(new ShutdownRace().apply(node));
        MethodInsnNode untilStopped = assertInstanceOf(MethodInsnNode.class, getUnchecked.getPrevious());
        assertEquals("dev/lich/compat/v1_8_9/ServerShutdowns", untilStopped.owner);
        assertEquals("untilStopped", untilStopped.name);
        assertEquals(0, assertInstanceOf(VarInsnNode.class, untilStopped.getPrevious()).var);
    }

    @Test
    void leavesOtherClassesAlone() {
        ClassNode node = new ClassNode();
        node.name = "net/minecraft/server/integrated/IntegratedServer";
        assertFalse(new ShutdownRace().apply(node));
    }
}
