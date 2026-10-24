package dev.lich.compat.lwjgl2;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * LWJGL 2's {@code PointerBuffer} members that LWJGL 3's lacks: its constructors, its static pointer size,
 * its view of the backing bytes, and the {@code java.nio}-style methods that returned a {@code PointerBuffer}
 * (LWJGL 3's return their generic type, which erases to {@code CustomBuffer}).
 */
public final class Lwjgl2PointerBuffer {
    private Lwjgl2PointerBuffer() {
    }

    @Lwjgl2Compat.Constructor
    public static PointerBuffer create(int capacity) {
        return PointerBuffer.allocateDirect(capacity);
    }

    @Lwjgl2Compat.Constructor
    public static PointerBuffer create(ByteBuffer source) {
        return PointerBuffer.create(source);
    }

    public static int getPointerSize() {
        return PointerBuffer.POINTER_SIZE;
    }

    /** The whole backing memory, from the buffer's start, as LWJGL 2's backing {@code ByteBuffer} was. */
    @Lwjgl2Compat.Receiver
    public static ByteBuffer getBuffer(PointerBuffer buffer) {
        return MemoryUtil.memByteBuffer(buffer.address0(), buffer.capacity() * PointerBuffer.POINTER_SIZE);
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer position(PointerBuffer buffer, int newPosition) {
        return buffer.position(newPosition);
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer limit(PointerBuffer buffer, int newLimit) {
        return buffer.limit(newLimit);
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer mark(PointerBuffer buffer) {
        return buffer.mark();
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer reset(PointerBuffer buffer) {
        return buffer.reset();
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer clear(PointerBuffer buffer) {
        return buffer.clear();
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer flip(PointerBuffer buffer) {
        return buffer.flip();
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer rewind(PointerBuffer buffer) {
        return buffer.rewind();
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer slice(PointerBuffer buffer) {
        return buffer.slice();
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer duplicate(PointerBuffer buffer) {
        return buffer.duplicate();
    }

    @Lwjgl2Compat.Receiver
    public static PointerBuffer compact(PointerBuffer buffer) {
        return buffer.compact();
    }
}
