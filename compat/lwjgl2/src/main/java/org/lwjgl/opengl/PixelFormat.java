package org.lwjgl.opengl;

/**
 * LWJGL 2's immutable framebuffer request. {@link Display} turns it into GLFW window hints: like LWJGL 2's,
 * the values are minimums the driver may exceed, and the defaults (8 depth bits, nothing else) are LWJGL 2's.
 */
public final class PixelFormat implements PixelFormatLWJGL {
    private int bpp;
    private int alpha;
    private int depth;
    private int stencil;
    private int samples;
    private int colorSamples;
    private int numAuxBuffers;
    private int accumBpp;
    private int accumAlpha;
    private boolean stereo;
    private boolean floatingPoint;
    private boolean floatingPointPacked;
    private boolean sRGB;

    public PixelFormat() {
        this(0, 8, 0);
    }

    public PixelFormat(int alpha, int depth, int stencil) {
        this(alpha, depth, stencil, 0);
    }

    public PixelFormat(int alpha, int depth, int stencil, int samples) {
        this(0, alpha, depth, stencil, samples);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples) {
        this(bpp, alpha, depth, stencil, samples, 0, 0, 0, false);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples, int numAuxBuffers, int accumBpp,
                       int accumAlpha, boolean stereo) {
        this(bpp, alpha, depth, stencil, samples, numAuxBuffers, accumBpp, accumAlpha, stereo, false);
    }

    public PixelFormat(int bpp, int alpha, int depth, int stencil, int samples, int numAuxBuffers, int accumBpp,
                       int accumAlpha, boolean stereo, boolean floatingPoint) {
        this.bpp = bpp;
        this.alpha = alpha;
        this.depth = depth;
        this.stencil = stencil;
        this.samples = samples;
        this.numAuxBuffers = numAuxBuffers;
        this.accumBpp = accumBpp;
        this.accumAlpha = accumAlpha;
        this.stereo = stereo;
        this.floatingPoint = floatingPoint;
    }

    private PixelFormat copy() {
        PixelFormat copy = new PixelFormat(bpp, alpha, depth, stencil, samples, numAuxBuffers, accumBpp, accumAlpha,
                stereo, floatingPoint);
        copy.colorSamples = colorSamples;
        copy.floatingPointPacked = floatingPointPacked;
        copy.sRGB = sRGB;
        return copy;
    }

    private static int checkNotNegative(int value, String what) {
        if (value < 0) {
            throw new IllegalArgumentException("Invalid number of " + what + " specified: " + value);
        }
        return value;
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public PixelFormat withBitsPerPixel(int bpp) {
        PixelFormat pf = copy();
        pf.bpp = checkNotNegative(bpp, "bits per pixel");
        return pf;
    }

    public int getAlphaBits() {
        return alpha;
    }

    public PixelFormat withAlphaBits(int alpha) {
        PixelFormat pf = copy();
        pf.alpha = checkNotNegative(alpha, "alpha bits");
        return pf;
    }

    public int getDepthBits() {
        return depth;
    }

    public PixelFormat withDepthBits(int depth) {
        PixelFormat pf = copy();
        pf.depth = checkNotNegative(depth, "depth bits");
        return pf;
    }

    public int getStencilBits() {
        return stencil;
    }

    public PixelFormat withStencilBits(int stencil) {
        PixelFormat pf = copy();
        pf.stencil = checkNotNegative(stencil, "stencil bits");
        return pf;
    }

    public int getSamples() {
        return samples;
    }

    public PixelFormat withSamples(int samples) {
        PixelFormat pf = copy();
        pf.samples = checkNotNegative(samples, "samples");
        return pf;
    }

    public PixelFormat withCoverageSamples(int colorSamples) {
        return withCoverageSamples(colorSamples, samples);
    }

    public PixelFormat withCoverageSamples(int colorSamples, int coverageSamples) {
        if (coverageSamples < 0 || colorSamples < 0 || (coverageSamples == 0 && colorSamples > 0)
                || coverageSamples < colorSamples) {
            throw new IllegalArgumentException("Invalid number of coverage samples specified: " + coverageSamples
                    + " - " + colorSamples);
        }
        PixelFormat pf = copy();
        pf.samples = coverageSamples;
        pf.colorSamples = colorSamples;
        return pf;
    }

    public int getAuxBuffers() {
        return numAuxBuffers;
    }

    public PixelFormat withAuxBuffers(int numAuxBuffers) {
        PixelFormat pf = copy();
        pf.numAuxBuffers = checkNotNegative(numAuxBuffers, "auxiliary buffers");
        return pf;
    }

    public int getAccumulationBitsPerPixel() {
        return accumBpp;
    }

    public PixelFormat withAccumulationBitsPerPixel(int accumBpp) {
        PixelFormat pf = copy();
        pf.accumBpp = checkNotNegative(accumBpp, "bits per pixel in the accumulation buffer");
        return pf;
    }

    public int getAccumulationAlpha() {
        return accumAlpha;
    }

    public PixelFormat withAccumulationAlpha(int accumAlpha) {
        PixelFormat pf = copy();
        pf.accumAlpha = checkNotNegative(accumAlpha, "alpha bits in the accumulation buffer");
        return pf;
    }

    public boolean isStereo() {
        return stereo;
    }

    public PixelFormat withStereo(boolean stereo) {
        PixelFormat pf = copy();
        pf.stereo = stereo;
        return pf;
    }

    public boolean isFloatingPoint() {
        return floatingPoint;
    }

    public PixelFormat withFloatingPoint(boolean floatingPoint) {
        PixelFormat pf = copy();
        pf.floatingPoint = floatingPoint;
        if (floatingPoint) {
            pf.floatingPointPacked = false;
        }
        return pf;
    }

    public PixelFormat withFloatingPointPacked(boolean floatingPointPacked) {
        PixelFormat pf = copy();
        pf.floatingPointPacked = floatingPointPacked;
        if (floatingPointPacked) {
            pf.floatingPoint = false;
        }
        return pf;
    }

    public boolean isSRGB() {
        return sRGB;
    }

    public PixelFormat withSRGB(boolean sRGB) {
        PixelFormat pf = copy();
        pf.sRGB = sRGB;
        return pf;
    }
}
