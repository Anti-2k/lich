package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * What the core and ARB mirrors of LWJGL 2's shader functions share: LWJGL 2 took shader source as bytes and
 * returned active uniform and attribute names, sizes and types from separate calls, where LWJGL 3 has one
 * native signature for each. The {@code Native*} interfaces are LWJGL 3's {@code ngl*} functions.
 */
final class Shaders {
    /** Vertex attribute arrays LWJGL 2 kept reachable, by index. */
    private static final Buffer[] ATTRIBUTE_ARRAYS = new Buffer[64];

    private Shaders() {
    }

    interface NativeShaderSource {
        void call(int shader, int count, long strings, long lengths);
    }

    interface NativeGetActive {
        void call(int program, int index, int maxLength, long length, long size, long type, long name);
    }

    interface NativeAttribPointer {
        void call(int index, int size, int type, boolean normalized, int stride, long pointer);
    }

    /** One source string: the bytes from {@code source}'s position to its limit. */
    static void shaderSource(NativeShaderSource function, int shader, ByteBuffer source) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long strings = stack.npointer(memAddress(source));
            long lengths = memAddress(stack.ints(source.remaining()));
            function.call(shader, 1, strings, lengths);
        }
    }

    static String activeName(NativeGetActive function, int program, int index, int maxLength, IntBuffer sizeType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer length = stack.mallocInt(1);
            ByteBuffer name = stack.malloc(Math.max(1, maxLength));
            long size;
            long type;
            if (sizeType == null) {
                size = memAddress(stack.mallocInt(1));
                type = memAddress(stack.mallocInt(1));
            } else {
                size = memAddress(sizeType);
                type = size + Integer.BYTES;
            }
            function.call(program, index, maxLength, memAddress(length), size, type, memAddress(name));
            byte[] bytes = new byte[length.get(0)];
            name.get(0, bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static int activeSize(NativeGetActive function, int program, int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1);
            function.call(program, index, 0, NULL, memAddress(size), memAddress(stack.mallocInt(1)), NULL);
            return size.get(0);
        }
    }

    static int activeType(NativeGetActive function, int program, int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer type = stack.mallocInt(1);
            function.call(program, index, 0, NULL, memAddress(stack.mallocInt(1)), memAddress(type), NULL);
            return type.get(0);
        }
    }

    static void attribPointer(NativeAttribPointer function, int index, int size, int type, boolean normalized,
                              int stride, Buffer buffer) {
        if (index >= 0 && index < ATTRIBUTE_ARRAYS.length) {
            ATTRIBUTE_ARRAYS[index] = buffer;
        }
        function.call(index, size, type, normalized, stride, memAddress(buffer));
    }

    static int byteType(boolean unsigned) {
        return unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE;
    }

    static int shortType(boolean unsigned) {
        return unsigned ? GL11.GL_UNSIGNED_SHORT : GL11.GL_SHORT;
    }

    static int intType(boolean unsigned) {
        return unsigned ? GL11.GL_UNSIGNED_INT : GL11.GL_INT;
    }
}
