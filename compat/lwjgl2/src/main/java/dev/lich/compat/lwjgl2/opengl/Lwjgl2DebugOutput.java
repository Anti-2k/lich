package dev.lich.compat.lwjgl2.opengl;

import org.lwjgl.opengl.AMDDebugOutput;
import org.lwjgl.opengl.AMDDebugOutputCallback;
import org.lwjgl.opengl.ARBDebugOutput;
import org.lwjgl.opengl.ARBDebugOutputCallback;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageAMDCallback;
import org.lwjgl.opengl.GLDebugMessageARBCallback;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebugCallback;
import org.lwjgl.system.Callback;

/**
 * LWJGL 2's debug message callbacks (GL 4.3/KHR_debug, ARB_debug_output, AMD_debug_output), registered with
 * LWJGL 3 as callbacks that call the LWJGL 2 handler. Each kind's native callback lives until it's replaced.
 */
public final class Lwjgl2DebugOutput {
    private static Callback khr;
    private static Callback arb;
    private static Callback amd;

    private Lwjgl2DebugOutput() {
    }

    private static Callback replace(Callback old, Callback replacement) {
        if (old != null) {
            old.free();
        }
        return replacement;
    }

    public static synchronized void glDebugMessageCallback(KHRDebugCallback callback) {
        GLDebugMessageCallback native_ = callback == null ? null : GLDebugMessageCallback.create(
                (source, type, id, severity, length, message, userParam) -> callback.getHandler().handleMessage(
                        source, type, id, severity, GLDebugMessageCallback.getMessage(length, message)));
        GL43.glDebugMessageCallback(native_, 0L);
        khr = replace(khr, native_);
    }

    public static synchronized void glDebugMessageCallbackARB(ARBDebugOutputCallback callback) {
        GLDebugMessageARBCallback native_ = callback == null ? null : GLDebugMessageARBCallback.create(
                (source, type, id, severity, length, message, userParam) -> callback.getHandler().handleMessage(
                        source, type, id, severity, GLDebugMessageARBCallback.getMessage(length, message)));
        ARBDebugOutput.glDebugMessageCallbackARB(native_, 0L);
        arb = replace(arb, native_);
    }

    public static synchronized void glDebugMessageCallbackAMD(AMDDebugOutputCallback callback) {
        GLDebugMessageAMDCallback native_ = callback == null ? null : GLDebugMessageAMDCallback.create(
                (id, category, severity, length, message, userParam) -> callback.getHandler().handleMessage(
                        id, category, severity, GLDebugMessageAMDCallback.getMessage(length, message)));
        AMDDebugOutput.glDebugMessageCallbackAMD(native_, 0L);
        amd = replace(amd, native_);
    }
}
