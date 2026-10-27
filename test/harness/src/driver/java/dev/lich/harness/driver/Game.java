package dev.lich.harness.driver;

import net.minecraft.launchwrapper.Launch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * The running game, reached through reflection on its SRG names: the driver is loaded by the system class loader and
 * the game by LaunchClassLoader, and the same names hold in 1.7.10 and 1.8.9 (checked against MCP's tables for both).
 * Anything that changes the game runs on the client thread, through the game's own task queue.
 */
final class Game {
    private final ClassLoader loader;
    private final Object minecraft;

    private Game(ClassLoader loader, Object minecraft) {
        this.loader = loader;
        this.minecraft = minecraft;
    }

    /**
     * Waits until the game has started and shows its first screen. It only asks for a class the game already loaded
     * ({@code findLoadedClass}): loading {@code Minecraft} from here, before FML has registered its transformers,
     * would define it untransformed. Not through {@code cachedClasses}: FoamFix swaps that map for another one.
     * <p>
     * Which LaunchClassLoader holds the game isn't fixed either: Essential relaunches the whole launch, Lich's boot
     * included, in a class loader of its own, so the game ends up in a LaunchClassLoader that a second copy of
     * {@code Launch} knows about, not the one this driver sees. LaunchWrapper makes its class loader the game
     * thread's context class loader, so every live thread's is a candidate, besides {@code Launch.classLoader}.
     */
    static Game await() throws Exception {
        Method findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        findLoadedClass.setAccessible(true);
        Map<Class<?>, Long> firstSeen = new HashMap<>();
        while (true) {
            for (ClassLoader loader : candidateLoaders()) {
                Class<?> minecraftClass = (Class<?>) findLoadedClass.invoke(loader, "net.minecraft.client.Minecraft");
                if (minecraftClass == null) {
                    continue;
                }
                // Defined isn't initialized yet: give the client thread time to construct it itself.
                Long seen = firstSeen.get(minecraftClass);
                if (seen == null) {
                    firstSeen.put(minecraftClass, System.nanoTime());
                    continue;
                }
                if (System.nanoTime() - seen < 5_000_000_000L) {
                    continue;
                }
                Object minecraft = field(minecraftClass, "field_71432_P").get(null); // theMinecraft
                if (minecraft != null && field(minecraftClass, "field_71462_r").get(minecraft) != null) { // currentScreen
                    return new Game(minecraftClass.getClassLoader(), minecraft);
                }
            }
            Thread.sleep(1000);
        }
    }

    /** {@code Launch.classLoader} and every live thread's context class loader, each once. */
    private static Set<ClassLoader> candidateLoaders() {
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        if (Launch.classLoader != null) {
            loaders.add(Launch.classLoader);
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            ClassLoader loader = thread.getContextClassLoader();
            if (loader != null) {
                loaders.add(loader);
            }
        }
        return loaders;
    }

    String screen() throws Exception {
        Object screen = get(minecraft, "field_71462_r");
        return screen == null ? "none" : screen.getClass().getName();
    }

    /** Creates a world in creative with cheats on, and waits until it's loaded. */
    void createWorld(String folder, String name, long seed, String worldTypeName) throws Exception {
        Class<?> worldType = type("net.minecraft.world.WorldType");
        Object type = worldType.getMethod("func_77130_a", String.class).invoke(null, worldTypeName); // parseWorldType
        if (type == null) {
            throw new IllegalStateException("No world type named " + worldTypeName);
        }
        Class<?> settingsClass = type("net.minecraft.world.WorldSettings");
        Class<?> gameType = type("net.minecraft.world.WorldSettings$GameType");
        Constructor<?> constructor = settingsClass.getConstructor(long.class, gameType, boolean.class, boolean.class, worldType);
        Object settings = constructor.newInstance(seed, enumConstant(gameType, "CREATIVE"), true, false, type);
        settingsClass.getMethod("func_77166_b").invoke(settings); // enableCommands
        launch(folder, name, settings);
    }

    /** Opens an existing world, as the world list does. */
    void loadWorld(String folder, String name) throws Exception {
        launch(folder, name, null);
    }

    private void launch(final String folder, final String name, final Object settings) throws Exception {
        final Method launch = method(minecraft.getClass(), "func_71371_a", // launchIntegratedServer
                String.class, String.class, type("net.minecraft.world.WorldSettings"));
        onClient(new Task() {
            @Override
            public void run() throws Exception {
                launch.invoke(minecraft, folder, name, settings);
            }
        });
    }

    /** Whether the player is in a world that the integrated server is still running. */
    boolean inWorld() throws Exception {
        if (get(minecraft, "field_71441_e") == null || get(minecraft, "field_71439_g") == null) { // theWorld, thePlayer
            return false;
        }
        Object server = server();
        return server != null && (Boolean) method(server.getClass(), "func_71278_l").invoke(server); // isServerRunning
    }

    Object server() throws Exception {
        return method(minecraft.getClass(), "func_71401_C").invoke(minecraft); // getIntegratedServer
    }

    /** Lets every player use commands, as opening the world to LAN with cheats on does: a pack's own map may not. */
    void allowCommands() throws Exception {
        Object server = server();
        Object players = method(server.getClass(), "func_71203_ab").invoke(server); // getConfigurationManager
        method(players.getClass(), "func_72387_b", boolean.class).invoke(players, true); // setCommandsAllowedForAll
    }

    double[] playerXZ() throws Exception {
        Object player = get(minecraft, "field_71439_g");
        return new double[]{(Double) get(player, "field_70165_t"), (Double) get(player, "field_70161_v")}; // posX, posZ
    }

    /** Sends a chat line as the player, so a command runs on the integrated server the way a typed one does. */
    void chat(final String message) throws Exception {
        onClient(new Task() {
            @Override
            public void run() throws Exception {
                Object player = get(minecraft, "field_71439_g");
                method(player.getClass(), "func_71165_d", String.class).invoke(player, message); // sendChatMessage
            }
        });
    }

    /** What the pause menu's "Save and Quit to Title" does. Returns the server, which then saves and stops. */
    Object saveAndQuit() throws Exception {
        final Object server = server();
        final Class<?> worldClient = type("net.minecraft.client.multiplayer.WorldClient");
        final Class<?> guiScreen = type("net.minecraft.client.gui.GuiScreen");
        final Class<?> mainMenu = type("net.minecraft.client.gui.GuiMainMenu");
        onClient(new Task() {
            @Override
            public void run() throws Exception {
                Object world = get(minecraft, "field_71441_e");
                if (world != null) {
                    method(world.getClass(), "func_72882_A").invoke(world); // sendQuittingDisconnectingPacket
                }
                method(minecraft.getClass(), "func_71403_a", worldClient).invoke(minecraft, (Object) null); // loadWorld
                method(minecraft.getClass(), "func_147108_a", guiScreen) // displayGuiScreen
                        .invoke(minecraft, mainMenu.getConstructor().newInstance());
            }
        });
        return server;
    }

    static boolean stopped(Object server) throws Exception {
        return (Boolean) method(server.getClass(), "func_71241_aa").invoke(server); // isServerStopped
    }

    /** The overworld's loaded chunks, entities and tile entities, and the server's mean tick time. */
    String stats() throws Exception {
        Object server = server();
        if (server == null) {
            return "server=none";
        }
        Object overworld = ((Object[]) get(server, "field_71305_c"))[0]; // worldServers
        Object chunks = get(overworld, "field_73059_b"); // theChunkProviderServer
        int loaded = (Integer) method(chunks.getClass(), "func_73152_e").invoke(chunks); // getLoadedChunkCount
        int entities = ((List<?>) get(overworld, "field_72996_f")).size(); // loadedEntityList
        int tileEntities = ((List<?>) get(overworld, "field_147482_g")).size(); // loadedTileEntityList
        long[] tickTimes = (long[]) get(server, "field_71311_j"); // tickTimeArray, nanoseconds
        long sum = 0;
        for (long t : tickTimes) {
            sum += t;
        }
        return String.format(Locale.ROOT, "chunks=%d entities=%d tileEntities=%d mspt=%.1f",
                loaded, entities, tileEntities, sum / (double) tickTimes.length / 1e6);
    }

    /** Lets the game shut down the way its window's close button does. */
    void shutdown() throws Exception {
        method(minecraft.getClass(), "func_71400_g").invoke(minecraft);
    }

    private interface Task {
        void run() throws Exception;
    }

    /** Runs {@code task} on the client thread and waits for it ({@code func_152344_a}: the scheduled-task queue). */
    private void onClient(final Task task) throws Exception {
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                } catch (InvocationTargetException e) {
                    throw new TaskFailed(e.getCause());
                } catch (Exception e) {
                    throw new TaskFailed(e);
                }
            }
        };
        Future<?> future = (Future<?>) method(minecraft.getClass(), "func_152344_a", Runnable.class).invoke(minecraft, runnable);
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof TaskFailed ? e.getCause().getCause() : e.getCause();
            throw new IllegalStateException("Failed on the client thread: " + cause, cause);
        }
    }

    private static final class TaskFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TaskFailed(Throwable cause) {
            super(cause);
        }
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    /** A field declared by {@code type} or one of its superclasses (mods subclass the game's classes). */
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
