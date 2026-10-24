package org.lwjgl.opengl;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.memAddress;

/**
 * LWJGL 2's APPLE_vertex_array_object, which LWJGL 3.3 doesn't bind (macOS legacy contexts have it; GTNHLib
 * uses it there). The functions are looked up in the current context and called directly.
 */
public final class APPLEVertexArrayObject {
    public static final int GL_VERTEX_ARRAY_BINDING_APPLE = 0x85B5;

    private APPLEVertexArrayObject() {
    }

    private static long function(String name) {
        long address = GL.getFunctionProvider().getFunctionAddress(name);
        if (address == 0) {
            throw new IllegalStateException("Function is not supported: " + name);
        }
        return address;
    }

    public static void glBindVertexArrayAPPLE(int array) {
        JNI.invokeV(array, function("glBindVertexArrayAPPLE"));
    }

    public static void glDeleteVertexArraysAPPLE(IntBuffer arrays) {
        JNI.invokePV(arrays.remaining(), memAddress(arrays), function("glDeleteVertexArraysAPPLE"));
    }

    public static void glDeleteVertexArraysAPPLE(int array) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glDeleteVertexArraysAPPLE(stack.ints(array));
        }
    }

    public static void glGenVertexArraysAPPLE(IntBuffer arrays) {
        JNI.invokePV(arrays.remaining(), memAddress(arrays), function("glGenVertexArraysAPPLE"));
    }

    public static int glGenVertexArraysAPPLE() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer array = stack.mallocInt(1);
            glGenVertexArraysAPPLE(array);
            return array.get(0);
        }
    }

    public static boolean glIsVertexArrayAPPLE(int array) {
        return JNI.invokeZ(array, function("glIsVertexArrayAPPLE"));
    }
}
