package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** LWJGL 2's {@code ARBShaderObjects} overloads that LWJGL 3 dropped; see {@link Lwjgl2GL20}. */
public final class Lwjgl2ARBShaderObjects {
    private Lwjgl2ARBShaderObjects() {
    }

    public static void glShaderSourceARB(int shader, ByteBuffer string) {
        Shaders.shaderSource(ARBShaderObjects::nglShaderSourceARB, shader, string);
    }

    public static String glGetActiveUniformARB(int programObj, int index, int maxLength) {
        return Shaders.activeName(ARBShaderObjects::nglGetActiveUniformARB, programObj, index, maxLength, null);
    }

    public static String glGetActiveUniformARB(int programObj, int index, int maxLength, IntBuffer sizeType) {
        return Shaders.activeName(ARBShaderObjects::nglGetActiveUniformARB, programObj, index, maxLength, sizeType);
    }

    public static int glGetActiveUniformSizeARB(int programObj, int index) {
        return Shaders.activeSize(ARBShaderObjects::nglGetActiveUniformARB, programObj, index);
    }

    public static int glGetActiveUniformTypeARB(int programObj, int index) {
        return Shaders.activeType(ARBShaderObjects::nglGetActiveUniformARB, programObj, index);
    }

    public static float glGetObjectParameterfARB(int obj, int pname) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer params = stack.mallocFloat(1);
            ARBShaderObjects.glGetObjectParameterfvARB(obj, pname, params);
            return params.get(0);
        }
    }
}
