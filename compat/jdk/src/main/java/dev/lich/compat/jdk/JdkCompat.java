package dev.lich.compat.jdk;

import dev.lich.transform.ClassFiles;
import dev.lich.transform.CompatTransformer;

/** The redirect table for removed JDK APIs: which JDK references the compat pass points at which shim. */
public final class JdkCompat {
    private static final String SHIMS = "dev/lich/compat/jdk/";
    private static final String REFLECT = SHIMS + "reflect/";

    private JdkCompat() {
    }

    public static CompatTransformer.Builder register(CompatTransformer.Builder builder) {
        builder
                // FML's binpatches (ClassPatchManager).
                .renameType("java/util/jar/Pack200", SHIMS + "Pack200")
                .renameType("java/util/jar/Pack200$Unpacker", SHIMS + "Pack200$Unpacker")
                .renameType("java/util/jar/Pack200$Packer", SHIMS + "Pack200$Packer")
                // JSR-250, gone since Java 11. Mixin 0.7 checks transformers for @Resource.
                .renameType("javax/annotation/Resource", SHIMS + "annotation/Resource")
                .renameType("javax/annotation/Resource$AuthenticationType", SHIMS + "annotation/Resource$AuthenticationType")
                // Left with CORBA in Java 11 too. LiteLoader 1.7.10 throws it (LiteLoader.getMod).
                .renameType("javax/activity/InvalidActivityException", SHIMS + "activity/InvalidActivityException")
                // Nashorn left the JDK in 15 (JEP 372); Lich ships the standalone one, same API in another
                // package. Mods also name its classes in strings, for Class.forName.
                .renamePackage("jdk/nashorn/", "org/openjdk/nashorn/")
                .renameString("jdk.nashorn.api.scripting.NashornScriptEngineFactory", "org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory")
                .renameString("jdk.nashorn.api.scripting.ScriptObjectMirror", "org.openjdk.nashorn.api.scripting.ScriptObjectMirror")
                .renameString("jdk.nashorn.api.scripting.JSObject", "org.openjdk.nashorn.api.scripting.JSObject")
                .renameString("jdk.nashorn.api.scripting.ClassFilter", "org.openjdk.nashorn.api.scripting.ClassFilter")
                // FMLTweaker's security manager.
                .redirect("java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V", SHIMS + "SystemShims")
                // Writing static finals and creating enum constants: Forge's ObjectHolderRef and EnumHelper,
                // and every mod that copied them.
                .renameType("sun/reflect/Reflection", REFLECT + "Reflection")
                .renameString("sun.reflect.Reflection", "dev.lich.compat.jdk.reflect.Reflection")
                .renameType("sun/reflect/FieldAccessor", REFLECT + "FieldAccessor")
                .renameType("sun/reflect/ConstructorAccessor", REFLECT + "ConstructorAccessor")
                .renameString("sun.reflect.FieldAccessor", "dev.lich.compat.jdk.reflect.FieldAccessor")
                .renameString("sun.reflect.ConstructorAccessor", "dev.lich.compat.jdk.reflect.ConstructorAccessor")
                .renameString("sun.reflect.ReflectionFactory", "dev.lich.compat.jdk.reflect.ReflectionFactory",
                        "newFieldAccessor", "newConstructorAccessor")
                // In every class: mods look Field.modifiers up through helpers that take the name as an
                // argument (IC2's ReflectionUtil.getField, for GraviSuite), then write it where the name is.
                .redirect("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;",
                        REFLECT + "ReflectionShims")
                .redirect("java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                        REFLECT + "ReflectionShims", "modifiers")
                .redirect("java/lang/reflect/Field", "setInt", "(Ljava/lang/Object;I)V",
                        REFLECT + "ReflectionShims", "modifiers")
                .redirect("java/lang/reflect/Field", "getInt", "(Ljava/lang/Object;)I",
                        REFLECT + "ReflectionShims", "modifiers");
        // The other primitive writes, which old code makes to static finals as it makes Field.set.
        for (String primitive : new String[]{"Boolean:Z", "Byte:B", "Char:C", "Short:S", "Long:J", "Float:F", "Double:D"}) {
            String[] nameAndDescriptor = primitive.split(":");
            builder.redirect("java/lang/reflect/Field", "set" + nameAndDescriptor[0],
                    "(Ljava/lang/Object;" + nameAndDescriptor[1] + ")V", REFLECT + "ReflectionShims", "modifiers");
        }
        return builder
                // Java 9+ rejects null URLs, which Java 8's URLClassLoader kept (UrlClassLoaders).
                .filterFirstArgument("java/net/URLClassLoader", "<init>", "([Ljava/net/URL;)V", SHIMS + "UrlClassLoaders", "withoutNulls")
                .filterFirstArgument("java/net/URLClassLoader", "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V",
                        SHIMS + "UrlClassLoaders", "withoutNulls")
                // Java 9+ parses UUID strings more strictly, and overlong groups to other values (UuidConstants).
                .patchWhere(new UuidConstants(), "java/util/UUID", "fromString")
                // A null loader only finds the JDK's own script engines, which have no Nashorn since Java 15.
                .filterFirstArgument("javax/script/ScriptEngineManager", "<init>", "(Ljava/lang/ClassLoader;)V",
                        SHIMS + "ScriptEngines", "withNashorn")
                // The game's commons-lang3 doesn't know Java 9+ version strings.
                .patch(CommonsLangJavaVersion.CLASS, new CommonsLangJavaVersion())
                .patch(ZenUtilsPlatform.CLASS, new ZenUtilsPlatform())
                // Java 9+ checks that method handles to interface methods say so; ASM 5.0-era tools didn't.
                .fixInterfaceHandles(ClassFiles::isInterface);
    }
}
