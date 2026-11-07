package dev.lich.compat.jdk;

import java.util.UUID;

/**
 * Java 8's {@code UUID.fromString}, for {@link UuidConstants}. Java 9+ rejects strings longer than 36 characters and
 * masks each group to its width; Java 8 decoded each dash-separated group as a long and OR-ed it into place, so an
 * overlong group spilled into the next. Solar Flux Reborn names an attribute modifier
 * {@code "28575920-a562b-e364d-388af-837a6b8f5a8c"} and stopped in pre-init (Divine Journey 2). Items keep their
 * modifiers' UUIDs, so the value has to be Java 8's, not just any.
 */
final class Uuids {
    private Uuids() {
    }

    /** Java 8's algorithm, line for line (OpenJDK 8 {@code java.util.UUID.fromString}). */
    static UUID fromString(String name) {
        String[] components = name.split("-");
        if (components.length != 5) {
            throw new IllegalArgumentException("Invalid UUID string: " + name);
        }
        for (int i = 0; i < 5; i++) {
            components[i] = "0x" + components[i];
        }
        long mostSigBits = Long.decode(components[0]);
        mostSigBits <<= 16;
        mostSigBits |= Long.decode(components[1]);
        mostSigBits <<= 16;
        mostSigBits |= Long.decode(components[2]);

        long leastSigBits = Long.decode(components[3]);
        leastSigBits <<= 48;
        leastSigBits |= Long.decode(components[4]);

        return new UUID(mostSigBits, leastSigBits);
    }
}
