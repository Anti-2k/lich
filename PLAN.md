# Lich — plan

A client-side runtime and mod loader that runs Minecraft 1.7.10 and 1.8.9 on Java 25. It patches game,
Forge and mod bytecode at load time so that unmodified Java 8 Forge mods work. It also loads its own
mod format, which gets more hooks, optimisations and control over the game.

Items marked **(verify)** come from memory or second-hand sources and need checking against the real
jars before building on them.

## 0. Decisions

| Topic | Decision |
|---|---|
| LaunchWrapper | **Own implementation.** RetroFuturaBootstrap/lwjgl3ify are references for *behavior* only. Don't copy code unless the licenses allow it. |
| Forge mods | Loaded through real Forge, unmodified, in Forge's own format (`@Mod` + `mcmod.info`). Lich patches Forge at load time ("Forge back-compat"). |
| Lich mods | Own format (`lich.mod.json`). Runs **with or without Forge**. First real user: a full client mod. |
| Game versions | 1.7.10 (Forge `10.13.4.1614`) and 1.8.9 (Forge `11.15.1.2318`). Keep the version-specific code isolated so 1.12.2 (also LaunchWrapper-based) can be added later. |
| Server side | **Client only**, plus the integrated singleplayer server that ships in the client jar. No dedicated-server target. Hard requirement: joining public servers must look and behave exactly like a normal client, so no bans or disconnects (§6). |
| Java | 25. Lich code and Lich mods compile to Java 25. Forge mods stay Java 8 bytecode. |

---

## 1. Prior art to study first

| Project | What to take from it |
|---|---|
| **lwjgl3ify** (GTNewHorizons) | Runs Forge 1.7.10 on modern Java with LWJGL 3: the LWJGL 2→3 redirect approach, the JVM flag set, the FML patches. Recommends Java 17/21; Java 25 support **(verify)**. |
| **RetroFuturaBootstrap** (GTNH) | Behavior reference for a LaunchWrapper replacement with Java 9+ classpath discovery. |
| **UniMixins** | How to override older Mixin copies that mods shade. |
| **Hodgepodge** (GTNH) | Java 17+ fixes for closed-source 1.7.10 mods. A list of real-world breakages. |
| **Lunar Client launch args** | Proof that 1.8.9 runs on 17+ without Forge. |
| **Fabric Loader** | Good design reference for the Lich mod format: entrypoints, dependency resolution, access wideners. |

Never redistribute Minecraft or patched Forge binaries. Patch at load time, or on the user's machine.

---

## 2. What breaks when 1.7.10/1.8.9 run on Java 25

### 2.1 Class loading (the main blocker)
- `net.minecraft.launchwrapper.Launch` casts the system class loader to `URLClassLoader`. On Java 9+
  that throws `ClassCastException`, before Forge even starts.
- `LaunchClassLoader` uses a `null` parent (bootstrap only). On 9+ the bootstrap loader can't see
  platform-loader modules (`java.sql`, `jdk.naming.dns`, …), so e.g. Gson's `java.sql.Timestamp` support
  fails. Use `ClassLoader.getPlatformClassLoader()` as the parent.
- Mods reflect into LaunchClassLoader's **private fields** (`cachedClasses`, `invalidClasses`,
  `classLoaderExceptions`, `transformerExceptions`, `resourceCache`, `negativeResourceCache`, …). Lich's
  implementation has to keep these names and types exactly.

### 2.2 Strong encapsulation (JEP 403) and Unsafe
- Netty 4.0, LWJGL 2, Guava and old mods reflect into `java.nio`, `java.lang`, `sun.*`. Everything
  Lich loads is in the unnamed module, so `--add-opens <mod>/<pkg>=ALL-UNNAMED` fixes most of it.
- Java 24+ prints warnings for `sun.misc.Unsafe` memory access (JEP 498). Use
  `--sun-misc-unsafe-memory-access=allow` for now; a future JDK will deny it by default.
- Java 24+ warns on native library loading (JEP 472). Use `--enable-native-access=ALL-UNNAMED`.

