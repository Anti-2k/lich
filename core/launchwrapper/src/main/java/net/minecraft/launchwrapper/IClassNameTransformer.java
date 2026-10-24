package net.minecraft.launchwrapper;

/**
 * LaunchWrapper 1.12's class name transformer, unchanged. The first registered transformer that
 * implements it renames classes, e.g. FML's obfuscated → SRG remapper.
 */
public interface IClassNameTransformer {
    String unmapClassName(String name);

    String remapClassName(String name);
}
