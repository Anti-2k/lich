package dev.lich.api.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Key bindings of Lich mods, listed in Options → Controls and saved in options.txt like the game's own. */
public final class KeyBindings {
    private KeyBindings() {
    }

    /**
     * Adds {@code binding} to the game's options, with the key the player last chose for it. Call it from an
     * {@code init} or {@code client} entrypoint or later: the options must exist. Registering the same
     * binding twice does nothing.
     */
    public static synchronized void register(KeyBinding binding) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        if (settings == null) {
            throw new IllegalStateException("Key bindings can only be registered once the game's options exist (from an init entrypoint on)");
        }
        if (Arrays.asList(settings.keyBindings).contains(binding)) {
            return;
        }
        settings.keyBindings = Arrays.copyOf(settings.keyBindings, settings.keyBindings.length + 1);
        settings.keyBindings[settings.keyBindings.length - 1] = binding;
        // The options were read before this binding existed, so its saved key is applied here.
        Integer saved = savedKey(new File(Minecraft.getMinecraft().mcDataDir, "options.txt"), binding.getKeyDescription());
        if (saved != null) {
            binding.setKeyCode(saved);
        }
        KeyBinding.resetKeyBindingArrayAndHash();
    }

    /** The key code saved for a binding ({@code key_<description>:<code>}), or {@code null}. */
    private static Integer savedKey(File options, String description) {
        if (!options.isFile()) {
            return null;
        }
        String prefix = "key_" + description + ":";
        try (BufferedReader reader = Files.newBufferedReader(options.toPath(), StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith(prefix)) {
                    try {
                        return Integer.parseInt(line.substring(prefix.length()).strip());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }
}
