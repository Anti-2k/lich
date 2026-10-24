package dev.lich.launchwrapper;

import net.minecraft.launchwrapper.IClassTransformer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lich's own transformers, which {@code LaunchClassLoader} runs before ({@link #first()}) and after
 * ({@link #last()}) every transformer registered through {@code registerTransformer}. They live outside
 * the registered list, so FML's sorting and mods that remove or reorder transformers can't move them:
 * JDK-compat redirects must see bytes before anyone else, and LWJGL redirects and game fixes must see
 * them last, after mods' transformers and Mixins have matched the original calls. Both also run on
 * classes excluded from transformers, which no registered transformer sees.
 */
public final class LichTransformers {
    private static final List<IClassTransformer> FIRST = new CopyOnWriteArrayList<>();
    private static final List<IClassTransformer> LAST = new CopyOnWriteArrayList<>();

    private LichTransformers() {
    }

    /** Adds a transformer that runs before every registered one, after the first ones added before it. */
    public static void addFirst(IClassTransformer transformer) {
        FIRST.add(transformer);
    }

    /** Adds a transformer that runs after every registered one, after the last ones added before it. */
    public static void addLast(IClassTransformer transformer) {
        LAST.add(transformer);
    }

    public static List<IClassTransformer> first() {
        return FIRST;
    }

    public static List<IClassTransformer> last() {
        return LAST;
    }
}