### 2.3 Removed JDK features
| Removed | Since | Who uses it | Fix |
|---|---|---|---|
| SecurityManager (`System.setSecurityManager` always throws) | 24 (JEP 486) | `FMLTweaker`'s constructor, both versions (confirmed), some mods | **Done (Phase 3):** the call is redirected to a no-op (`SystemShims`). Later: redirect `System.exit`/`Runtime.exit` in mod code to a Lich handler that does FML's job. |
| `Field.modifiers` reflection hack | 12 | Forge `ObjectHolderRef` (both versions, fatal at startup), `EnumHelper`, many mods ("setFinalStatic") | **Done for the Forge pattern (Phase 3)**, §4.4 |
| `sun.reflect.ReflectionFactory.newFieldAccessor`/`newConstructorAccessor`, `sun.reflect.FieldAccessor`/`ConstructorAccessor` | 9 | `ObjectHolderRef`, `EnumHelper`, mods that copied them | **Done (Phase 3):** Lich look-alikes, §4.4 |
| `java.util.jar.Pack200` | 14 | FML `ClassPatchManager` (`binpatches.pack.lzma`) | **Done (Phase 3):** a Pack200 shim at runtime (§4.5), not install-time pre-patching |
| JAXB `javax.xml.bind` (`DatatypeConverter`) | 11 | Mods doing base64/hex | Bundle jaxb-api, or redirect to `java.util.Base64`/`HexFormat` |
| Nashorn `jdk.nashorn.*` | 15 | Scripting mods | Bundle `org.openjdk.nashorn:nashorn-core`, remap `jdk.nashorn` → `org.openjdk.nashorn` |
| `sun.misc.BASE64Encoder/Decoder` | 9 | Old mods/libs | Redirect to shim |
| `sun.reflect.Reflection.getCallerClass` | 9 | log4j 2.0-beta9 (has a fallback), mods | Shim on `StackWalker` |
| `Thread.stop/suspend/resume` now throw | 20 | Rare mods | Redirect to no-op and log a warning |
| Applet API | JDK 26 (JEP 504) | Old game/launcher classes **(verify)** | Not removed in 25 yet; watch it |

### 2.4 Other runtime issues
- **ASM version (done in Phase 3)**: Forge ships asm-all 5.0.3, which can't parse Java 9+ class files.
  Lich ships ASM 9.8 (asm, -tree, -analysis, -commons, -util) and its system class loader drops
  asm-all 5 from the class path. Differences that mattered, found by running Forge on 25:
  - ASM 9 removed `RemappingClassAdapter`, `RemappingMethodAdapter` and three more `Remapping*`
    classes, which FML's `DeobfuscationTransformer` extends. `compat-asm` carries ASM 5.0.3's own
    copies (BSD). Kept verbatim, so FML's output, e.g. local renumbering, stays as coremods expect.
  - ASM 9's `Type.getType(String)` throws on internal names; ASM 5 returned a method-sort type.
    FML 1.8.9's `EventSubscriptionTransformer` relies on that, and otherwise silently skips every event
    class. Redirected to `Asm5Types.getType`.
  - ASM-5-API visitors throw on Java 9+ attributes ("NestHost requires ASM7"). FML's mod discovery hits
    them in Lich's own jars and ASM 9's `module-info`, so those jars are hidden from discovery
    (`LichSources`). Still open (Phase 4): mods that scan or transform Java 9+ classes, and subclasses
    of `ClassNode`/`MethodNode` calling the no-arg constructors, which ASM 6+ rejects.
  - FML 1.7.10 lets `LaunchClassLoader` define its own copy of ASM (1.8.9 excludes it); the copy
    broke the ASM shims with loader constraint violations. Lich excludes `org.objectweb.asm.` in both.
- **Lich's system class loader (Phase 3)**: tweakers and FML's launch code (`FMLTweaker`,
  `relauncher.*`) are LaunchWrapper class loader exclusions, so they're defined by the system class
  loader, where `LaunchClassLoader`'s transformers never run, and they call removed JDK APIs. Lich
  installs its own system class loader (`-Djava.system.class.loader=dev.lich.boot.LichSystemClassLoader`,
  as RetroFuturaBootstrap does) so the compat pass reaches them too. It's a `URLClassLoader` because FML's
  `CoreModManager` calls `URLClassLoader.addURL` on it for cascading tweakers. It also drops Mojang's
  LaunchWrapper and asm-all 5 from the class path. The JDK then warns that CDS is off for
  application classes; `-Xlog:cds=off` hides that.
- **Mixin**: mods shade Mixin 0.7.x. Provide one modern Mixin (+ MixinExtras) and always serve
  `org.spongepowered.*` from it, whatever is shaded in mod jars.
