package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.ARBVertexShader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/** LWJGL 2's {@code ARBVertexShader} overloads that LWJGL 3 dropped; see {@link Lwjgl2GL20}. */
public final class Lwjgl2ARBVertexShader {
    private Lwjgl2ARBVertexShader() {
    }

    public static String glGetActiveAttribARB(int programObj, int index, int maxLength) {
        return Shaders.activeName(ARBVertexShader::nglGetActiveAttribARB, programObj, index, maxLength, null);
    }

    public static String glGetActiveAttribARB(int programObj, int index, int maxLength, IntBuffer sizeType) {
        return Shaders.activeName(ARBVertexShader::nglGetActiveAttribARB, programObj, index, maxLength, sizeType);
    }

    public static int glGetActiveAttribSizeARB(int programObj, int index) {
        return Shaders.activeSize(ARBVertexShader::nglGetActiveAttribARB, programObj, index);
    }

    public static int glGetActiveAttribTypeARB(int programObj, int index) {
        return Shaders.activeType(ARBVertexShader::nglGetActiveAttribARB, programObj, index);
    }

    public static ByteBuffer glGetVertexAttribPointerARB(int index, int pname, long resultSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);
            ARBVertexShader.glGetVertexAttribPointervARB(index, pname, pointer);
            return pointer.get(0) == 0 ? null : MemoryUtil.memByteBuffer(pointer.get(0), (int) resultSize);
        }
    }

    public static void glVertexAttribPointerARB(int index, int size, boolean normalized, int stride, DoubleBuffer buffer) {
        Shaders.attribPointer(ARBVertexShader::nglVertexAttribPointerARB, index, size, GL11.GL_DOUBLE, normalized, stride, buffer);
    }

    public static void glVertexAttribPointerARB(int index, int size, boolean normalized, int stride, FloatBuffer buffer) {
        Shaders.attribPointer(ARBVertexShader::nglVertexAttribPointerARB, index, size, GL11.GL_FLOAT, normalized, stride, buffer);
    }

    public static void glVertexAttribPointerARB(int index, int size, boolean unsigned, boolean normalized, int stride, ByteBuffer buffer) {
        Shaders.attribPointer(ARBVertexShader::nglVertexAttribPointerARB, index, size, Shaders.byteType(unsigned), normalized, stride, buffer);
    }

    public static void glVertexAttribPointerARB(int index, int size, boolean unsigned, boolean normalized, int stride, IntBuffer buffer) {
        Shaders.attribPointer(ARBVertexShader::nglVertexAttribPointerARB, index, size, Shaders.intType(unsigned), normalized, stride, buffer);
    }

    public static void glVertexAttribPointerARB(int index, int size, boolean unsigned, boolean normalized, int stride, ShortBuffer buffer) {
        Shaders.attribPointer(ARBVertexShader::nglVertexAttribPointerARB, index, size, Shaders.shortType(unsigned), normalized, stride, buffer);
    }
}
