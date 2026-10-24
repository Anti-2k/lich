package dev.lich.compat.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.analysis.SourceInterpreter;

/** {@link SourceInterpreter} with ASM 5's constructors; see {@link Asm5BasicInterpreter}. */
public class Asm5SourceInterpreter extends SourceInterpreter {
    public Asm5SourceInterpreter() {
        super(Opcodes.ASM9);
    }

    protected Asm5SourceInterpreter(int api) {
        super(api);
    }
}