# Lifecycle and entrypoints

A Lich mod's code starts in an **entrypoint**: a class named in `lich.mod.json` that implements one of three small
interfaces in `dev.lich.api.entrypoint`. The three kinds run at three well-defined moments of startup. A fourth moment,
shutdown, is an event.

```
JVM start
  └─ Lich boots, finds mods, builds the class loader, runs every tweaker
       ├─ preLaunch     no game class has loaded yet
       └─ the game's main() runs
            └─ Minecraft.startGame
                 ├─ (window, options, resource manager exist)
                 ├─ init     where Forge gives its mods pre-init
                 ├─ ... resources load, Forge mods initialise ...
                 └─ client   the game has started; the title screen is next
  ...
  └─ shutdown   LifecycleEvents.SHUTDOWN, when the game quits normally
```

## The three entrypoints

| Kind | Interface | Method | Runs |
|---|---|---|---|
| `preLaunch` | `PreLaunchEntrypoint` | `onPreLaunch()` | Once every tweaker has run, right before the game's `main` method |
| `init` | `InitEntrypoint` | `onInit()` | While `Minecraft.startGame` runs, once the window, the options and the resource manager exist and **before** resources first load |
| `client` | `ClientEntrypoint` | `onClientReady()` | When `startGame` returns: resources loaded, every Forge mod initialised, right before the first frame of the title screen |

All three run on the thread that starts the game (the client thread for `init` and `client`). For each kind, Lich runs
the mods in [load order](lich-mod-json.md#resolution-and-load-order), and a mod's classes in the order listed in its
descriptor.

### `preLaunch`

For things that must happen before the game's classes exist: reading a config file, setting a system property,
deciding which of your Mixins to enable.

**Do not touch game classes here, and avoid loading classes that mention them.** Nothing of the game has been loaded
or transformed yet; loading a class early can load it before it is meant to be. Reading files and plain Java is fine.
If in doubt, move the work to `init`.

Not much else of Lich is ready either: the safest assumption is that only `Lich.version()`, `Lich.mods()`,
`Lich.minecraftVersion()` and `Lich.isForgePresent()` are meaningful.

### `init`

The place to **register listeners and key bindings**. At this point:

- the game window exists and `Minecraft.getMinecraft()` returns the game instance;
- `gameSettings` (the options) are loaded, so `KeyBindings.register` works;
- no resources (textures, sounds, language files) have loaded, so do not ask for them yet.

Under Forge, this is the moment Forge gives its own mods their pre-initialisation.

Registering for an event whose hook your descriptor does not list throws an `IllegalStateException` that names your
mod and the missing hook. See [Events and hooks](events-and-hooks.md#hooks).

### `client`

The game is up. Resources are loaded, Forge mods (if any) are initialised. Use it for work that needs the finished
game: looking up another mod's registered content, or reading something that only exists after resource loading.

## Writing one

```java
public final class MyMod implements InitEntrypoint, ClientEntrypoint {
    @Override public void onInit()        { /* listeners, key bindings */ }
    @Override public void onClientReady() { /* needs the finished game */ }
}
```

```json
"entrypoints": {
  "init":   ["dev.example.MyMod"],
  "client": ["dev.example.MyMod"]
}
```

Rules, all enforced:

- The class must be public and have a **public no-argument constructor**. Otherwise the launch stops with
  "Mod X's init entrypoint C can't be created (it needs a public no-argument constructor)".
- It must implement the interface for the kind it is listed under ("doesn't implement
  dev.lich.api.entrypoint.InitEntrypoint").
- **One instance per class**, shared across kinds, so a class listed under `init` and `client` keeps its fields from
  one to the next. The instance is created the first time that class is needed.
- An exception thrown from an entrypoint ends the launch: Lich rethrows it as "Mod X failed in its init entrypoint C"
  with your exception as the cause. Catch what you can recover from.

## Shutdown

```java
LifecycleEvents.SHUTDOWN.register(shutdown -> saveMyState());
```

`LifecycleEvents.SHUTDOWN` fires when the game quits normally (from the menu, or the window is closed), on the client
thread, **before** the window and the sound system are released. It does not fire on a crash or a forced exit
(killed process, `System.exit`), so do not keep data that only this event saves. Unlike most events it has no hook to
list: every Lich mod gets it.

## Threads

- Entrypoints and every Lich event run on the **client thread**. Listeners run on whichever thread fires the event,
  and Lich fires all of its events from the client thread.
- `Event.register` and `unregister` can be called from any thread, also while the event is firing. A listener
  registered during a firing is not called until the next one.
- The integrated server runs on its own thread. Do not touch the world from a listener without knowing which side you
  are on.

## What is available to a mod

Your classes load in the game's class loader (LaunchClassLoader), next to the game and Forge's mods, and see what the game
sees:

- the game's libraries: log4j 2.0-beta9 (`org.apache.logging.log4j`), Gson 2.2.4, Guava, commons-lang3, ...,
  the **versions the game ships**, not Java 25-era ones;
- LWJGL 2's API (`org.lwjgl.input.Keyboard`, `GL11`, ...) running on LWJGL 3; see
  [DOCS.md §2.5](https://github.com/Anti-2k/lich/blob/main/DOCS.md#25-lwjgl). Your code is compiled against the game's LWJGL 2 API. Lich translates the
  calls;
- Lich's API (`dev.lich.api.*`);
- Mixin and MixinExtras, **only for Mixin classes** (see [Mixins](mixins.md));
- resources in your own jar, through your class's own class loader: `MyMod.class.getResourceAsStream("/data.json")`.

Lich offers no config or data-folder API yet. To keep files, use the game directory
(`Minecraft.getMinecraft().mcDataDir`, and `config/` inside it by convention).

## Errors in the log

| Message | Cause |
|---|---|
| `Mod X failed in its init entrypoint C` | Your entrypoint threw; the cause follows |
| `Mod X's init entrypoint C can't be created (it needs a public no-argument constructor)` | Class is not public, has no public no-arg constructor, or failed to load (see the cause) |
| `Mod X's init entrypoint C doesn't implement dev.lich.api.entrypoint.InitEntrypoint` | Wrong interface for the kind |
| `... has an unknown kind of entrypoint "x" ...` (descriptor error) | Only `preLaunch`, `init` and `client` exist |

More in [Troubleshooting](troubleshooting.md).
