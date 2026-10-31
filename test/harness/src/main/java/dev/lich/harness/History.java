package dev.lich.harness;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Every pack run, one JSON line each in {@code run/harness/history.jsonl}: when, which Lich commit, the verdict and how
 * long it took. The summary uses it to say what changed since a pack's previous run.
 */
final class History {
    private static final Gson GSON = new Gson();

    /** One pack run. {@code lichSeconds} is the Lich run alone; {@code baselineReused} whether Forge ran too. */
    record Entry(String time, String pack, String lich, String verdict, String reason, long lichSeconds,
                 boolean baselineReused) {
    }

    private final Path file;

    History(Path file) {
        this.file = file;
    }

    /** The pack's latest entry, or null. */
    Entry last(String pack) throws IOException {
        Entry last = null;
        for (Entry entry : read()) {
            if (entry.pack().equals(pack)) {
                last = entry;
            }
        }
        return last;
    }

    void add(Entry entry) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(entry) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private List<Entry> read() throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            try {
                Entry entry = GSON.fromJson(line, Entry.class);
                if (entry != null && entry.pack() != null) {
                    entries.add(entry);
                }
            } catch (JsonParseException ignored) {
                // A line cut short by a killed run.
            }
        }
        return entries;
    }
}
