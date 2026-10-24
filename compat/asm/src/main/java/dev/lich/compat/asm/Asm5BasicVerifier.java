package dev.lich.compat.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** {@link BasicVerifier} with ASM 5's constructors; see {@link Asm5BasicInterpreter}. */
public class Asm5BasicVerifier extends BasicVerifier {
    public Asm5BasicVerifier() {
        super(Opcodes.ASM9);
    }

    protected Asm5BasicVerifier(int api) {
        super(api);
    }
}