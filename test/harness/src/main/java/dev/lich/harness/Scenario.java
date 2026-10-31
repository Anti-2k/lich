package dev.lich.harness;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every profile of a pack plays (DOCS.md §7), as steps the driver takes in order:
 * <ol>
 *     <li>{@code boot}: the game starts and its first screen settles;</li>
 *     <li>{@code mods}: FML's mod list, every mod selected and every config screen opened;</li>
 *     <li>{@code server}: joins the local vanilla test server, stays 15 s and leaves;</li>
 *     <li>{@code world}: creates a creative world with a fixed seed, or opens the map the pack ships;</li>
 *     <li>{@code spawn}: until the chunks around spawn have loaded;</li>
 *     <li>{@code tour}: teleports to 8 points 1000 blocks out, each until its new chunks have generated;</li>
 *     <li>{@code inventory}: the creative inventory, every tab, every item drawn once;</li>
 *     <li>{@code gallery} (packs that ask for it): one of every block placed, kept through the save;</li>
 *     <li>{@code hold}: stays a while, so machines, mobs and timers run;</li>
 *     <li>{@code save}: save and quit, and the world's files are there;</li>
 *     <li>{@code reload}: opens the world again (the gallery's tile entities must come back), save and quit;</li>
 *     <li>{@code exit}: the game shuts down as its close button does.</li>
 * </ol>
 * {@code until} cuts the list after a step, for a quick look ({@code boot} only checks that the pack starts).
 */
record Scenario(List<String> steps, int holdSeconds, boolean server, boolean gallery) {
    static final List<String> ALL = List.of("boot", "mods", "server", "world", "spawn", "tour", "inventory", "gallery",
            "hold", "save", "reload", "exit");
    /** Both profiles generate the same world. */
    static final long SEED = 7_350_118_032L;
    static final int SERVER_STAY_SECONDS = 15;

    static Scenario of(Pack pack, String until, int holdSeconds, boolean server, Boolean gallery) {
        boolean withGallery = gallery != null ? gallery : pack.gallery();
        if (until != null && !ALL.contains(until)) {
            throw new IllegalArgumentException("Unknown step " + until + "; steps: " + String.join(", ", ALL));
        }
        List<String> steps = new ArrayList<>();
        for (String step : ALL) {
            if (step.equals("server") && !server || step.equals("gallery") && !withGallery) {
                continue;
            }
            steps.add(step);
            if (step.equals(until)) {
                break;
            }
        }
        if (!steps.getLast().equals("exit")) {
            steps.add("exit");
        }
        return new Scenario(List.copyOf(steps), holdSeconds, server, withGallery);
    }

    /** The steps the driver plays itself: all but {@code exit}, which the harness sees through. */
    List<String> driverSteps() {
        return steps.subList(0, steps.size() - 1);
    }

    /** The driver's settings, as {@code -D} properties. */
    Map<String, String> properties(Pack pack, int port, int serverPort) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("lich.harness.port", Integer.toString(port));
        p.put("lich.harness.steps", String.join(",", driverSteps()));
        p.put("lich.harness.seed", Long.toString(SEED));
        p.put("lich.harness.worldType", pack.worldType());
        p.put("lich.harness.hold", Integer.toString(holdSeconds));
        p.put("lich.harness.serverStay", Integer.toString(SERVER_STAY_SECONDS));
        if (server) {
            p.put("lich.harness.server", "127.0.0.1:" + serverPort);
        }
        if (pack.save() != null) {
            p.put("lich.harness.save", pack.save());
        }
        if (!pack.galleryExclude().isEmpty()) {
            p.put("lich.harness.galleryExclude", String.join(",", pack.galleryExclude()));
        }
        return p;
    }

    /**
     * What a step may take before the run counts as stalled. The defaults fit the biggest packs (GT New Horizons
     * takes 2.5 min to its menu); with a baseline, three times what the baseline took, plus a minute, if that's less.
     */
    Duration deadline(String step, RunResult baseline) {
        long seconds = switch (step) {
            case "boot" -> 900;
            case "mods", "inventory", "gallery", "reload", "tour" -> 900;
            case "server", "spawn" -> 240;
            case "world", "save" -> 420;
            case "hold" -> holdSeconds + 180L;
            case "exit" -> 30;
            default -> 600;
        };
        RunResult.Step before = baseline == null || step.equals("exit") ? null : baseline.step(step);
        if (before != null && before.status() == RunResult.Status.PASSED) {
            seconds = Math.min(seconds, Math.max(120, 3 * before.seconds() + 60));
        }
        return Duration.ofSeconds(seconds);
    }

    /** Everything but the steps that a baseline depends on: a baseline that played more steps still compares. */
    String key() {
        return "seed=" + SEED + " hold=" + holdSeconds + " serverStay=" + SERVER_STAY_SECONDS;
    }
}
