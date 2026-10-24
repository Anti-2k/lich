package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/** LWJGL 2's {@code GL30.glGetBoolean(int, int, ByteBuffer)}, LWJGL 3's {@code glGetBooleani_v}. */
public final class Lwjgl2GL30 {
    private Lwjgl2GL30() {
    }

    public static void glGetBoolean(int value, int index, ByteBuffer data) {
        GL30.glGetBooleani_v(value, index, data);
    }
}