- **SRV lookup (tested 2026-10-24)**: vanilla `ServerAddress` loads `com.sun.jndi.dns.DnsContextFactory`
  with the game's class loader, then asks JNDI for it. With LaunchWrapper 1.12's bootstrap-only parent,
  that throws `ClassNotFoundException` on Java 9+, and vanilla **silently** falls back to `<host>:25565`.
  So `hypixel.net` (SRV → `mc.hypixel.net`) connects to the wrong host. Lunar's
  `--add-modules jdk.naming.dns --add-exports …` flags don't fix this. With Lich's platform-loader parent
  it works with **no flags**: JNDI finds the factory through `ServiceLoader`. Covered by
  `LaunchClassLoaderTest.platformModulesAreVisible`.
- **`java.version` parsing**: code that expects `1.x` breaks on `25`. Audit FML, Mixin 0.7, LWJGL 2 and
  popular mods.
- **Default charset is UTF-8** (Java 18). Usually harmless.
- **log4j 2.0-beta9** is Log4Shell-vulnerable, and both client jars bundle an unfiltered `log4j2.xml`.
  Mojang's fix for these versions (`client-1.7.xml`, passed by the version JSON) is a `RegexFilter`
  that drops every message containing `${…}`, not `%msg{nolookups}`. When a launcher passes no config,
  `boot` points log4j at its own copy of that filter (`log4j2-safe.xml`, tested against beta9 in
  `SafeLoggingTest`). log4j is a class loader exclusion, so transformers can't patch `JndiLookup`.
  Don't swap log4j versions; FML touches log4j-core internals.
- **Twitch SDK** (dead service, native libs): patch in a null stream so nothing loads.
- **Sound race in `SoundManager` (1.7.10 and 1.8.9, fixed in Phase 3)**: a resource reload during the
  async "Sound Library Loader" starts a second sound system next to the first. OpenAL refuses the
  second context, and the game has no sound until the menu music crashes it (`UnsatisfiedLinkError` in
  `AL10.nalGetSourcei`). FML reloads right after the first load; on Java 25 Forge 1.7.10 lost the race
  every time, while Java 8 narrowly wins it. `shared-compat-mc`'s `SoundLoaderRace` makes a reload wait
  for the loader thread. It runs in the Forge profile only (SRG names) until the vanilla profile has
  them too (Phase 5).
- **Access token in the game log (to fix)**: vanilla 1.7.10 and 1.8.9 log `(Session ID is token:<access
  token>:<uuid>)` at startup, in plain text in `logs/latest.log`, which users paste into support
  chats. Forge 1.8.9's patched `Main` drops the line; vanilla and Forge 1.7.10 don't. Lich should
  redact it (a log4j filter can only drop the line, which is fine) in both profiles.
- **Scala 2.11** (Forge 1.7.10 ships it for Scala mods like ForgeMultipart/OpenComputers): test on 25.

### 2.5 LWJGL
- **Confirmed (2026-10-24, Windows x64, Temurin 25.0.4):** vanilla 1.8.9 with LWJGL 2.9.4 reaches the
  main menu on Java 25 with no flags at all. The only output is two warnings: `System::loadLibrary`
  from `org.lwjgl.Sys` (fixed by `--enable-native-access=ALL-UNNAMED`) and
  `sun.misc.Unsafe::objectFieldOffset` from `org.lwjgl.MemoryUtilSun$AccessorUnsafe`. Once a future
  JDK removes that method, LWJGL 2 falls back to reflection on `Buffer.address`, which needs
  `--add-opens java.base/java.nio`. Through Lich (Phase 1), both 1.8.9 and 1.7.10 also join a local
  server on Java 25 with no warnings at all; 1.7.10 also ran a singleplayer world and a public server.
  Still untested: Linux, macOS.
- LWJGL 2 is broken on Apple Silicon and has old input/DPI/Wayland problems.
- LWJGL 3 reuses package/class names with different signatures, and its JNI symbols are tied to
  `org.lwjgl.*`, so it **can't be relocated**. Keep LWJGL 3 at `org.lwjgl` and **rewrite consumer
  bytecode** so that LWJGL 2-only classes and methods point at Lich compat classes (`Display`,
  `Keyboard`, `Mouse`, `AL`, `Controllers`, removed `GLxx` overloads).

---

## 3. Architecture

