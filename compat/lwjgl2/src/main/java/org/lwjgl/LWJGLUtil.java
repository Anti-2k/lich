package org.lwjgl;

import org.lwjgl.system.Platform;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LWJGL 2's platform and debugging helpers, on top of LWJGL 3's {@link Platform}. */
public class LWJGLUtil {
    public static final int PLATFORM_LINUX = 1;
    public static final int PLATFORM_MACOSX = 2;
    public static final int PLATFORM_WINDOWS = 3;
    public static final String PLATFORM_LINUX_NAME = "linux";
    public static final String PLATFORM_MACOSX_NAME = "macosx";
    public static final String PLATFORM_WINDOWS_NAME = "windows";

    /** LWJGL 2's own default window icons. Lich has none: these are fully transparent. */
    public static final ByteBuffer LWJGLIcon16x16 = BufferUtils.createByteBuffer(16 * 16 * 4);
    public static final ByteBuffer LWJGLIcon32x32 = BufferUtils.createByteBuffer(32 * 32 * 4);

    public static final boolean DEBUG = Boolean.getBoolean("org.lwjgl.util.Debug");
    public static final boolean CHECKS = !Boolean.getBoolean("org.lwjgl.util.NoChecks");

    /** Filters the fields {@link #getClassTokens} collects. */
    public interface TokenFilter {
        boolean accept(Field field, int value);
    }

    public LWJGLUtil() {
    }

    public static int getPlatform() {
        return switch (Platform.get()) {
            case WINDOWS -> PLATFORM_WINDOWS;
            case MACOSX -> PLATFORM_MACOSX;
            // LWJGL 2 ran FreeBSD as Linux too.
            default -> PLATFORM_LINUX;
        };
    }

    public static String getPlatformName() {
        return switch (getPlatform()) {
            case PLATFORM_WINDOWS -> PLATFORM_WINDOWS_NAME;
            case PLATFORM_MACOSX -> PLATFORM_MACOSX_NAME;
            default -> PLATFORM_LINUX_NAME;
        };
    }

    public static String mapLibraryName(String name) {
        return System.mapLibraryName(name);
    }

    public static String[] getLibraryPaths(String libname, String platformLibName, ClassLoader classloader) {
        return getLibraryPaths(libname, new String[]{platformLibName}, classloader);
    }

    /** Where LWJGL 2 would look for a native library: {@code org.lwjgl.librarypath}, then {@code java.library.path}. */
    public static String[] getLibraryPaths(String libname, String[] platformLibNames, ClassLoader classloader) {
        List<String> paths = new ArrayList<>();
        for (String name : platformLibNames) {
            String override = System.getProperty("org.lwjgl.librarypath");
            if (override != null) {
                paths.add(override + File.separator + name);
            }
            String libraryPath = System.getProperty("java.library.path", "");
            for (String dir : libraryPath.split(File.pathSeparator)) {
                if (!dir.isEmpty()) {
                    paths.add(dir + File.separator + name);
                }
            }
            paths.add(name);
        }
        return paths.toArray(new String[0]);
    }

    public static boolean getPrivilegedBoolean(String propertyName) {
        return Boolean.getBoolean(propertyName);
    }

    public static Integer getPrivilegedInteger(String propertyName) {
        return Integer.getInteger(propertyName);
    }

    public static Integer getPrivilegedInteger(String propertyName, int defaultValue) {
        return Integer.getInteger(propertyName, defaultValue);
    }

    public static void log(CharSequence msg) {
        if (DEBUG) {
            System.err.println("[LWJGL] " + msg);
        }
    }

    public static boolean isMacOSXEqualsOrBetterThan(int majorRequired, int minorRequired) {
        if (getPlatform() != PLATFORM_MACOSX) {
            return false;
        }
        String[] version = System.getProperty("os.version", "0").split("\\.");
        int major = parse(version, 0);
        int minor = parse(version, 1);
        return major > majorRequired || major == majorRequired && minor >= minorRequired;
    }

    private static int parse(String[] parts, int index) {
        try {
            return index < parts.length ? Integer.parseInt(parts[index]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static Map<Integer, String> getClassTokens(TokenFilter filter, Map<Integer, String> target, Class<?>... tokenClasses) {
        return getClassTokens(filter, target, Arrays.asList(tokenClasses));
    }

    /** The {@code public static final int} constants of {@code tokenClasses}, by value. */
    @SuppressWarnings("rawtypes")
    public static Map<Integer, String> getClassTokens(TokenFilter filter, Map<Integer, String> target, Iterable<Class> tokenClasses) {
        Map<Integer, String> tokens = target == null ? new HashMap<>() : target;
        int wanted = Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL;
        for (Class<?> type : tokenClasses) {
            for (Field field : type.getFields()) {
                if ((field.getModifiers() & wanted) == wanted && field.getType() == int.class) {
                    try {
                        int value = field.getInt(null);
                        if (filter == null || filter.accept(field, value)) {
                            tokens.put(value, tokens.containsKey(value) ? tokens.get(value) + "|" + field.getName() : field.getName());
                        }
                    } catch (IllegalAccessException ignored) {
                        // Public fields only.
                    }
                }
            }
        }
        return tokens;
    }

    public static String toHexString(int i) {
        return "0x" + Integer.toHexString(i).toUpperCase();
    }
}
