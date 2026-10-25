package dev.lich.loader;

import java.io.Serial;
import java.util.List;

/** Lich mods can't load as installed; the message lists every problem, written for the player. */
public final class ModLoadingException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public ModLoadingException(List<String> problems) {
        super("Lich can't load the installed mods:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