```
launcher (vanilla / Prism / MultiMC)
   │  java 25 + Lich JVM flags, mainClass = dev.lich.boot.Main
   ▼
LichSystemClassLoader (-Djava.system.class.loader) ─ loads the whole class path, minus the jars Lich
   │        replaces (Mojang's LaunchWrapper, asm-all 5), and runs the compat pass on what it defines
   ▼
lich-boot ─ checks the JVM flags, installs the compat pass, picks the profile
   │        {1.7.10, 1.8.9} × {vanilla, forge}, discovers Lich mods
   ▼
Lich LaunchWrapper (own impl, API-compatible net.minecraft.launchwrapper.*)
   │  LaunchClassLoader(parent = platform loader; excluded classes come from LichSystemClassLoader)
   │  transformer order:
   │    1. Lich compat pass: JDK/ASM redirects, reflection compat, SecurityManager strip
   │       (also on transformer-excluded classes; on obf names, before Forge's binpatches)
   │    2. [vanilla profile] obf → SRG remapper (Lich's own; Forge does this in the forge profile)
   │    3. Forge/FML transformers + Forge coremods          (forge profile only)
   │    4. Mixin (Lich internal patches + Lich mods + Forge mods' mixins)
   │    5. Lich mod raw transformers (advanced, opt-in)
   │    6. Lich last: game fixes on SRG names (now ClassPatches, later Mixins), frame recomputation
   ▼
Lich mod loader ───────────────┬──── FML (forge profile) loads Forge mods as usual
   preLaunch → init → client   │     Lich jars are hidden from FML discovery
   ▼                           ▼
Minecraft  ◄── Lich API (events, hooks, optimisations) available in both profiles
```

### 3.1 One runtime namespace: SRG
1.7.10/1.8.9 have no official Mojang mappings, and Forge runs the game with SRG names. Lich runs the
game **in SRG in both profiles**. In the vanilla profile, Lich does the obf → SRG remap itself, using MCP
SRG data downloaded at install time (not bundled). Because of this:
- One Lich mod jar (SRG-reobfuscated, same as a Forge mod) works with and without Forge.
- Mixin refmaps target SRG in all cases. That's what Lich mod builds produce.
- Dev mappings: MCP `stable_12` (1.7.10) / `stable_22` (1.8.9).

### 3.2 Gradle modules (Java 25 toolchain everywhere)
| Module | Contents |
|---|---|
| `boot` | Main class, flag checks, profile selection, crash-report hints ("mod X used removed JDK API Y") |
| `launchwrapper` | Own implementation of `Launch`, `LaunchClassLoader`, `ITweaker`, `IClassTransformer`, `IClassNameTransformer`, `LogWrapper`, blackboard |
| `transform` | The compat pass (`CompatTransformer`): type and string renames, call redirects, class patches |
| `remap` | obf → SRG runtime remapper for the vanilla profile |
| `compat-jdk` | Shims for removed JDK APIs and their redirect table: Pack200 (nested commons-compress), SecurityManager, reflection compat |
| `compat-asm` | ASM 5 classes ASM 9 removed (verbatim), redirects for ASM 5 behavior |
| `compat-lwjgl2` | LWJGL 2 API on LWJGL 3.3.x (Phase 6) |
| `compat-forge-<ver>` | Version-specific FML/Forge patches |
| `compat-mc-<ver>` | Fixes to the game (Mixins from Phase 4, ClassPatches on SRG names until then); fixes identical in both versions live in `shared-compat-mc` |
| `loader` | Lich mod discovery, descriptor parsing, dependency resolution, entrypoints, FML-hiding |
| `api` + `api-<ver>` | Public Lich API: version-neutral interfaces + per-version implementations |
| `perf` | Built-in optimisation modules (§5.3) |
| `mixin-provider` | Bundled modern Mixin + MixinExtras, and the "always serve ours" rule |
| `installer` | Version JSON / Prism component, LWJGL 3 + libs, MCP SRG data |
| `testmod` + `harness` | Automated tests (§7) |

To add 1.12.2 later, add `compat-forge-1.12.2`, `compat-mc-1.12.2` and `api-1.12.2`. Nothing else should
need to change. Keep version-specific code out of the core modules.

Patching rule: FML/launch-time classes load before Mixin is up, so patch them with **raw ASM
transformers**. Patch game classes with **Mixins**.

---

## 4. Java 25 compatibility designs

