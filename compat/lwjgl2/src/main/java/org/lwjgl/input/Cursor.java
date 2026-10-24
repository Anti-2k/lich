package org.lwjgl.input;

import dev.lich.compat.lwjgl2.display.Glfw;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * LWJGL 2's native mouse cursor, as a GLFW cursor. LWJGL 2 took ARGB pixels with the origin at the bottom
 * left; GLFW takes RGBA from the top left, so the image is converted and the hotspot flipped. Only the first
 * image of an animated cursor is shown.
 */
public class Cursor {
    public static final int CURSOR_ONE_BIT_TRANSPARENCY = 1;
    public static final int CURSOR_8_BIT_ALPHA = 2;
    public static final int CURSOR_ANIMATION = 4;

    private long handle;

    public Cursor(int width, int height, int xHotspot, int yHotspot, int numImages, IntBuffer images, IntBuffer delays)
            throws LWJGLException {
        if (width <= 0 || height <= 0 || numImages <= 0) {
            throw new IllegalArgumentException("Invalid cursor size " + width + "x" + height + " x " + numImages);
        }
        if (images.remaining() < width * height * numImages) {
            throw new IllegalArgumentException("images must contain at least " + width * height * numImages + " elements");
        }
        if (xHotspot >= width || xHotspot < 0 || yHotspot >= height || yHotspot < 0) {
            throw new IllegalArgumentException("Invalid hotspot (" + xHotspot + ", " + yHotspot + ")");
        }
        Glfw.init();
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try (GLFWImage image = GLFWImage.malloc()) {
            int base = images.position();
            for (int row = 0; row < height; row++) {
                int source = base + (height - 1 - row) * width;
                for (int column = 0; column < width; column++) {
                    int argb = images.get(source + column);
                    pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
                }
            }
            pixels.flip();
            image.set(width, height, pixels);
            handle = GLFW.glfwCreateCursor(image, xHotspot, height - 1 - yHotspot);
        } finally {
            MemoryUtil.memFree(pixels);
        }
        if (handle == 0) {
            throw new LWJGLException("Could not create the cursor");
        }
    }

    public static int getMinCursorSize() {
        return 1;
    }

    public static int getMaxCursorSize() {
        return 512;
    }

    public static int getCapabilities() {
        return CURSOR_ONE_BIT_TRANSPARENCY | CURSOR_8_BIT_ALPHA;
    }

    long handle() {
        if (handle == 0) {
            throw new IllegalStateException("The cursor is destroyed");
        }
        return handle;
    }

    public void destroy() {
        if (handle != 0) {
            if (Mouse.getNativeCursor() == this) {
                try {
                    Mouse.setNativeCursor(null);
                } catch (LWJGLException | IllegalStateException ignored) {
                    // The mouse is gone already.
                }
            }
            GLFW.glfwDestroyCursor(handle);
            handle = 0;
        }
    }

    protected void setTimeout() {
    }

    protected boolean hasTimedOut() {
        return false;
    }

    protected void nextCursor() {
    }
}
