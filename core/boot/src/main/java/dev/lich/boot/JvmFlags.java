package dev.lich.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The JVM arguments Lich needs ({@code jvm-flags.txt}, which the profile generator also reads) and a
 * check for which ones this JVM is missing. Each kind of flag is checked by its effect where Java
 * exposes it, so equivalent spellings ({@code --add-opens x} and {@code --add-opens=x}) both count.
 */
final class JvmFlags {
    private static final String RESOURCE = "jvm-flags.txt";

    private JvmFlags() {
    }

    static List<String> required() {
        try (InputStream in = JvmFlags.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the Lich boot jar");
            }
            List<String> flags = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    flags.add(line);
                }
            }
            return flags;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> missing(List<String> flags) {
        List<String> missing = new ArrayList<>();
        for (String flag : flags) {
            if (!present(flag)) {
                missing.add(flag);
            }
        }
        return missing;
    }

    static String explain(List<String> missing) {
        StringBuilder message = new StringBuilder("Lich can't start: these JVM arguments are missing:\n");
        missing.forEach(flag -> message.append("  ").append(flag).append('\n'));
        message.append("Launch with the Lich version profile, which sets them, or add them to your launcher's JVM arguments.\n");
        message.append("To start anyway (things will break), add -Dlich.ignoreMissingFlags=true.");
        return message.toString();
    }

    private static boolean present(String flag) {
        int equals = flag.indexOf('=');
        String option = equals < 0 ? flag : flag.substring(0, equals);
        String value = equals < 0 ? "" : flag.substring(equals + 1);
        Module unnamed = JvmFlags.class.getModule();
        return switch (option) {
            case "--add-opens", "--add-exports" -> {
                // module/package=target
                int slash = value.indexOf('/');
                int targetAt = value.indexOf('=');
                Optional<Module> module = ModuleLayer.boot().findModule(value.substring(0, slash));
                String pkg = value.substring(slash + 1, targetAt);
                Optional<Module> target = target(value.substring(targetAt + 1), unnamed);
                yield module.isPresent() && target.isPresent() && (option.equals("--add-opens")
                        ? module.get().isOpen(pkg, target.get())
                        : module.get().isExported(pkg, target.get()));
            }
            case "--add-modules" -> ModuleLayer.boot().findModule(value).isPresent();
            case "--enable-native-access" -> unnamed.isNativeAccessEnabled();
            // No API reports these; fall back to the literal arguments the JVM was started with.
            default -> ManagementFactory.getRuntimeMXBean().getInputArguments().contains(flag);
        };
    }

    private static Optional<Module> target(String name, Module unnamed) {
        return name.equals("ALL-UNNAMED") ? Optional.of(unnamed) : ModuleLayer.boot().findModule(name);
    }
}
