package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * LWJGL 2's {@code GL11} overloads that LWJGL 3 dropped: the client array pointers, where LWJGL 2 took the
 * element type from the buffer's class (or a boolean for signed and unsigned bytes) and LWJGL 3 takes it as
 * an argument. Like LWJGL 2, they keep the last buffer of each array reachable, since OpenGL reads it at draw
 * time and the caller may hold no other reference to it.
 */
public final class Lwjgl2GL11 {
    private static volatile Buffer vertexArray;
    private static volatile Buffer colorArray;
    private static volatile Buffer normalArray;
    /** Per client texture unit in LWJGL 2; the last few are enough for the game's own one or two. */
    private static final Buffer[] TEXCOORD_ARRAYS = new Buffer[8];
    private static int nextTexcoord;

    private Lwjgl2GL11() {
    }

    private static synchronized void keepTexcoord(Buffer buffer) {
        TEXCOORD_ARRAYS[nextTexcoord] = buffer;
        nextTexcoord = (nextTexcoord + 1) % TEXCOORD_ARRAYS.length;
    }

    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
        colorArray = pointer;
        GL11.nglColorPointer(size, unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE, stride, memAddress(pointer));
    }

    public static void glColorPointer(int size, int stride, FloatBuffer pointer) {
        colorArray = pointer;
        GL11.nglColorPointer(size, GL11.GL_FLOAT, stride, memAddress(pointer));
    }

    public static void glColorPointer(int size, int stride, DoubleBuffer pointer) {
        colorArray = pointer;
        GL11.nglColorPointer(size, GL11.GL_DOUBLE, stride, memAddress(pointer));
    }

    public static void glVertexPointer(int size, int stride, FloatBuffer pointer) {
        vertexArray = pointer;
        GL11.nglVertexPointer(size, GL11.GL_FLOAT, stride, memAddress(pointer));
    }

    public static void glVertexPointer(int size, int stride, DoubleBuffer pointer) {
        vertexArray = pointer;
        GL11.nglVertexPointer(size, GL11.GL_DOUBLE, stride, memAddress(pointer));
    }

    public static void glVertexPointer(int size, int stride, IntBuffer pointer) {
        vertexArray = pointer;
        GL11.nglVertexPointer(size, GL11.GL_INT, stride, memAddress(pointer));
    }

    public static void glVertexPointer(int size, int stride, ShortBuffer pointer) {
        vertexArray = pointer;
        GL11.nglVertexPointer(size, GL11.GL_SHORT, stride, memAddress(pointer));
    }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) {
        keepTexcoord(pointer);
        GL11.nglTexCoordPointer(size, GL11.GL_FLOAT, stride, memAddress(pointer));
    }

    public static void glTexCoordPointer(int size, int stride, DoubleBuffer pointer) {
        keepTexcoord(pointer);
        GL11.nglTexCoordPointer(size, GL11.GL_DOUBLE, stride, memAddress(pointer));
    }

    public static void glTexCoordPointer(int size, int stride, IntBuffer pointer) {
        keepTexcoord(pointer);
        GL11.nglTexCoordPointer(size, GL11.GL_INT, stride, memAddress(pointer));
    }

    public static void glTexCoordPointer(int size, int stride, ShortBuffer pointer) {
        keepTexcoord(pointer);
        GL11.nglTexCoordPointer(size, GL11.GL_SHORT, stride, memAddress(pointer));
    }

    public static void glNormalPointer(int stride, ByteBuffer pointer) {
        normalArray = pointer;
        GL11.nglNormalPointer(GL11.GL_BYTE, stride, memAddress(pointer));
    }

    public static void glNormalPointer(int stride, FloatBuffer pointer) {
        normalArray = pointer;
        GL11.nglNormalPointer(GL11.GL_FLOAT, stride, memAddress(pointer));
    }

    public static void glNormalPointer(int stride, DoubleBuffer pointer) {
        normalArray = pointer;
        GL11.nglNormalPointer(GL11.GL_DOUBLE, stride, memAddress(pointer));
    }

    public static void glNormalPointer(int stride, IntBuffer pointer) {
        normalArray = pointer;
        GL11.nglNormalPointer(GL11.GL_INT, stride, memAddress(pointer));
    }

    public static void glDrawElements(int mode, int count, int type, ByteBuffer indices) {
        GL11.nglDrawElements(mode, count, type, memAddress(indices));
    }

    /**
     * Throws without a current context, like LWJGL 2, where LWJGL 3 aborts the JVM: the game's crash report
     * asks for the GL version whether or not a context exists, and catches the exception.
     */
    public static String glGetString(int name) {
        GL.getCapabilities();
        return GL11.glGetString(name);
    }

    public static ByteBuffer glGetPointer(int pname, long resultSize) {
        long pointer = GL11.glGetPointer(pname);
        return pointer == 0 ? null : org.lwjgl.system.MemoryUtil.memByteBuffer(pointer, (int) resultSize);
    }
}
