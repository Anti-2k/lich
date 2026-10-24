package dev.lich.launchwrapper.testsupport;

import net.minecraft.launchwrapper.IClassTransformer;

import java.nio.charset.StandardCharsets;

/** Swaps the UTF-8 constant "ORIGINAL" for "REPLACED" (same length) in fixture.Greeting. */
public class ReplaceStringTransformer implements IClassTransformer {
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!"fixture.Greeting".equals(name) || basicClass == null) {
            return basicClass;
        }
        byte[] from = "ORIGINAL".getBytes(StandardCharsets.UTF_8);
        byte[] to = "REPLACED".getBytes(StandardCharsets.UTF_8);
        byte[] result = basicClass.clone();
        for (int i = 0; i <= result.length - from.length; i++) {
            boolean match = true;
            for (int j = 0; j < from.length && match; j++) {
                match = result[i + j] == from[j];
            }
            if (match) {
                System.arraycopy(to, 0, result, i, to.length);
            }
        }
        return result;
    }
}
