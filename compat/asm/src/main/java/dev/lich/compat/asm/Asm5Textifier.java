package dev.lich.compat.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.Textifier;

/**
 * {@link Textifier} printing instructions the way ASM 5.0.3's did, for code written against Forge's ASM 5. ASM 6+
 * marks calls to interface methods with {@code (itf)} and number constants with a type suffix ({@code LDC 0.5D}).
 * Mods hash this text to recognize the code they patch: Logistics Pipes checks Thermal Dynamics' item duct renderer
 * that way and stops the game when the hash is unknown. The compat pass rebases mods' Textifier subclasses onto this
 * class and swaps their {@code new Textifier()} for it; the printers it makes for each method are of this kind too.
 */
public class Asm5Textifier extends Textifier {
    public Asm5Textifier() {
        this(Opcodes.ASM9);
    }

    protected Asm5Textifier(int api) {
        super(api);
    }

    @Override
    protected Textifier createTextifier() {
        return new Asm5Textifier(api);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
        stringBuilder.setLength(0);
        stringBuilder.append(tab2).append(OPCODES[opcode]).append(' ');
        appendDescriptor(INTERNAL_NAME, owner);
        stringBuilder.append('.').append(name).append(' ');
        appendDescriptor(METHOD_DESCRIPTOR, descriptor);
        stringBuilder.append('\n');
        text.add(stringBuilder.toString());
    }

    @Override
    public void visitLdcInsn(Object value) {
        // Strings and class constants print as they did in ASM 5.
        if (!(value instanceof Number)) {
            super.visitLdcInsn(value);
            return;
        }
        stringBuilder.setLength(0);
        stringBuilder.append(tab2).append("LDC ").append(value).append('\n');
        text.add(stringBuilder.toString());
    }
}
