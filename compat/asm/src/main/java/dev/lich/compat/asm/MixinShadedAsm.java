package dev.lich.compat.asm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Tells which ASM Mixin has relocated to {@code org.spongepowered.asm.lib}. Mixin 0.7 shades ASM 5.2
 * there, which can't read Java 9+ class files, so the compat pass points it at Lich's ASM 9. Mixin forks
 * that relocate an ASM 9 (UniMixins: 9.8 now, 9.5 in 0.1.15, with bridges for 0.7-era plugins in its
 * own classes) must keep it: renaming would cut those bridges. An ASM 9 that stops short of Java 25 gets
 * its reader's limit raised instead ({@link ShadedAsmClassReader}).
 */
final class MixinShadedAsm {
    private static final String OPCODES = "org/spongepowered/asm/lib/Opcodes.class";

    private MixinShadedAsm() {
    }

    /**
     * Whether the relocated ASM that the game would load is older than ASM 9, too old to read Java 9+
     * class files at all. No copy at all also counts as old: classes written against Mixin 0.7's ASM can
     * then only work on Lich's.
     */
    static boolean isTooOld() {
        URL opcodes = find();
        if (opcodes == null) {
            return true;
        }
        try (InputStream in = opcodes.openStream()) {
            // ASM declares a V<n> constant for every class file version it reads; ASM 9.1 added V17.
            return !new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains("V17");
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + opcodes, e);
        }
    }

    /** From LaunchClassLoader once the launch has started (it's the context loader), else the class path. */
    private static URL find() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        URL url = context == null ? null : context.getResource(OPCODES);
        return url != null ? url : ClassLoader.getSystemResource(OPCODES);
    }
}
