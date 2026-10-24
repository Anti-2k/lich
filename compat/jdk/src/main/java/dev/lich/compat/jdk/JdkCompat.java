package dev.lich.compat.jdk;

import dev.lich.transform.CompatTransformer;

/** The redirect table for removed JDK APIs: which JDK references the compat pass points at which shim. */
public final class JdkCompat {
    private static final String SHIMS = "dev/lich/compat/jdk/";
    private static final String REFLECT = SHIMS + "reflect/";

    private JdkCompat() {
    }

    public static CompatTransformer.Builder register(CompatTransformer.Builder builder) {
        return builder
                // FML's binpatches (ClassPatchManager).
                .renameType("java/util/jar/Pack200", SHIMS + "Pack200")
                .renameType("java/util/jar/Pack200$Unpacker", SHIMS + "Pack200$Unpacker")
                .renameType("java/util/jar/Pack200$Packer", SHIMS + "Pack200$Packer")
                // FMLTweaker's security manager.
                .redirect("java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V", SHIMS + "SystemShims")
                // Writing static finals and creating enum constants: Forge's ObjectHolderRef and EnumHelper,
                // and every mod that copied them.
                .renameType("sun/reflect/FieldAccessor", REFLECT + "FieldAccessor")
                .renameType("sun/reflect/ConstructorAccessor", REFLECT + "ConstructorAccessor")
                .renameString("sun.reflect.FieldAccessor", "dev.lich.compat.jdk.reflect.FieldAccessor")
                .renameString("sun.reflect.ConstructorAccessor", "dev.lich.compat.jdk.reflect.ConstructorAccessor")
                .renameString("sun.reflect.ReflectionFactory", "dev.lich.compat.jdk.reflect.ReflectionFactory",
                        "newFieldAccessor", "newConstructorAccessor")
                .redirect("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                        REFLECT + "ReflectionShims", "modifiers")
                .redirect("java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                        REFLECT + "ReflectionShims", "modifiers");
    }
}
