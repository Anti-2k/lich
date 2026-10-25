package org.lwjgl.opengl;

import dev.lich.compat.lwjgl2.display.ContextHints;
import dev.lich.compat.lwjgl2.display.FrameLimiter;
import dev.lich.compat.lwjgl2.display.GlContext;
import dev.lich.compat.lwjgl2.display.Glfw;
import dev.lich.compat.lwjgl2.display.KeyboardInput;
import dev.lich.compat.lwjgl2.display.MouseInput;
import dev.lich.compat.lwjgl2.display.WindowState;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.input.Controllers;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;

import java.awt.Canvas;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * LWJGL 2's game window, on a GLFW window. It keeps LWJGL 2's behavior where the game can tell: a window
 * mode is a {@link DisplayMode} and goes fullscreen only if it came from the monitor's list; the window is
 * centered and shown by {@link #create}, which also creates {@link Keyboard} and {@link Mouse}; buffers are
 * swapped only while the window is visible; {@link #isCloseRequested} clears the request it reports;
 * {@link #getWidth}/{@link #getHeight} are framebuffer pixels. Window messages are only processed on the
 * thread that created the window: Forge's loading screen calls {@link #update} from another thread, which
 * swaps buffers but leaves the messages to the main thread, as LWJGL 2's Windows message loop did. GLFW
 * makes the process DPI-aware on Windows, so on a scaled desktop the window has real pixels where LWJGL 2's
 * was stretched by Windows. As LWJGL 2 did each time it made a window, a windowed mode reads the
 * {@code org.lwjgl.opengl.Window.undecorated} property: borderless-fullscreen mods set it, then a desktop-sized mode.
 */
public final class Display {
    private static final Object LOCK = new Object();
    private static final String UNDECORATED = "org.lwjgl.opengl.Window.undecorated";

    private static String title = "Game";
    private static DisplayMode desktopMode;
    private static DisplayMode mode;
    private static boolean fullscreen;
    private static boolean resizable;
    private static int swapInterval;
    private static int locationX = -1;
    private static int locationY = -1;
    private static ByteBuffer[] icons;
    private static float red;
    private static float green;
    private static float blue;

    private static PixelFormat createdFormat;
    private static ContextAttribs createdAttribs;
    private static volatile GlContext context;
    private static volatile long window;
    private static volatile boolean focused;
    private static volatile boolean iconified;
    private static volatile boolean closeRequested;
    private static volatile boolean resizePending;
    private static boolean resized;
    private static int width;
    private static int height;

    private static final Drawable DRAWABLE = new Drawable() {
        @Override
        public boolean isCurrent() throws LWJGLException {
            return Display.isCurrent();
        }

        @Override
        public void makeCurrent() throws LWJGLException {
            Display.makeCurrent();
        }

        @Override
        public void releaseContext() throws LWJGLException {
            Display.releaseContext();
        }

        @Override
        public void destroy() {
            Display.destroy();
        }

        @Override
        public void setCLSharingProperties(PointerBuffer properties) {
            throw new UnsupportedOperationException("OpenCL sharing isn't supported");
        }
    };

    private Display() {
    }

    /** The context {@link SharedDrawable}s share textures with. */
    static GlContext context() {
        return context;
    }

    /** What the display was created with, for offscreen contexts that share with it. */
    static PixelFormat createdFormat() {
        synchronized (LOCK) {
            return createdFormat;
        }
    }

    static ContextAttribs createdAttribs() {
        synchronized (LOCK) {
            return createdAttribs;
        }
    }

    public static Drawable getDrawable() {
        return DRAWABLE;
    }

    private static long monitor() {
        Glfw.init();
        return GLFW.glfwGetPrimaryMonitor();
    }

    private static DisplayMode toDisplayMode(GLFWVidMode video) {
        int bits = video.redBits() + video.greenBits() + video.blueBits();
        // LWJGL 2 listed 32-bit modes where GLFW counts the 24 color bits.
        return new DisplayMode(video.width(), video.height(), bits == 24 ? 32 : bits, video.refreshRate());
    }

    public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
        synchronized (LOCK) {
            GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(monitor());
            if (modes == null) {
                throw new LWJGLException("Couldn't list the display modes");
            }
            Set<DisplayMode> unique = new LinkedHashSet<>();
            for (int i = 0; i < modes.limit(); i++) {
                unique.add(toDisplayMode(modes.get(i)));
            }
            return unique.toArray(new DisplayMode[0]);
        }
    }

    /** The monitor's mode when first asked, as LWJGL 2 read it once at startup. */
    public static DisplayMode getDesktopDisplayMode() {
        synchronized (LOCK) {
            if (desktopMode == null) {
                GLFWVidMode video = GLFW.glfwGetVideoMode(monitor());
                desktopMode = video == null ? new DisplayMode(640, 480) : toDisplayMode(video);
            }
            return desktopMode;
        }
    }

    public static DisplayMode getDisplayMode() {
        synchronized (LOCK) {
            if (mode == null) {
                mode = getDesktopDisplayMode();
            }
            return mode;
        }
    }

    public static void setDisplayMode(DisplayMode displayMode) throws LWJGLException {
        if (displayMode == null) {
            throw new NullPointerException("mode must be non-null");
        }
        synchronized (LOCK) {
            mode = displayMode;
            applyMode();
        }
    }

    public static void setDisplayModeAndFullscreen(DisplayMode displayMode) throws LWJGLException {
        if (displayMode == null) {
            throw new NullPointerException("mode must be non-null");
        }
        synchronized (LOCK) {
            mode = displayMode;
            fullscreen = displayMode.isFullscreenCapable();
            applyMode();
        }
    }

    public static void setFullscreen(boolean full) throws LWJGLException {
        synchronized (LOCK) {
            fullscreen = full;
            applyMode();
        }
    }

    public static boolean isFullscreen() {
        synchronized (LOCK) {
            return fullscreen && getDisplayMode().isFullscreenCapable();
        }
    }

    /** Moves the existing window to the current mode: fullscreen on the monitor, or a centered window. */
    private static void applyMode() {
        if (window == 0) {
            return;
        }
        DisplayMode current = getDisplayMode();
        if (isFullscreen()) {
            int refresh = current.getFrequency() > 0 ? current.getFrequency() : GLFW.GLFW_DONT_CARE;
            GLFW.glfwSetWindowMonitor(window, monitor(), 0, 0, current.getWidth(), current.getHeight(), refresh);
        } else {
            int[] position = windowPosition(current);
            if (GLFW.glfwGetWindowMonitor(window) != 0) {
                GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, decorated());
                GLFW.glfwSetWindowMonitor(window, 0, position[0], position[1], current.getWidth(), current.getHeight(),
                        GLFW.GLFW_DONT_CARE);
            } else {
                GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, decorated());
                GLFW.glfwSetWindowSize(window, current.getWidth(), current.getHeight());
                GLFW.glfwSetWindowPos(window, position[0], position[1]);
            }
        }
        if (context != null && context.isCurrent()) {
            GLFW.glfwSwapInterval(swapInterval);
        }
        readSizes();
    }

    /** LWJGL 2 read the property whenever it created a window, which every windowed mode change did. */
    private static int decorated() {
        return Boolean.getBoolean(UNDECORATED) ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE;
    }

    /** Where a window of {@code windowMode} goes: {@link #setLocation}'s, or centered on the desktop. */
    private static int[] windowPosition(DisplayMode windowMode) {
        DisplayMode desktop = getDesktopDisplayMode();
        int[] monitorX = new int[1];
        int[] monitorY = new int[1];
        GLFW.glfwGetMonitorPos(monitor(), monitorX, monitorY);
        int x = locationX == -1 ? (desktop.getWidth() - windowMode.getWidth()) / 2 : locationX;
        int y = locationY == -1 ? (desktop.getHeight() - windowMode.getHeight()) / 2 : locationY;
        return new int[]{monitorX[0] + Math.max(0, x), monitorY[0] + Math.max(0, y)};
    }

    public static void setDisplayConfiguration(float gamma, float brightness, float contrast) throws LWJGLException {
        synchronized (LOCK) {
            if (window == 0) {
                throw new LWJGLException("Display not yet created.");
            }
            if (gamma <= 0) {
                throw new IllegalArgumentException("Invalid gamma value");
            }
            GLFW.glfwSetGamma(monitor(), gamma);
        }
    }

    public static void sync(int fps) {
        FrameLimiter.sync(fps);
    }

    public static String getTitle() {
        synchronized (LOCK) {
            return title;
        }
    }

    public static Canvas getParent() {
        return null;
    }

    /** Embedding in an AWT canvas (applets) isn't supported; the game always runs in its own window. */
    public static void setParent(Canvas parent) throws LWJGLException {
        if (parent != null) {
            throw new LWJGLException("Lich runs the game in its own window; Display.setParent isn't supported");
        }
    }

    public static void setTitle(String newTitle) {
        synchronized (LOCK) {
            title = newTitle == null ? "" : newTitle;
            if (window != 0) {
                GLFW.glfwSetWindowTitle(window, title);
            }
        }
    }

    public static boolean isCloseRequested() {
        if (window == 0) {
            throw new IllegalStateException("Cannot determine close requested state of uncreated window");
        }
        boolean requested = closeRequested;
        closeRequested = false;
        return requested;
    }

    public static boolean isVisible() {
        if (window == 0) {
            throw new IllegalStateException("Cannot determine minimized state of uncreated window");
        }
        return !iconified;
    }

    public static boolean isActive() {
        if (window == 0) {
            throw new IllegalStateException("Cannot determine focused state of uncreated window");
        }
        return focused;
    }

    public static boolean isDirty() {
        if (window == 0) {
            throw new IllegalStateException("Cannot determine dirty state of uncreated window");
        }
        return false;
    }

    /** Processes window messages, then polls the input devices; a no-op off the main thread (see class docs). */
    public static void processMessages() {
        if (window == 0) {
            throw new IllegalStateException("Display not created");
        }
        if (Glfw.isEventThread()) {
            GLFW.glfwPollEvents();
            KeyboardInput.endOfMessages();
        }
        if (Mouse.isCreated()) {
            Mouse.poll();
        }
        if (Keyboard.isCreated()) {
            Keyboard.poll();
        }
        if (Controllers.isCreated()) {
            Controllers.poll();
        }
    }

    public static void swapBuffers() throws LWJGLException {
        if (window == 0) {
            throw new IllegalStateException("Display not created");
        }
        GLFW.glfwSwapBuffers(window);
    }

    public static void update() {
        update(true);
    }

    public static void update(boolean processMessages) {
        synchronized (LOCK) {
            if (window == 0) {
                throw new IllegalStateException("Display not created");
            }
            if (!iconified) {
                GLFW.glfwSwapBuffers(window);
            }
            if (processMessages) {
                processMessages();
            }
            boolean wasPending = resizePending;
            resizePending = false;
            if (wasPending) {
                readSizes();
            }
            resized = wasPending && !isFullscreen();
        }
    }

    public static void releaseContext() throws LWJGLException {
        GlContext current = context;
        if (current == null) {
            throw new IllegalStateException("Display not created");
        }
        current.release();
    }

    public static boolean isCurrent() throws LWJGLException {
        GlContext current = context;
        if (current == null) {
            throw new IllegalStateException("Display not created");
        }
        return current.isCurrent();
    }

    public static void makeCurrent() throws LWJGLException {
        GlContext current = context;
        if (current == null) {
            throw new IllegalStateException("Display not created");
        }
        current.makeCurrent();
    }

    public static void create() throws LWJGLException {
        create(new PixelFormat());
    }

    public static void create(PixelFormat pixelFormat) throws LWJGLException {
        create(pixelFormat, null, null);
    }

    public static void create(PixelFormat pixelFormat, Drawable sharedDrawable) throws LWJGLException {
        create(pixelFormat, sharedDrawable, null);
    }

    public static void create(PixelFormat pixelFormat, ContextAttribs attribs) throws LWJGLException {
        create(pixelFormat, null, attribs);
    }

    public static void create(PixelFormatLWJGL pixelFormat) throws LWJGLException {
        create(asPixelFormat(pixelFormat), null, null);
    }

    public static void create(PixelFormatLWJGL pixelFormat, Drawable sharedDrawable) throws LWJGLException {
        create(asPixelFormat(pixelFormat), sharedDrawable, null);
    }

    private static PixelFormat asPixelFormat(PixelFormatLWJGL pixelFormat) {
        if (pixelFormat instanceof PixelFormat pf) {
            return pf;
        }
        throw new IllegalArgumentException("Only org.lwjgl.opengl.PixelFormat is supported, not " + pixelFormat);
    }

    public static void create(PixelFormat pixelFormat, Drawable sharedDrawable, ContextAttribs attribs) throws LWJGLException {
        synchronized (LOCK) {
            if (window != 0) {
                throw new IllegalStateException("Only one LWJGL context may be instantiated at any one time.");
            }
            if (pixelFormat == null) {
                throw new NullPointerException("pixel_format cannot be null");
            }
            Glfw.init();
            DisplayMode current = getDisplayMode();
            boolean full = isFullscreen();
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, decorated());
            ContextHints.apply(pixelFormat, attribs);
            long share = sharedDrawable instanceof SharedDrawable shared ? shared.context().window()
                    : sharedDrawable instanceof Pbuffer pbuffer ? pbuffer.context().window() : 0L;
            long handle = GLFW.glfwCreateWindow(current.getWidth(), current.getHeight(), title, full ? monitor() : 0L, share);
            if (handle == 0) {
                throw new LWJGLException("Could not create the window (" + current + ", " + describe(pixelFormat) + ")");
            }
            window = handle;
            Glfw.setEventThread(Thread.currentThread());
            context = new GlContext(handle);
            createdFormat = pixelFormat;
            createdAttribs = attribs;
            installCallbacks(handle);
            if (!full) {
                int[] position = windowPosition(current);
                GLFW.glfwSetWindowPos(handle, position[0], position[1]);
            }
            if (icons != null) {
                applyIcons();
            }
            context.makeCurrent();
            GLFW.glfwSwapInterval(swapInterval);
            GL11.glClearColor(red, green, blue, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GLFW.glfwSwapBuffers(handle);
            GLFW.glfwShowWindow(handle);
            focused = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
            iconified = false;
            closeRequested = false;
            resized = false;
            readSizes();
            // On HiDPI screens the framebuffer is bigger than the mode the game asked for: tell it on the first
            // update, as a resize, so it sets its viewport from getWidth()/getHeight().
            resizePending = !full && (width != current.getWidth() || height != current.getHeight());
        }
        // As LWJGL 2: the input devices come with the display, unless the system properties say otherwise.
        if (!Boolean.getBoolean("org.lwjgl.opengl.Display.noinput")) {
            if (!Mouse.isCreated() && !Boolean.getBoolean("org.lwjgl.opengl.Display.nomouse")) {
                Mouse.create();
            }
            if (!Keyboard.isCreated() && !Boolean.getBoolean("org.lwjgl.opengl.Display.nokeyboard")) {
                Keyboard.create();
            }
        }
    }

    private static String describe(PixelFormat pf) {
        return "depth " + pf.getDepthBits() + ", stencil " + pf.getStencilBits() + ", alpha " + pf.getAlphaBits()
                + ", samples " + pf.getSamples();
    }

    private static void installCallbacks(long handle) {
        GLFW.glfwSetFramebufferSizeCallback(handle, (w, newWidth, newHeight) -> resizePending = true);
        GLFW.glfwSetWindowSizeCallback(handle, (w, newWidth, newHeight) -> resizePending = true);
        GLFW.glfwSetWindowFocusCallback(handle, (w, hasFocus) -> focused = hasFocus);
        GLFW.glfwSetWindowIconifyCallback(handle, (w, minimized) -> iconified = minimized);
        GLFW.glfwSetWindowCloseCallback(handle, w -> {
            closeRequested = true;
            GLFW.glfwSetWindowShouldClose(w, false);
        });
        GLFW.glfwSetKeyCallback(handle, (w, key, scancode, action, mods) -> KeyboardInput.key(key, scancode, action));
        GLFW.glfwSetCharCallback(handle, (w, codePoint) -> KeyboardInput.character(codePoint));
        GLFW.glfwSetMouseButtonCallback(handle, (w, button, action, mods) -> MouseInput.button(button, action == GLFW.GLFW_PRESS));
        GLFW.glfwSetCursorPosCallback(handle, (w, x, y) -> MouseInput.cursor(x, y));
        GLFW.glfwSetScrollCallback(handle, (w, x, y) -> MouseInput.scroll(y));
        GLFW.glfwSetCursorEnterCallback(handle, (w, entered) -> MouseInput.enter(entered));
    }

    private static void readSizes() {
        int[] fbWidth = new int[1];
        int[] fbHeight = new int[1];
        int[] windowWidth = new int[1];
        int[] windowHeight = new int[1];
        GLFW.glfwGetFramebufferSize(window, fbWidth, fbHeight);
        GLFW.glfwGetWindowSize(window, windowWidth, windowHeight);
        if (fbWidth[0] > 0 && fbHeight[0] > 0) {
            // Minimized windows report 0 x 0; the game keeps the last real size, as with LWJGL 2.
            width = fbWidth[0];
            height = fbHeight[0];
            WindowState.update(window, fbWidth[0], fbHeight[0], windowWidth[0], windowHeight[0]);
        }
    }

    public static void setInitialBackground(float r, float g, float b) {
        synchronized (LOCK) {
            red = r;
            green = g;
            blue = b;
        }
    }

    public static void destroy() {
        if (Mouse.isCreated()) {
            Mouse.destroy();
        }
        if (Keyboard.isCreated()) {
            Keyboard.destroy();
        }
        synchronized (LOCK) {
            GlContext current = context;
            if (current == null) {
                return;
            }
            context = null;
            window = 0;
            WindowState.clear();
            current.destroy();
            // LWJGL 2 went back to the desktop mode.
            mode = getDesktopDisplayMode();
        }
    }

    public static boolean isCreated() {
        return window != 0;
    }

    public static void setSwapInterval(int value) {
        synchronized (LOCK) {
            swapInterval = value;
            if (context != null && context.isCurrent()) {
                GLFW.glfwSwapInterval(value);
            }
        }
    }

    public static void setVSyncEnabled(boolean sync) {
        setSwapInterval(sync ? 1 : 0);
    }

    public static void setLocation(int newX, int newY) {
        synchronized (LOCK) {
            locationX = newX;
            locationY = newY;
            if (window != 0 && !isFullscreen()) {
                int[] position = windowPosition(getDisplayMode());
                GLFW.glfwSetWindowPos(window, position[0], position[1]);
            }
        }
    }

    public static String getAdapter() {
        return null;
    }

    public static String getVersion() {
        return null;
    }

    /** Keeps a copy of {@code iconImages} (RGBA, square) for the window, which uses the ones that fit. */
    public static int setIcon(ByteBuffer[] iconImages) {
        synchronized (LOCK) {
            if (icons != iconImages) {
                icons = new ByteBuffer[iconImages.length];
                for (int i = 0; i < iconImages.length; i++) {
                    ByteBuffer copy = ByteBuffer.allocate(iconImages[i].remaining());
                    copy.put(iconImages[i].duplicate()).flip();
                    icons[i] = copy;
                }
            }
            return window != 0 ? applyIcons() : 0;
        }
    }

    private static int applyIcons() {
        // Neither macOS nor Wayland lets an application set its window icon.
        if (Platform.get() == Platform.MACOSX) {
            return 0;
        }
        int count = 0;
        GLFWImage.Buffer images = GLFWImage.malloc(icons.length);
        ByteBuffer[] pixels = new ByteBuffer[icons.length];
        try {
            for (ByteBuffer icon : icons) {
                int size = (int) Math.sqrt(icon.remaining() / 4.0);
                if (size <= 0 || size * size * 4 != icon.remaining()) {
                    continue;
                }
                ByteBuffer direct = MemoryUtil.memAlloc(icon.remaining());
                direct.put(icon.duplicate()).flip();
                pixels[count] = direct;
                images.position(count).width(size).height(size).pixels(direct);
                count++;
            }
            if (count > 0) {
                images.position(0).limit(count);
                GLFW.glfwSetWindowIcon(window, images);
            }
        } finally {
            images.free();
            for (ByteBuffer direct : pixels) {
                if (direct != null) {
                    MemoryUtil.memFree(direct);
                }
            }
        }
        return count;
    }

    public static void setResizable(boolean value) {
        synchronized (LOCK) {
            resizable = value;
            if (window != 0) {
                GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_RESIZABLE, value ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
            }
        }
    }

    public static boolean isResizable() {
        synchronized (LOCK) {
            return resizable;
        }
    }

    public static boolean wasResized() {
        synchronized (LOCK) {
            return resized;
        }
    }

    public static int getX() {
        synchronized (LOCK) {
            if (window == 0 || isFullscreen()) {
                return isFullscreen() ? 0 : locationX;
            }
            int[] x = new int[1];
            GLFW.glfwGetWindowPos(window, x, null);
            return x[0];
        }
    }

    public static int getY() {
        synchronized (LOCK) {
            if (window == 0 || isFullscreen()) {
                return isFullscreen() ? 0 : locationY;
            }
            int[] y = new int[1];
            GLFW.glfwGetWindowPos(window, null, y);
            return y[0];
        }
    }

    public static int getWidth() {
        synchronized (LOCK) {
            if (isFullscreen()) {
                return getDisplayMode().getWidth();
            }
            return window != 0 ? width : getDisplayMode().getWidth();
        }
    }

    public static int getHeight() {
        synchronized (LOCK) {
            if (isFullscreen()) {
                return getDisplayMode().getHeight();
            }
            return window != 0 ? height : getDisplayMode().getHeight();
        }
    }

    /** 1: {@link #getWidth} and {@link #getHeight} already count framebuffer pixels. */
    public static float getPixelScaleFactor() {
        return 1f;
    }
}
