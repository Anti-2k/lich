package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLSync;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * LWJGL 2's sync functions, which wrapped sync objects in {@link GLSync} where LWJGL 3 passes the pointer,
 * and its indexed {@code glGetInteger64}. Also stands in for {@code ARBSync}, which had the same functions.
 */
public final class Lwjgl2GL32 {
    private Lwjgl2GL32() {
    }

    private static long pointer(GLSync sync) {
        return sync == null ? 0L : sync.getPointer();
    }

    public static GLSync glFenceSync(int condition, int flags) {
        return new GLSync(GL32.glFenceSync(condition, flags));
    }

    public static boolean glIsSync(GLSync sync) {
        return GL32.glIsSync(pointer(sync));
    }

    public static void glDeleteSync(GLSync sync) {
        GL32.glDeleteSync(pointer(sync));
    }

    public static int glClientWaitSync(GLSync sync, int flags, long timeout) {
        return GL32.glClientWaitSync(pointer(sync), flags, timeout);
    }

    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        GL32.glWaitSync(pointer(sync), flags, timeout);
    }

    public static int glGetSync(GLSync sync, int pname) {
        return GL32.glGetSynci(pointer(sync), pname, null);
    }

    public static int glGetSynci(GLSync sync, int pname) {
        return GL32.glGetSynci(pointer(sync), pname, null);
    }

    public static void glGetSync(GLSync sync, int pname, IntBuffer length, IntBuffer values) {
        GL32.glGetSynciv(pointer(sync), pname, length, values);
    }

    public static long glGetInteger64(int value, int index) {
        return GL32.glGetInteger64i(value, index);
    }

    public static void glGetInteger64(int value, int index, LongBuffer data) {
        GL32.glGetInteger64i_v(value, index, data);
    }
}
