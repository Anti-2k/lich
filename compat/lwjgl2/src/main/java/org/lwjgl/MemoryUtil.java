package org.lwjgl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

/**
 * LWJGL 2's {@code MemoryUtil}: addresses of direct buffers and null-terminated string buffers. LWJGL 3 has the same
 * functions in {@code org.lwjgl.system.MemoryUtil} under other names; Sodium's ports (Angelica) write vertex data
 * through these addresses. {@code getAddress0} is the buffer's start, {@code getAddress} its position (or the given
 * one), and the {@code Safe} variants return 0 for {@code null}. A {@code PointerBuffer} is LWJGL 3's.
 */
public final class MemoryUtil {
    private MemoryUtil() {
    }

    public static long getAddress0(Buffer buffer) {
        return org.lwjgl.system.MemoryUtil.memAddress0(buffer);
    }

    public static long getAddress0Safe(Buffer buffer) {
        return buffer == null ? 0L : getAddress0(buffer);
    }

    public static long getAddress0(PointerBuffer buffer) {
        return buffer.address0();
    }

    public static long getAddress0Safe(PointerBuffer buffer) {
        return buffer == null ? 0L : getAddress0(buffer);
    }

    public static long getAddress(ByteBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(ByteBuffer buffer, int position) {
        return getAddress0(buffer) + position;
    }

    public static long getAddress(ShortBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(ShortBuffer buffer, int position) {
        return getAddress0(buffer) + ((long) position << 1);
    }

    public static long getAddress(CharBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(CharBuffer buffer, int position) {
        return getAddress0(buffer) + ((long) position << 1);
    }

    public static long getAddress(IntBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(IntBuffer buffer, int position) {
        return getAddress0(buffer) + ((long) position << 2);
    }

    public static long getAddress(FloatBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(FloatBuffer buffer, int position) {
        return getAddress0(buffer) + ((long) position << 2);
    }

    public static long getAddress(LongBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(LongBuffer buffer, int position) {
        return getAddress0(buffer) + ((long) position << 3);
    }

    public static long getAddress(DoubleBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(DoubleBuffer buffer, int position) {
        return getAddress0(buffer) + ((long) position << 3);
    }

    public static long getAddress(PointerBuffer buffer) {
        return getAddress(buffer, buffer.position());
    }

    public static long getAddress(PointerBuffer buffer, int position) {
        return getAddress0(buffer) + (long) position * PointerBuffer.POINTER_SIZE;
    }

    public static long getAddressSafe(ByteBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(ByteBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(ShortBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(ShortBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(CharBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(CharBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(IntBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(IntBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(FloatBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(FloatBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(LongBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(LongBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(DoubleBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(DoubleBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    public static long getAddressSafe(PointerBuffer buffer) {
        return buffer == null ? 0L : getAddress(buffer);
    }

    public static long getAddressSafe(PointerBuffer buffer, int position) {
        return buffer == null ? 0L : getAddress(buffer, position);
    }

    /**
     * {@code text} and a terminating NUL, in a new direct buffer ready to read; {@code null} for {@code null}. LWJGL 2's
     * "ASCII" was ISO-8859-1, and its UTF-16 little-endian without a byte order mark.
     */
    public static ByteBuffer encodeASCII(CharSequence text) {
        return encode(text, StandardCharsets.ISO_8859_1);
    }

    public static ByteBuffer encodeUTF8(CharSequence text) {
        return encode(text, StandardCharsets.UTF_8);
    }

    public static ByteBuffer encodeUTF16(CharSequence text) {
        return encode(text, StandardCharsets.UTF_16LE);
    }

    /** Decodes the buffer's remaining bytes, which it consumes, as LWJGL 2 did; {@code null} for {@code null}. */
    public static String decodeASCII(ByteBuffer buffer) {
        return decode(buffer, StandardCharsets.ISO_8859_1);
    }

    public static String decodeUTF8(ByteBuffer buffer) {
        return decode(buffer, StandardCharsets.UTF_8);
    }

    public static String decodeUTF16(ByteBuffer buffer) {
        return decode(buffer, StandardCharsets.UTF_16LE);
    }

    private static ByteBuffer encode(CharSequence text, Charset charset) {
        if (text == null) {
            return null;
        }
        CharBuffer in = CharBuffer.wrap(text + "\0");
        CharsetEncoder encoder = charset.newEncoder();
        ByteBuffer out = BufferUtils.createByteBuffer((int) (in.remaining() * encoder.maxBytesPerChar()));
        CoderResult result = encoder.encode(in, out, true);
        if (result.isUnderflow()) {
            result = encoder.flush(out);
        }
        throwIfError(result);
        out.flip();
        return out;
    }

    private static String decode(ByteBuffer buffer, Charset charset) {
        if (buffer == null) {
            return null;
        }
        CharsetDecoder decoder = charset.newDecoder();
        CharBuffer out = CharBuffer.allocate((int) Math.ceil(buffer.remaining() * (double) decoder.maxCharsPerByte()));
        CoderResult result = decoder.decode(buffer, out, true);
        if (result.isUnderflow()) {
            result = decoder.flush(out);
        }
        throwIfError(result);
        out.flip();
        return out.toString();
    }

    private static void throwIfError(CoderResult result) {
        if (result.isError()) {
            try {
                result.throwException();
            } catch (CharacterCodingException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
