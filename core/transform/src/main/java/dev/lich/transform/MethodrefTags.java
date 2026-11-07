package dev.lich.transform;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Turns an {@code InterfaceMethodref} constant whose owner is a class back into a {@code Methodref}. Java 8 resolved
 * {@code invokestatic} and {@code invokespecial} by what the owner is and ignored which of the two the constant was;
 * Java 9+ checks the constant and throws {@code IncompatibleClassChangeError: ... must be Methodref constant}.
 * Coremods leave such constants behind when they turn an interface call into a static call to one of their own
 * classes by editing the {@code MethodInsnNode} and keep its {@code itf} flag (TickCentral, which LagGoggles uses,
 * on Forge's {@code EventBus.post}). It runs after every transformer, so it sees what they wrote. The two constants
 * have the same layout, so only the tag byte changes. Owners whose class file can't be found are left alone.
 */
public final class MethodrefTags {
    private static final int UTF8 = 1;
    private static final int CLASS = 7;
    private static final int METHODREF = 10;
    private static final int INTERFACE_METHODREF = 11;
    private static final int ACC_INTERFACE = 0x0200;

    private MethodrefTags() {
    }

    /**
     * {@code bytes} with each {@code InterfaceMethodref} to an owner {@code isClass} accepts (or to the class itself,
     * if it isn't an interface) made a {@code Methodref}, telling {@code fixed} each such owner. Returns {@code bytes}
     * itself when nothing changes, or when they aren't a class file this understands.
     */
    public static byte[] fix(byte[] bytes, Predicate<String> isClass, Consumer<String> fixed) {
        if (bytes == null || bytes.length < 10 || u2(bytes, 0) != 0xCAFE || u2(bytes, 2) != 0xBABE) {
            return bytes;
        }
        try {
            return fixClassFile(bytes, isClass, fixed);
        } catch (ArrayIndexOutOfBoundsException truncated) {
            return bytes; // Not a whole class file: the class loader will say so.
        }
    }

    private static byte[] fixClassFile(byte[] bytes, Predicate<String> isClass, Consumer<String> fixed) {
        int count = u2(bytes, 8);
        int[] offsets = new int[count];
        boolean any = false;
        int at = 10;
        for (int i = 1; i < count; i++) {
            if (at >= bytes.length) {
                return bytes;
            }
            offsets[i] = at;
            switch (bytes[at] & 0xFF) {
                case UTF8 -> at += 3 + u2(bytes, at + 1);
                case 3, 4, 9, METHODREF, 12, 17, 18 -> at += 5;
                case INTERFACE_METHODREF -> {
                    any = true;
                    at += 5;
                }
                case 5, 6 -> { // Long and Double take two slots.
                    at += 9;
                    i++;
                }
                case CLASS, 8, 16, 19, 20 -> at += 3;
                case 15 -> at += 4;
                default -> {
                    return bytes;
                }
            }
        }
        if (!any || at + 4 > bytes.length) {
            return bytes;
        }
        boolean selfIsInterface = (u2(bytes, at) & ACC_INTERFACE) != 0;
        String self = className(bytes, offsets, u2(bytes, at + 2));
        byte[] out = bytes;
        for (int i = 1; i < count; i++) {
            int entry = offsets[i];
            if (entry == 0 || (bytes[entry] & 0xFF) != INTERFACE_METHODREF) {
                continue;
            }
            String owner = className(bytes, offsets, u2(bytes, entry + 1));
            if (owner == null || owner.startsWith("[")) {
                continue;
            }
            boolean ownerIsClass = owner.equals(self) ? !selfIsInterface : isClass.test(owner);
            if (ownerIsClass) {
                if (out == bytes) {
                    out = bytes.clone();
                }
                out[entry] = METHODREF;
                fixed.accept(owner);
            }
        }
        return out;
    }

    private static String className(byte[] bytes, int[] offsets, int classIndex) {
        if (classIndex <= 0 || classIndex >= offsets.length || offsets[classIndex] == 0
                || (bytes[offsets[classIndex]] & 0xFF) != CLASS) {
            return null;
        }
        int nameIndex = u2(bytes, offsets[classIndex] + 1);
        if (nameIndex <= 0 || nameIndex >= offsets.length || offsets[nameIndex] == 0
                || (bytes[offsets[nameIndex]] & 0xFF) != UTF8) {
            return null;
        }
        int start = offsets[nameIndex];
        // Class names have no NUL or supplementary characters, where modified UTF-8 differs from UTF-8.
        return new String(bytes, start + 3, u2(bytes, start + 1), StandardCharsets.UTF_8);
    }

    private static int u2(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) << 8 | bytes[at + 1] & 0xFF;
    }
}