### 4.1 JVM flags (put in the version JSON)
```
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.lang.invoke=ALL-UNNAMED
--add-opens java.base/java.nio=ALL-UNNAMED
--add-opens java.base/java.io=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.net=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED
--add-exports java.base/jdk.internal.reflect=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
--sun-misc-unsafe-memory-access=allow
```
The source of truth is `core/boot/src/main/resources/dev/lich/boot/jvm-flags.txt`: the profile
generator writes it into the version JSON and `boot` checks it at startup. The `jdk.naming.dns` flags
were dropped once SRV turned out not to need them (§2.4). Start broad, then narrow the list once the
test matrix passes. Launchers start the game with
`-cp … mainClass`, so manifest `Add-Opens` doesn't apply. `boot` refuses to start with a readable
message if a flag is missing.

### 4.2 LaunchWrapper
- Same public API **and** the same private field names/types as LaunchWrapper 1.12 (§2.1).
- Class path from Lich's system class loader when it's there (it leaves out the jars Lich replaces),
  from `java.class.path` otherwise. Never a cast of the JDK's application loader to `URLClassLoader`.
- Parent = platform class loader. Keep the original exclusion lists; boot adds Lich's packages and
  `org.objectweb.asm.` (§2.4).
- Differences from 1.12, all in Lich's favor and covered by tests: Lich's first transformers (the
  compat pass) also run on transformer-excluded classes, and `getSources()` leaves out the jars in
  `LichSources` (Lich's own and ASM 9), so FML's mod discovery never scans them.
- Tweaker chain, `--tweakClass` handling and blackboard keys (`Tweaks`, `TweakClasses`,
  `ArgumentList`, `fml.deobfuscatedEnvironment`) behave exactly like LaunchWrapper 1.12. Write tests
  that compare against the real LaunchWrapper running on Java 8.

### 4.3 Redirect transformer
One table-driven ASM pass: `owner/name/desc` → `dev/lich/compat/...` static replacement. It covers removed
JDK APIs, LWJGL 2-only APIs, `System.setSecurityManager`, `System.exit` (mods only), `Thread.stop`, and
`jdk.nashorn`. Log hits per mod.

### 4.4 Reflection compat (EnumHelper and "setFinalStatic")
Implemented in Phase 3 for the pattern Forge itself uses (`ObjectHolderRef`, `EnumHelper`), which many
mods copied: the string constants `"sun.reflect.ReflectionFactory"` (only in classes that also mention
`newFieldAccessor`/`newConstructorAccessor`), `"sun.reflect.FieldAccessor"` and
`"sun.reflect.ConstructorAccessor"` are renamed to Lich look-alikes in `dev.lich.compat.jdk.reflect`, so
the same reflective lookups land on Lich's methods. Field accessors write through
`jdk.internal.misc.Unsafe`; constructor accessors call a method handle, which works for enum
constructors. In classes containing `"modifiers"`, `Class.getDeclaredField` hands out a dummy for
`Field.modifiers` and `Field.set` writes static finals. Still to do (Phase 4): the `Field.set<Primitive>`
variants, resetting `enumConstantDirectory` when EnumHelper adds constants (verify with a mod that uses
it), and other spellings of the hack found in the mod corpus.

The original design:
- **Enums**: when loading game/mod enum classes, strip `ACC_FINAL` from `$VALUES`/`ENUM$VALUES`.
  Create new constants with a `MethodHandle` to the private constructor (`privateLookupIn`), append them
  to `$VALUES`, and reset `Class.enumConstants`/`enumConstantDirectory`. Replace Forge's `EnumHelper`
  internals with this.
- **Final fields**: redirect `Class.getDeclaredField` so that `Field.class.getDeclaredField("modifiers")`
  returns a dummy field, and redirect `Field.set*` so that writes to the dummy are dropped and
  `static final` writes go through `jdk.internal.misc.Unsafe`.

### 4.5 Forge binpatches
**Changed in Phase 3: patched at runtime, like Forge itself does, not pre-applied by the installer.** The
compat pass renames `java.util.jar.Pack200` in `ClassPatchManager` to Lich's `Pack200` shim, so FML
unpacks `binpatches.pack.lzma` and applies the patches as on Java 8. Nothing depends on where a
launcher keeps the Forge jar, nothing has to be re-run when Forge changes, and no patched Minecraft
classes are written to disk. Cost: about 1 s per launch for unpacking (cache the unpacked jar later in
`perf` if it matters).

The shim drives commons-compress's Pack200 port, nested inside `lich-compat-jdk` and loaded by an
isolated in-memory class loader (its commons-io/commons-lang3 would clash with the game's older copies).
Three commons-compress behaviors needed working around (`Pack200Test` covers them):
- It "unwraps" any `FilterInputStream` by reflection to find the file underneath. FML passes an
  `LzmaInputStream`, so commons-compress read the compressed bytes and unpacked nothing, without an
  error (the game then ran with no Forge patches at all). The shim hides the stream's type.
- It leaves the last jar entry open; FML reads the jar bytes without closing the stream.
- It closes its log stream, `System.out` by default, after every segment. The shim gives it a temp
  log file instead.

### 4.6 Launcher integration
- **Primary**: a version JSON per {MC version × profile} (`inheritsFrom` vanilla,
  `mainClass = dev.lich.boot.Main`, LWJGL overrides, JVM args). Check whether Mojang's launcher reads
  `arguments.jvm` from a child JSON whose parent is a legacy (`minecraftArguments`) version
  **(verify)**. If it doesn't, the flags have to go in the launcher profile's JVM arguments.
  `devlaunch` already handles both cases.
- **Prism/MultiMC**: a custom component (`patches/dev.lich.json` with `mainClass`, `+libraries` using
  `MMC-hint: local`, and `+jvmArgs`) plus an `mmc-pack.json` entry after every other component, so
  Lich's main class wins over Forge's (`net.minecraft.launchwrapper.Launch`). **Works in Prism 11.0.2** (2026-10-24, both versions): `installer`'s `PrismComponent`, run with
  `:installer:installPrism`. The instance also needs `IgnoreJavaCompatibility=true` and
  `AutomaticJava=false`, or Prism falls back to Java 8. Phase 6 adds replacing the LWJGL 2 component.
- No custom launcher.

---

## 5. Lich mods

### 5.1 Format: `lich.mod.json` at the jar root
```jsonc
{
  "schema": 1,
  "id": "examplemod",
  "version": "2.0.0",
  "name": "Example Mod",
  "authors": ["..."],
  "minecraft": "1.8.9",                 // one jar per MC version (multi-version jars: maybe later)
  "depends": {
    "lich": ">=0.4",
    "forge": "optional"                 // "required" | "optional" | "incompatible"
  },
  "entrypoints": {
    "preLaunch": ["dev.example.lich.PreLaunch"],   // before game classes load
    "init":      ["dev.example.lich.Init"],        // after game bootstrap, before first frame
    "client":    ["dev.example.lich.ClientInit"]   // after the Minecraft instance is ready
  },
  "mixins": ["mixins.examplemod.json"],
  "accessWidener": "examplemod.lich.aw",               // make fields/methods public / non-final
  "transformers": [],                                  // raw ASM, discouraged, needs explicit opt-in
  "javaVersion": 25
}
```
- **Discovery**: jars in `mods/` that contain `lich.mod.json`. They are **hidden from FML's
  `ModDiscoverer`**, so FML never scans them.
- **Dual jars**: a jar that has both `lich.mod.json` and `mcmod.info`/`@Mod` is claimed by Lich when
  running under Lich, and still works as a plain Forge mod elsewhere. That lets a mod ship one jar
  during the transition.
- **Dependency resolution**: semver ranges, clear error screens for missing or incompatible mods,
  load-order sorting.
- **Forge interop**: a Lich mod with `forge: optional` can check `Lich.isForgePresent()` and use Forge
  APIs only behind that check.

### 5.2 Lich API (both profiles, implemented with Lich's own Mixins)
- **Lifecycle**: preLaunch / init / client / shutdown.
- **Events** (lightweight bus, no reflection-scanning on the hot path): client tick (pre/post), render
  stages (world, entities, HUD layers, GUI overlay, screen open/close), input (key/mouse, raw and
  bound), chat (send/receive), world load/unload, server connect/disconnect, resource reload.
- **Hooks with control**: cancel/replace render passes, custom HUD layers, keybind registry, config
  API with a built-in GUI, resource/texture hooks, command registry (client-side).
- **Network**: read-only packet observation by default. Packet send/modify is a separate, explicitly
  documented API so authors know they're touching server-observable behavior (§6).
- **Access**: access wideners, plus a stable `api` surface so mods use Mixins only where the API has
  nothing.

### 5.3 Built-in optimisations (`perf`, each one a toggle)
Rendering and client-side only (§6 rule): entity/tile-entity culling, faster chunk mesh building and
uploads, fewer GL state changes, font/text render caching, particle limits, faster startup (parallel
resource and texture loading, cached transformed classes), memory-leak fixes, a frame limiter and
input-latency options, GC presets (G1 tuned / generational ZGC, `-XX:+UseCompactObjectHeaders`).

---

## 6. Public-server safety (hard requirement)

Goal: no bans, no kicks, no disconnects that a plain vanilla/Forge client wouldn't also get.

### 6.1 Rule: the client's server-observable behavior must not change
"Server-observable" means anything that reaches or is inferred from the network.
- **Never touched by Lich core or `perf`**: player movement/physics code, the 20 TPS client tick timer,
  `PlayerControllerMP` (attack/dig/use timing), packet send order/rate/content, rotation math,
  `MathHelper` (its sine table *is* part of the physics; "fast math" replacements change movement and
  can trip prediction anticheats), keep-alive handling, entity interaction.
- **Allowed**: rendering, GUI, audio, resource loading, client-only caches, threading of
  client-only work (chunk meshing), memory and GC.
- Enforce this mechanically: keep a deny-list of target classes/methods for Lich's own Mixins and
  `perf`, and fail the build if anything targets them. Lich mods can still touch them; that's on the
  mod author (and the server's mod rules).

### 6.2 Protocol and identity
- No Lich plugin channels, no `REGISTER` additions, no extra packets unless a mod asks for them.
- Client brand: by default send exactly what the underlying game sends (`vanilla` / `fml,forge`).
  Open question: offer an opt-in `lich` brand.
- Login/encryption on Java 25: 1.7/1.8 use RSA-1024 + `AES/CFB8/NoPadding` and zlib (1.8). **Works for
  1.7.10** (2026-10-24: the user joined as.minemen.club with a Microsoft account through Lich on Java 25,
  so session join, RSA and AES/CFB8 work); 1.8.9 (which adds zlib) still to check. Mojang session-server
  HTTPS works better on 25 (newer CA certs and TLS).

### 6.3 Floating point
Java 17 made all floating point strict (JEP 306). On x86-64, Java 8 already behaved that way (SSE2), so
physics should match bit for bit. `Math.*` intrinsics (e.g. `Math.atan2`, which 1.7.10 uses) may
differ by 1 ulp between JDKs **(verify)**.

### 6.4 Tests (see §7)
- **Determinism replay**: record inputs on Java 8 vanilla, replay on Lich/Java 25, and compare every
  outgoing movement/action packet byte for byte.
- **Anticheat smoke test**: local 1.8.8 Spigot/Paper with a prediction anticheat (e.g. Grim behind
  ViaVersion **(verify)**). Walk, sprint-jump, bridge and PvP for N minutes with zero flags.
- Manual checks on big public servers (e.g. Hypixel) before each release.

---

## 7. Testing
- **Harness**: a `testmod` (built as both a Forge mod and a Lich mod). With `-Dlich.test=…` set it
  loads a world, runs N ticks, optionally joins a local server, dumps a report, and exits with a status
  code.
- **CI**: Linux + Xvfb + Mesa llvmpipe. Matrix: {1.7.10, 1.8.9} × {vanilla, forge, forge+modset,
  lich-mods} × {Java 8 baseline, Java 25}, plus the determinism replay and anticheat jobs.
- **Mod corpus**: a pinned list of popular mods per version.
  - 1.8.9 ideas: OneConfig-based mods, SkyblockAddons, NotEnoughUpdates, Skytils, OldAnimations,
    Chatting.
  - 1.7.10 ideas: NEI/CodeChickenCore, ForgeMultipart (Scala), Thaumcraft, IC2, BuildCraft, AE2,
    OpenComputers.
- **LaunchWrapper conformance**: the same tweaker/transformer scenarios run on real LaunchWrapper
  (Java 8) and Lich's, with the same results.

---

## 8. Roadmap

| Phase | Goal | Done when |
|---|---|---|
| 0. Setup ✅ | Gradle multi-project on JDK 25 + `devlaunch`; still to do: decompile LaunchWrapper 1.12, FML 1.7.10/1.8.9; read lwjgl3ify/RFB | **Done 2026-10-24:** repo builds; vanilla 1.8.9 and 1.7.10 reach the main menu on **Java 8** through `devlaunch` (baseline). The Lich version JSON generator moves to Phase 1, since there's no `boot` to point it at yet. |
| 1. Vanilla 1.8.9 on 25 🟡 | `boot` + `launchwrapper` + flags + log4j safety + profile generator. The SRV fix came free with the class loader parent. The Twitch null stream moved to Phase 8: on 25 it only loads a few unused DLLs | **So far (2026-10-24):** main menu and joining a local server on Java 25 with zero JVM warnings. Prism test instances `lich-1.8.9`/`lich-1.7.10` start Lich on Java 25. **Left:** a singleplayer world and a public server with a real account in `lich-1.8.9` (§4.6) |
| 2. Vanilla 1.7.10 on 25 ✅ | Same for 1.7.10. No 1.7.10-specific code was needed | **Done 2026-10-24:** in Prism with the user's account, a singleplayer world and a public server (as.minemen.club, about 40 s, chat working), Java 25 |
| 3. Forge, no mods 🟡 | Runtime binpatching through a Pack200 shim (§4.5), ASM 9 + ASM 5 shims, SecurityManager strip, reflection compat for ObjectHolderRef/EnumHelper (§4.4), Lich's system class loader, hiding Lich's jars from FML, the SoundManager race fix (§2.4) | **So far (2026-10-24):** Forge 1.7.10 and 1.8.9 load ("successfully loaded 3 mods") and reach the main menu on Java 25 through devlaunch, with zero JVM warnings; their logs match the Java 8 Forge baseline apart from Lich's own lines. **Left:** the mod list screen, and Prism instances with Forge on the user's account |
| 4. Forge mods | Mixin provider, redirect table, reflection compat, JDK shims, harness + corpus | Agreed % of the corpus loads; failures name the mod and the API |
| 5. Lich mods v0 | `remap` (vanilla-profile SRG), `loader`, `lich.mod.json`, `api` v0 (lifecycle, events, HUD, keybinds, config), FML-hiding, dual jars | A full client mod runs as a Lich mod on both versions, with and without Forge |
| 6. LWJGL 3 | `compat-lwjgl2` + redirects; Prism LWJGL override | Windows/Linux/macOS ARM native; mods calling GL11/Display/Keyboard work |
| 7. Server safety gate | Determinism replay + anticheat CI jobs; Mixin deny-list enforcement | Zero packet diffs vs Java 8 vanilla; zero anticheat flags |
| 8. Optimisations | `perf` modules (§5.3), each benchmarked and server-safe | Measured FPS/stutter/memory gains vs Java 8 |
| 9. Ship | Installer UX, Prism pack, docs, compat list, mod-dev docs + template | Users install without reading source; a third party can write a Lich mod |
| later | 1.12.2 (`compat-forge-1.12.2`, `compat-mc-1.12.2`, `api-1.12.2`) | — |

Phase 7 runs from Phase 1 onwards as checks get added. It's listed separately because it's a release gate.

Note on startup: Java 25's AOT cache (JEP 483/514/515) doesn't cache classes from custom class loaders,
so it helps little here. Cache *transformed class bytes* on disk instead (in `perf`).

---

## 9. Risks
- **Coremods with aggressive ASM** may assume exact bytecode or old ASM behavior.
- **OptiFine**: closed source, can't be redistributed, swaps in whole classes, heavy LWJGL 2 use.
  Stretch goal (user-supplied jar). Lich's `perf` should make it less needed.
- **Unsafe trajectory**: `sun.misc.Unsafe` memory access will be denied by default in a future JDK.
  Use `jdk.internal.misc.Unsafe` via `--add-exports`.
- **Shaded old libraries in mods** (ASM, Mixin, Guava, Kotlin/Forgelin): "always serve ours" rules per
  package, kept conservative.
- **Lich mods on servers**: the API gives mods a lot of control. Server mod rules (Hypixel's allowed-mods
  policy etc.) apply to Lich mods like any other. Lich core itself stays server-invisible.
- **Mod toolchain on Java 25**: compiling Lich mods to Java 25 with RFG / Essential Loom and the Mixin
  annotation processor **(verify)**. A `lich-gradle` plugin may be needed eventually.

## 10. Open questions
1. Client brand: always mirror vanilla/Forge, or opt-in `lich`?
2. Multi-version Lich mod jars (one jar for 1.7.10 + 1.8.9), or one jar per version only?
3. Should Lich events also fire as Forge events when Forge is present (bridge), or stay separate?
