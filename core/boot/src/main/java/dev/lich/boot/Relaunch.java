package dev.lich.boot;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Boot when a mod has relaunched the game: Essential's loader (and others built like it) starts
 * {@code Launch.main} a second time in a class loader of its own, which defines fresh copies of every
 * class on the class path, Lich's included, and shares only {@code java.*}-style packages with the
 * first launch. That copy of Lich's LaunchWrapper then runs this copy of boot ({@code LaunchCore.startBoot}).
 * Three things work differently from a first launch: the relaunch loader isn't {@link LichSystemClassLoader},
 * so Lich's jars are found by classifying its URLs; the compat pass for the classes it defines
 * (LaunchWrapper's exclusions: tweakers, FML's launch code) has to be added to the loader itself; and the
 * loader's class path may put an old ASM first (Essential puts its asm-debug-all 5.2 ahead of everything),
 * which Lich's ASM has to win over, as it does in a first launch.
 */
final class Relaunch {
    private Relaunch() {
    }

    /** The class loader that relaunched the game, or {@code null} for a first launch. */
    static ClassLoader relauncher() {
        ClassLoader own = Relaunch.class.getClassLoader();
        // The class is compared by name: a relaunch loader defines its own LichSystemClassLoader class.
        return own.getClass().getName().equals(LichSystemClassLoader.class.getName()) ? null : own;
    }

    /** Lich's jars and the libraries it ships among the relaunch loader's URLs, to hide them from mod discovery. */
    static List<URL> lichJars(ClassLoader relauncher) {
        List<URL> lich = new ArrayList<>();
        if (relauncher instanceof URLClassLoader urls) {
            for (URL url : urls.getURLs()) {
                try {
                    // The first launch already chose between LWJGL 2 and 3: whichever LWJGL 3 jars are left are in use.
                    LichSystemClassLoader.Kind kind = url.getProtocol().equals("file")
                            ? LichSystemClassLoader.classify(Path.of(url.toURI())) : LichSystemClassLoader.Kind.OTHER;
                    if (kind == LichSystemClassLoader.Kind.LICH || kind == LichSystemClassLoader.Kind.LWJGL3
                            || kind == LichSystemClassLoader.Kind.LWJGL2_API) {
                        lich.add(url);
                    }
                } catch (URISyntaxException | RuntimeException notAJar) {
                    // Not a local jar: not one of Lich's.
                }
            }
        }
        return lich;
    }

    /** The compat pass for the relaunch loader's classes, once boot has built it. */
    private static volatile BiFunction<String, byte[], byte[]> compat;

    /**
     * Hooks into what the relaunch loader runs on the classes it defines: from now on its ASM classes come
     * from Lich's jars, and once {@link #useCompat} is called, the compat pass runs on everything else.
     * Boot calls this first thing, before any ASM class is loaded: the JVM loads some while verifying
     * boot's own classes. Essential's {@code RelaunchClassLoader} keeps a
     * {@code BiFunction<String, byte[], byte[]>} (binary name, bytes) → bytes for its own patches (it
     * already strips FMLTweaker's {@code setSecurityManager}); Lich's run after it. Returns whether the
     * loader had such a hook.
     */
    static boolean hook(ClassLoader relauncher, Logger log) {
        BiFunction<String, byte[], byte[]> lichAsm = lichAsm(lichJars(relauncher));
        for (Class<?> type = relauncher.getClass(); type != null && type != ClassLoader.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != BiFunction.class) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    BiFunction<String, byte[], byte[]> own = (BiFunction<String, byte[], byte[]>) field.get(relauncher);
                    field.set(relauncher, (BiFunction<String, byte[], byte[]>) (name, bytes) -> {
                        if (name.startsWith("org.objectweb.asm.")) {
                            return lichAsm.apply(name, bytes);
                        }
                        byte[] patched = own == null ? bytes : own.apply(name, bytes);
                        BiFunction<String, byte[], byte[]> pass = compat;
                        return pass == null || skipsCompat(name) ? patched : pass.apply(name, patched);
                    });
                    log.info("Relaunched by {}: Lich's ASM and compat pass run in its {}.{}",
                            relauncher.getClass().getName(), type.getSimpleName(), field.getName());
                    return true;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    log.warn("Can't add the compat pass to {}.{}", type.getName(), field.getName(), e);
                    return false;
                }
            }
        }
        log.warn("Relaunched by {}, which has no transformer hook: the classes it defines outside "
                + "LaunchClassLoader run without Lich's compat pass", relauncher.getClass().getName());
        return false;
    }

    /** The compat pass for the classes the relaunch loader defines from now on. */
    static void useCompat(BiFunction<String, byte[], byte[]> pass) {
        compat = pass;
    }

    /**
     * (binary name, bytes) → the class's bytes from Lich's jars (ASM 9 and {@code compat-asm}'s copies of
     * the ASM 5 classes it dropped) if they have it, else {@code bytes}.
     */
    private static BiFunction<String, byte[], byte[]> lichAsm(List<URL> lichJars) {
        URLClassLoader ours = new URLClassLoader(lichJars.toArray(URL[]::new), null);
        return (name, bytes) -> {
            URL url = ours.findResource(name.replace('.', '/') + ".class");
            if (url == null) {
                return bytes;
            }
            try (InputStream in = url.openStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("Can't read " + url, e);
            }
        };
    }

    /**
     * As {@link LichSystemClassLoader}, which tells by the jar: Lich's own classes and ASM never need it, nor do
     * LWJGL 3 and Lich's LWJGL 2 classes on it, which are all of {@code org.lwjgl} but LWJGL 2's lwjgl_util.
     */
    private static boolean skipsCompat(String name) {
        return name.startsWith("org.objectweb.asm.") || name.startsWith("dev.lich.")
                || name.startsWith("org.lwjgl.") && !name.startsWith("org.lwjgl.util.");
    }
}
