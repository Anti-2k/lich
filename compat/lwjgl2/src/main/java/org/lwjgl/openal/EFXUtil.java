package org.lwjgl.openal;

import dev.lich.compat.lwjgl2.openal.Lwjgl2AL;

/** LWJGL 2's EFX probes: whether the device has EFX, and whether it supports an effect or filter type. */
public final class EFXUtil {
    private EFXUtil() {
    }

    public static boolean isEfxSupported() {
        if (!Lwjgl2AL.isCreated()) {
            throw new OpenALException("OpenAL has not been created.");
        }
        return ALC10.alcIsExtensionPresent(Lwjgl2AL.getDevice().pointer(), "ALC_EXT_EFX");
    }

    public static boolean isEffectSupported(int effectType) {
        if (!isEfxSupported()) {
            return false;
        }
        AL10.alGetError();
        int effect = EXTEfx.alGenEffects();
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            return false;
        }
        EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, effectType);
        boolean supported = AL10.alGetError() == AL10.AL_NO_ERROR;
        EXTEfx.alDeleteEffects(effect);
        return supported;
    }

    public static boolean isFilterSupported(int filterType) {
        if (!isEfxSupported()) {
            return false;
        }
        AL10.alGetError();
        int filter = EXTEfx.alGenFilters();
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            return false;
        }
        EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, filterType);
        boolean supported = AL10.alGetError() == AL10.AL_NO_ERROR;
        EXTEfx.alDeleteFilters(filter);
        return supported;
    }
}
