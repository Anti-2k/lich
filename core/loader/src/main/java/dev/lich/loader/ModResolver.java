package dev.lich.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks that the discovered mods fit this game and each other, and puts them in load order: a mod after
 * the mods it depends on, otherwise by id. Every problem is collected, so the player sees them all at
 * once instead of fixing one per launch.
 */
final class ModResolver {
    /** What the mods are checked against. {@code lich} is {@code null} for a development build of Lich. */
    record Environment(String minecraft, boolean forge, Version lich, int java) {
    }

    private ModResolver() {
    }

    static List<LichMod> resolve(List<LichMod> mods, Environment environment) {
        List<String> problems = new ArrayList<>();
        Map<String, LichMod> byId = new TreeMap<>();
        for (LichMod mod : mods) {
            LichMod other = byId.putIfAbsent(mod.id(), mod);
            if (other != null) {
                problems.add("Mod " + mod.id() + " is installed twice: " + other.source().getFileName() + " and " + mod.source().getFileName());
            }
        }

        Map<String, String> providers = new HashMap<>();
        for (LichMod mod : byId.values()) {
            ModDescriptor descriptor = mod.descriptor();
            String name = descriptor.name() + " (" + mod.source().getFileName() + ")";
            if (!descriptor.minecraft().equals(environment.minecraft())) {
                problems.add(name + " is for Minecraft " + descriptor.minecraft() + ", not " + environment.minecraft());
            }
            if (descriptor.forge().equals("required") && !environment.forge()) {
                problems.add(name + " needs Forge");
            }
            if (descriptor.forge().equals("incompatible") && environment.forge()) {
                problems.add(name + " doesn't work with Forge");
            }
            if (descriptor.javaVersion() > environment.java()) {
                problems.add(name + " needs Java " + descriptor.javaVersion() + "; the game runs on Java " + environment.java());
            }
            if (!descriptor.transformers().isEmpty()) {
                problems.add(name + " declares raw transformers, which this version of Lich doesn't support yet");
            }
            for (String subsystem : descriptor.provides()) {
                String other = providers.putIfAbsent(subsystem, descriptor.id());
                if (other != null) {
                    problems.add("Mods " + other + " and " + descriptor.id() + " both replace " + subsystem + "; remove one of them");
                }
            }
            for (Map.Entry<String, VersionRange> dependency : descriptor.depends().entrySet()) {
                String id = dependency.getKey();
                VersionRange range = dependency.getValue();
                switch (id) {
                    case "lich" -> {
                        if (environment.lich() != null && !range.test(environment.lich())) {
                            problems.add(name + " needs Lich " + range + "; this is Lich " + environment.lich());
                        }
                    }
                    case "minecraft" -> {
                        if (!range.test(Version.parse(environment.minecraft()))) {
                            problems.add(name + " needs Minecraft " + range);
                        }
                    }
                    default -> {
                        LichMod installed = byId.get(id);
                        if (installed == null) {
                            problems.add(name + " needs the mod " + id + " " + range + ", which isn't installed");
                        } else if (!range.test(installed.descriptor().version())) {
                            problems.add(name + " needs " + id + " " + range + "; " + installed.descriptor().version() + " is installed");
                        }
                    }
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new ModLoadingException(problems);
        }
        return sorted(byId);
    }

    /** Depth-first, dependencies before dependents, ids in order; a dependency cycle is a loading error. */
    private static List<LichMod> sorted(Map<String, LichMod> byId) {
        List<LichMod> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Map<String, Boolean> visiting = new LinkedHashMap<>();
        for (String id : byId.keySet()) {
            visit(id, byId, done, visiting, order);
        }
        return order;
    }

    private static void visit(String id, Map<String, LichMod> byId, Set<String> done, Map<String, Boolean> visiting, List<LichMod> order) {
        if (done.contains(id) || !byId.containsKey(id)) {
            return;
        }
        if (visiting.containsKey(id)) {
            throw new ModLoadingException(List.of("Mods depend on each other in a circle: "
                    + String.join(" → ", visiting.keySet()) + " → " + id));
        }
        visiting.put(id, true);
        for (String dependency : new TreeMap<>(byId.get(id).descriptor().depends()).keySet()) {
            visit(dependency, byId, done, visiting, order);
        }
        visiting.remove(id);
        done.add(id);
        order.add(byId.get(id));
    }
}
