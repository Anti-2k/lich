package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryUtil.memAddress;

/** LWJGL 2's {@code GL14} pointer overloads that LWJGL 3 dropped; see {@link Lwjgl2GL11}. */
public final class Lwjgl2GL14 {
    private static volatile Buffer fogCoordArray;
    private static volatile Buffer secondaryColorArray;

    private Lwjgl2GL14() {
    }

    public static void glFogCoordPointer(int stride, FloatBuffer data) {
        fogCoordArray = data;
        GL14.nglFogCoordPointer(GL11.GL_FLOAT, stride, memAddress(data));
    }

    public static void glFogCoordPointer(int stride, DoubleBuffer data) {
        fogCoordArray = data;
        GL14.nglFogCoordPointer(GL11.GL_DOUBLE, stride, memAddress(data));
    }

    public static void glSecondaryColorPointer(int size, int stride, FloatBuffer data) {
        secondaryColorArray = data;
        GL14.nglSecondaryColorPointer(size, GL11.GL_FLOAT, stride, memAddress(data));
    }

    public static void glSecondaryColorPointer(int size, int stride, DoubleBuffer data) {
        secondaryColorArray = data;
        GL14.nglSecondaryColorPointer(size, GL11.GL_DOUBLE, stride, memAddress(data));
    }

    public static void glSecondaryColorPointer(int size, boolean unsigned, int stride, ByteBuffer data) {
        secondaryColorArray = data;
        GL14.nglSecondaryColorPointer(size, unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE, stride, memAddress(data));
    }
}
