package dev.lich.api.impl;

import dev.lich.api.entrypoint.ClientEntrypoint;
import dev.lich.api.entrypoint.InitEntrypoint;
import dev.lich.api.entrypoint.PreLaunchEntrypoint;
import dev.lich.loader.LichLoader;
import dev.lich.loader.LichMod;

import java.util.HashMap;
import java.util.Map;

/**
 * Runs the mods' entrypoints of one kind, mod by mod in load order. One instance per entrypoint class,
 * so a class that is both a mod's {@code init} and its {@code client} entrypoint keeps its state between
 * them. Boot runs {@code preLaunch}; Lich's lifecycle hook in the game runs {@code init} and {@code client}.
 * Not API: mods don't call this.
 */
public final class Entrypoints {
    private static final Map<String, Object> INSTANCES = new HashMap<>();

    private Entrypoints() {
    }

    public static synchronized void run(String kind) {
        for (LichMod mod : LichLoader.mods()) {
            for (String className : mod.descriptor().entrypoints(kind)) {
                Object instance = instance(mod, kind, className);
                try {
                    switch (kind) {
                        case "preLaunch" -> ((PreLaunchEntrypoint) instance).onPreLaunch();
                        case "init" -> ((InitEntrypoint) instance).onInit();
                        case "client" -> ((ClientEntrypoint) instance).onClientReady();
                        default -> throw new IllegalArgumentException("Unknown entrypoint kind " + kind);
                    }
                } catch (RuntimeException | LinkageError e) {
                    throw new IllegalStateException("Mod " + mod.id() + " failed in its " + kind + " entrypoint " + className, e);
                }
            }
        }
    }

    private static Object instance(LichMod mod, String kind, String className) {
        Object instance = INSTANCES.get(className);
        if (instance == null) {
            try {
                Class<?> type = Class.forName(className, true, Entrypoints.class.getClassLoader());
                instance = type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException("Mod " + mod.id() + "'s " + kind + " entrypoint " + className
                        + " can't be created (it needs a public no-argument constructor)", e);
            }
            INSTANCES.put(className, instance);
        }
        Class<?> expected = switch (kind) {
            case "preLaunch" -> PreLaunchEntrypoint.class;
            case "init" -> InitEntrypoint.class;
            default -> ClientEntrypoint.class;
        };
        if (!expected.isInstance(instance)) {
            throw new IllegalStateException("Mod " + mod.id() + "'s " + kind + " entrypoint " + className
                    + " doesn't implement " + expected.getName());
        }
        return instance;
    }
}
