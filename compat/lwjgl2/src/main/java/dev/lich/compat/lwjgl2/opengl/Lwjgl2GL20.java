package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/** LWJGL 2's {@code GL20} overloads that LWJGL 3 dropped: byte shader sources, active variable queries, typed pointers. */
public final class Lwjgl2GL20 {
    private Lwjgl2GL20() {
    }

    public static void glShaderSource(int shader, ByteBuffer string) {
        Shaders.shaderSource(GL20::nglShaderSource, shader, string);
    }

    public static String glGetActiveAttrib(int program, int index, int maxLength) {
        return Shaders.activeName(GL20::nglGetActiveAttrib, program, index, maxLength, null);
    }

    public static String glGetActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) {
        return Shaders.activeName(GL20::nglGetActiveAttrib, program, index, maxLength, sizeType);
    }

    public static int glGetActiveAttribSize(int program, int index) {
        return Shaders.activeSize(GL20::nglGetActiveAttrib, program, index);
    }

    public static int glGetActiveAttribType(int program, int index) {
        return Shaders.activeType(GL20::nglGetActiveAttrib, program, index);
    }

    public static String glGetActiveUniform(int program, int index, int maxLength) {
        return Shaders.activeName(GL20::nglGetActiveUniform, program, index, maxLength, null);
    }

    public static String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeType) {
        return Shaders.activeName(GL20::nglGetActiveUniform, program, index, maxLength, sizeType);
    }

    public static int glGetActiveUniformSize(int program, int index) {
        return Shaders.activeSize(GL20::nglGetActiveUniform, program, index);
    }

    public static int glGetActiveUniformType(int program, int index) {
        return Shaders.activeType(GL20::nglGetActiveUniform, program, index);
    }

    public static ByteBuffer glGetVertexAttribPointer(int index, int pname, long resultSize) {
        long pointer = GL20.glGetVertexAttribPointer(index, pname);
        return pointer == 0 ? null : MemoryUtil.memByteBuffer(pointer, (int) resultSize);
    }

    public static void glVertexAttribPointer(int index, int size, boolean normalized, int stride, DoubleBuffer buffer) {
        Shaders.attribPointer(GL20::nglVertexAttribPointer, index, size, GL11.GL_DOUBLE, normalized, stride, buffer);
    }

    public static void glVertexAttribPointer(int index, int size, boolean normalized, int stride, FloatBuffer buffer) {
        Shaders.attribPointer(GL20::nglVertexAttribPointer, index, size, GL11.GL_FLOAT, normalized, stride, buffer);
    }

    public static void glVertexAttribPointer(int index, int size, boolean unsigned, boolean normalized, int stride, ByteBuffer buffer) {
        Shaders.attribPointer(GL20::nglVertexAttribPointer, index, size, Shaders.byteType(unsigned), normalized, stride, buffer);
    }

    public static void glVertexAttribPointer(int index, int size, boolean unsigned, boolean normalized, int stride, IntBuffer buffer) {
        Shaders.attribPointer(GL20::nglVertexAttribPointer, index, size, Shaders.intType(unsigned), normalized, stride, buffer);
    }

    public static void glVertexAttribPointer(int index, int size, boolean unsigned, boolean normalized, int stride, ShortBuffer buffer) {
        Shaders.attribPointer(GL20::nglVertexAttribPointer, index, size, Shaders.shortType(unsigned), normalized, stride, buffer);
    }
}
