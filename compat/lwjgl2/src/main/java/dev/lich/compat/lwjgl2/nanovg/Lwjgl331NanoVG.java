package dev.lich.compat.lwjgl2.nanovg;

import org.lwjgl.nanovg.NanoVG;

import java.nio.ByteBuffer;

/**
 * NanoVG functions as LWJGL 3.3.1 had them, for mods built against it (OneConfig): {@code freeData} was an
 * {@code int} and became a {@code boolean} by 3.3.6, which Lich ships. Not LWJGL 2 at all, but the same kind of
 * mirror: only calls with the old descriptors are pointed here.
 */
public final class Lwjgl331NanoVG {
    private Lwjgl331NanoVG() {
    }

    public static int nvgCreateFontMem(long ctx, ByteBuffer name, ByteBuffer data, int freeData) {
        return NanoVG.nvgCreateFontMem(ctx, name, data, freeData != 0);
    }

    public static int nvgCreateFontMem(long ctx, CharSequence name, ByteBuffer data, int freeData) {
        return NanoVG.nvgCreateFontMem(ctx, name, data, freeData != 0);
    }

    public static int nvgCreateFontMemAtIndex(long ctx, ByteBuffer name, ByteBuffer data, int freeData, int fontIndex) {
        return NanoVG.nvgCreateFontMemAtIndex(ctx, name, data, freeData != 0, fontIndex);
    }

    public static int nvgCreateFontMemAtIndex(long ctx, CharSequence name, ByteBuffer data, int freeData, int fontIndex) {
        return NanoVG.nvgCreateFontMemAtIndex(ctx, name, data, freeData != 0, fontIndex);
    }
}
