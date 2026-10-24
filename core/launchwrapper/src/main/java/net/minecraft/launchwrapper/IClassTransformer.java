package net.minecraft.launchwrapper;

/** LaunchWrapper 1.12's class transformer interface, unchanged. */
public interface IClassTransformer {
    /**
     * @param basicClass the class bytes so far, or {@code null} if no class file exists, in which case a
     *                   transformer may create the class from nothing
     */
    byte[] transform(String name, String transformedName, byte[] basicClass);
}
