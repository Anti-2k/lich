package dev.lich.compat.lwjgl2.display;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.PixelFormat;

/**
 * Turns LWJGL 2's {@link PixelFormat} and {@link ContextAttribs} into GLFW window hints. Without
 * attributes GLFW asks for OpenGL 1.0 of any profile, which drivers answer with their newest
 * compatibility context: the legacy context LWJGL 2 created.
 */
public final class ContextHints {
    private ContextHints() {
    }

    public static void apply(PixelFormat format, ContextAttribs attribs) {
        PixelFormat pf = format == null ? new PixelFormat() : format;
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, pf.getAlphaBits());
        GLFW.glfwWindowHint(GLFW.GLFW_DEPTH_BITS, pf.getDepthBits());
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, pf.getStencilBits());
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, pf.getSamples());
        GLFW.glfwWindowHint(GLFW.GLFW_AUX_BUFFERS, pf.getAuxBuffers());
        int accumColor = Math.max(0, pf.getAccumulationBitsPerPixel() - pf.getAccumulationAlpha()) / 3;
        GLFW.glfwWindowHint(GLFW.GLFW_ACCUM_RED_BITS, accumColor);
        GLFW.glfwWindowHint(GLFW.GLFW_ACCUM_GREEN_BITS, accumColor);
        GLFW.glfwWindowHint(GLFW.GLFW_ACCUM_BLUE_BITS, accumColor);
        GLFW.glfwWindowHint(GLFW.GLFW_ACCUM_ALPHA_BITS, pf.getAccumulationAlpha());
        GLFW.glfwWindowHint(GLFW.GLFW_STEREO, pf.isStereo() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_SRGB_CAPABLE, pf.isSRGB() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        if (pf.getBitsPerPixel() == 16) {
            GLFW.glfwWindowHint(GLFW.GLFW_RED_BITS, 5);
            GLFW.glfwWindowHint(GLFW.GLFW_GREEN_BITS, 6);
            GLFW.glfwWindowHint(GLFW.GLFW_BLUE_BITS, 5);
        }

        if (attribs == null) {
            return;
        }
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, attribs.getMajorVersion());
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, attribs.getMinorVersion());
        if (attribs.isProfileCore()) {
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        } else if (attribs.isProfileCompatibility()) {
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        }
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, attribs.isForwardCompatible() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_DEBUG, attribs.isDebug() ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
        if (attribs.isRobustAccess()) {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_ROBUSTNESS, attribs.isLoseContextOnReset()
                    ? GLFW.GLFW_LOSE_CONTEXT_ON_RESET : GLFW.GLFW_NO_RESET_NOTIFICATION);
        }
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_RELEASE_BEHAVIOR,
                attribs.getContextReleaseBehavior() == ContextAttribs.CONTEXT_RELEASE_BEHAVIOR_NONE_ARB
                        ? GLFW.GLFW_RELEASE_BEHAVIOR_NONE : GLFW.GLFW_RELEASE_BEHAVIOR_FLUSH);
    }
}
