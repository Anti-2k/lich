package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * LWJGL 2's {@code glGetBufferPointer}, which returned the mapped buffer as a {@link ByteBuffer} of the
 * buffer's size; LWJGL 3 returns the address. Also stands in for the ARB versions ({@code glGetBufferPointerARB}).
 */
public final class Lwjgl2GL15 {
    private Lwjgl2GL15() {
    }

    public static ByteBuffer glGetBufferPointer(int target, int pname) {
        long pointer = GL15.glGetBufferPointer(target, pname);
        return pointer == 0 ? null : MemoryUtil.memByteBuffer(pointer, GL15.glGetBufferParameteri(target, GL15.GL_BUFFER_SIZE));
    }

    public static ByteBuffer glGetBufferPointerARB(int target, int pname) {
        return glGetBufferPointer(target, pname);
    }
}
