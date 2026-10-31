package dev.lich.compat.v1_8_9;

import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Called from the patched {@code IntegratedServer}; see {@link ShutdownRace}. */
public final class ServerShutdowns {
    private static final long POLL_MILLIS = 50;
    /** {@code MinecraftServer.isServerStopped()}, true once the server thread has finished stopping. */
    private static final String IS_SERVER_STOPPED = "func_71241_aa";

    private ServerShutdowns() {
    }

    /**
     * Waits until {@code task} is done, and returns it, or until {@code server} has stopped without running it, and
     * returns a completed future instead. Like {@code Futures.getUnchecked}, which then reads the result, it waits
     * through interrupts and restores the flag afterwards.
     */
    public static Future<?> untilStopped(Future<?> task, Object server) {
        boolean interrupted = false;
        try {
            while (!task.isDone()) {
                if (stopped(server)) {
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    task.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException | ExecutionException | CancellationException e) {
                    // Done or not, the loop checks; getUnchecked reports a failure.
                }
            }
            return task;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** False if the server can't say: then the wait is the game's own. */
    private static boolean stopped(Object server) {
        try {
            Method isServerStopped = server.getClass().getMethod(IS_SERVER_STOPPED);
            return (Boolean) isServerStopped.invoke(server);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
