package dev.lich.compat.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

/**
 * What subclasses of {@link BasicInterpreter} written against ASM 5 extend instead (the compat pass
 * rebases them, see {@link AsmCompat}): ASM 6+ throws when a subclass calls the constructor without an
 * API version, which ASM 5 allowed.
 */
public class Asm5BasicInterpreter extends BasicInterpreter {
    public Asm5BasicInterpreter() {
        super(Opcodes.ASM9);
    }

    protected Asm5BasicInterpreter(int api) {
        super(api);
    }
}