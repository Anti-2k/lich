package org.lwjgl.opengl;

/** LWJGL 2's GL error helpers. The strings are LWJGL 2's: the game logs them ({@code GLU.gluErrorString}). */
public final class Util {
    private Util() {
    }

    public static void checkGLError() throws OpenGLException {
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new OpenGLException(error);
        }
    }

    public static String translateGLErrorString(int errorCode) {
        return switch (errorCode) {
            case GL11.GL_NO_ERROR -> "No error";
            case GL11.GL_INVALID_ENUM -> "Invalid enum";
            case GL11.GL_INVALID_VALUE -> "Invalid value";
            case GL11.GL_INVALID_OPERATION -> "Invalid operation";
            case GL11.GL_STACK_OVERFLOW -> "Stack overflow";
            case GL11.GL_STACK_UNDERFLOW -> "Stack underflow";
            case GL11.GL_OUT_OF_MEMORY -> "Out of memory";
            case ARBImaging.GL_TABLE_TOO_LARGE -> "Table too large";
            case EXTFramebufferObject.GL_INVALID_FRAMEBUFFER_OPERATION_EXT -> "Invalid framebuffer operation";
            default -> null;
        };
    }
}
