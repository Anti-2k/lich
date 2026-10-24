# Lich — plan

A client-side runtime and mod loader that runs Minecraft 1.7.10 and 1.8.9 on Java 25. It patches game,
Forge and mod bytecode at load time so that unmodified Java 8 Forge mods work. It also loads its own
mod format, which gets deep hooks into the game. Lich itself only loads mods: the game plays exactly as
vanilla (or Forge) does, and everything beyond running on Java 25, optimisations included, is a mod.

Items marked **(verify)** come from memory or second-hand sources and need checking against the real
jars before building on them.

## 0. Decisions

| Topic | Decision |
|---|---|
| LaunchWrapper | **Own implementation.** RetroFuturaBootstrap/lwjgl3ify are references for *behavior* only. Don't copy code unless the licenses allow it. |
| Forge mods | Loaded through real Forge, unmodified, in Forge's own format (`@Mod` + `mcmod.info`). Lich patches Forge at load time ("Forge back-compat"). They must work as they do on Forge on Java 8, with no changes and no Lich-specific setup. Lich's hooks stay out of their way: with no Lich mods, no hook is woven at all. |
| Lich mods | Own format (`lich.mod.json`). Runs **with or without Forge**. First real user: a full client mod. |
| Game versions | 1.7.10 (Forge `10.13.4.1614`) and 1.8.9 (Forge `11.15.1.2318`). Keep the version-specific code isolated so 1.12.2 (also LaunchWrapper-based) can be added later. |
| Server side | **Client only**, plus the integrated singleplayer server that ships in the client jar. No dedicated-server target. Hard requirement: joining public servers must look and behave exactly like a normal client, so no bans or disconnects (§6). |
| Java | 25. Lich code and Lich mods compile to Java 25. Forge mods stay Java 8 bytecode. |
| Game logic (2026-10-24) | **Core changes the game only where it has to run on Java 25** (JDK/ASM redirects, reflection compat, fixes for bugs Java 25 exposes such as the sound race). No gameplay, rendering or behavior changes, no "improvements". With no Lich mods installed, Lich plays exactly like vanilla or Forge on Java 8. |
| Hooks (2026-10-24) | Lich mods get **deep hooks** (§5.2): events, replaceable game subsystems, Mixins, access wideners, raw transformers. Core weaves a hook into game code only when a loaded Lich mod asks for it, and an unused hook leaves vanilla behavior unchanged. |
| Optimisations (2026-10-24) | **Not in core.** They're a separate, optional first-party Lich mod (`mods/perf`, §5.3), built on the same hooks as any other Lich mod, so they can be as aggressive as the hooks allow. |
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
| JSR-250 `javax.annotation.Resource` | 11 (JEP 320) | Mixin 0.7's `LegacyTransformerHandle` (fatal at launch with any Mixin mod) | **Done (Phase 4):** renamed to a look-alike annotation in `compat-jdk` |
| JAXB `javax.xml.bind` (`DatatypeConverter`) | 11 | Mods doing base64/hex | Bundle jaxb-api, or redirect to `java.util.Base64`/`HexFormat` |
| Nashorn `jdk.nashorn.*` | 15 | Scripting mods: Fisk's Superheroes (hero packs in JS) | **Done (Phase 4):** Lich ships `nashorn-core` 15.7 (on its ASM 9, hidden from mod discovery, a class loader exclusion) and the compat pass renames `jdk/nashorn/` → `org/openjdk/nashorn/`, plus the class names mods pass to `Class.forName`. Its engines load game classes through the context class loader (LaunchClassLoader) |
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
    (`LichSources`). **(Phase 4)** Mods also bring Java 11+/17 jars and classes (GTNHLib's downloaded
    jvmdowngrader, its Java 17 class variants), which FML's discovery and `DeobfuscationTransformer` read.
    So in every class that uses ASM the compat pass raises the API level `ASM5` (an inlined `ldc 327680`)
    to `ASM9`, and `compat-asm`'s ASM 5 `Remapping*` copies default to `ASM9`. For code written against
    ASM 5 a higher level only adds callbacks for newer attributes, whose defaults pass them on, so output
    for Java 8 classes is unchanged. `ASM4`-level code is left alone (ASM routes its old-style method
    calls differently).
  - **(Phase 4)** ASM 9's `Type` is stricter where ASM 5 was lenient, beyond `getType`: `getReturnType`
    of a descriptor without `)` (ASM 5 returned the descriptor's own type; ForgeMultipart's trait
    compiler relies on it), and `getSize`/`getOpcode` of a method-sort type (ASM 5 treated it as a
    reference; ASM 9 throws `AssertionError`, and method-sort types are what ASM 5's `getType` made of
    internal names). All redirected to `Asm5Types`.
  - **(Phase 4)** In 1.7.10, where FML let LaunchClassLoader define ASM, a plain `ClassWriter`'s
    `getCommonSuperClass` resolved classes through LaunchClassLoader, which sees mod jars and libraries
    added at runtime (FalsePatternLib's downloads, e.g. fastutil for Hodgepodge). Lich's single ASM lives
    in the system class loader, so in the 1.7.10 profile `new ClassWriter(...)` becomes
    `LaunchClassLoaderClassWriter`, which resolves through the context class loader. 1.8.9's FML kept ASM
    in the system class loader on Java 8 too, so nothing changes there.
  - **(Phase 4)** ASM 6+ throws `IllegalStateException` when a *subclass* calls the API-less constructor
    of `BasicInterpreter`, `BasicVerifier`, `SimpleVerifier`, `SourceInterpreter`, `CheckMethodAdapter`
    or `TryCatchBlockSorter`; ASM 5 allowed it (`ClassNode`, `MethodNode` etc. already threw in ASM 5,
    so old code doesn't do it). Mixin 0.7's `MixinVerifier` does, which broke every `@ModifyConstant`.
    The compat pass *rebases* such subclasses onto `compat-asm`'s `Asm5*` classes, which extend the
    ASM 9 class and keep the old constructors (`CompatTransformer.Builder.rebase`). List found by
    diffing the constructors with a `getClass()` check in ASM 5.0.3 and 9.8.
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
- **Mixin (Phase 4, decided 2026-10-24)**: mods shade Mixin 0.7.x, and under LaunchWrapper the first
  copy on the class path wins, on Forge as on Lich. **For Forge mods, Lich runs the shaded copy as it
  is** (what Forge does, so mods see the Mixin they were tested with) and fixes its Java 25 problems in
  the compat pass. A bundled modern Mixin for Lich mods comes with Phase 5; whether it should then also
  replace shaded copies is open. What Mixin 0.7.11 needed:
  - It shades ASM 5.2 as `org.spongepowered.asm.lib`, and reads JDK classes (`java/lang/Math`, …) for
    its class metadata. ASM 5.2's `ClassReader` rejects anything newer than Java 8 with a message-less
    `IllegalArgumentException`, so applying any mixin that calls a JDK method failed. The compat pass
    renames `org/spongepowered/asm/lib/` to `org/objectweb/asm/` in every class (Mixin and the config
    plugins mods write against it), so all of it runs on Lich's ASM 9. Every class in Mixin's copy has
    an ASM 9 or `compat-asm` counterpart except `Item`, which is internal to ASM 5's `ClassWriter`.
    The rename only happens when the relocated copy the game would load is too old for Java 25 class
    files (no `V25` in its `Opcodes`, checked once, `MixinShadedAsm`): UniMixins (GTNH's Mixin 0.8 fork)
    relocates ASM 9.8 to the same package, with bridges for 0.7-era plugins that a rename would cut
    (it broke Hodgepodge's GTNHLib plugin until the check was added).
  - `LegacyTransformerHandle` checks transformers for `javax.annotation.Resource` (JSR-250, removed in
    Java 11). Renamed to `compat-jdk`'s look-alike.
  - `MixinServiceLaunchWrapper.init` logs "MixinBootstrap.doInit() called during a tweak constructor!"
    unless `net.minecraft.launchwrapper.Launch.launch` is on the stack. Lich's boot now starts the game
    through `Launch.main` → private `Launch.launch` like 1.12, so the stack matches.
  - `MixinVerifier`'s constructor: see the ASM rebases above.
  - Checked (2026-10-24): with OptiFine (Mixin 0.7.11), the mixins applied on Lich/Java 25
    are exactly those applied on Forge/Java 8 (`-Dmixin.debug.verbose`, same 55 on 1.8.9 and 50 on
    1.7.10 after joining a server), with the same warnings.
  - Other Mixins seen working: UniMixins 0.3.1 (ArchaicFix, JourneyMap, EntityCulling's 1.7.10 port),
    Essential's Mixin 0.8.7. GTNH's old `gtnhmixins` 2.1.2 caps its ASM support at Java 14 class files
    in its own code and can't read JDK 25 classes; it's superseded by UniMixins, which works.
- **Relaunching mods (Phase 4)**: Essential's loader (which many 1.8.9 mods embed) starts
  `Launch.main` a second time in its own `RelaunchClassLoader`, which defines fresh copies of every class
  on the class path, Lich's included, shares only `java.*`-style packages with the first launch, and puts
  Essential's jars first: its Mixin 0.8.7, Kotlin, and asm-debug-all **5.2**. Lich handles it like this:
  `Launch.main` entered without boot's hooks runs the boot of its own class loader (`LaunchCore.startBoot`),
  and boot in a relaunched copy (`Relaunch`) first hooks the relaunch loader's own transformer (a
  `BiFunction` field, which Essential uses to strip `setSecurityManager` itself) so its ASM classes come
  from Lich's jars and, once built, the compat pass runs on what it defines; Lich's jars are found by
  classifying its URLs. Essential 1.5.1 on 1.8.9 now joins a server. The hook must go in before any ASM
  class loads: the JVM loads some while verifying boot's own classes.
- **SRV lookup (tested 2026-10-24)**: vanilla `ServerAddress` loads `com.sun.jndi.dns.DnsContextFactory`
  with the game's class loader, then asks JNDI for it. With LaunchWrapper 1.12's bootstrap-only parent,
  that throws `ClassNotFoundException` on Java 9+, and vanilla **silently** falls back to `<host>:25565`.
  So `hypixel.net` (SRV → `mc.hypixel.net`) connects to the wrong host. Lunar's
  `--add-modules jdk.naming.dns --add-exports …` flags don't fix this. With Lich's platform-loader parent
  it works with **no flags**: JNDI finds the factory through `ServiceLoader`. Covered by
  `LaunchClassLoaderTest.platformModulesAreVisible`.
- **`java.version` parsing**: code that expects `1.x` breaks on `25`. Audit FML, Mixin 0.7, LWJGL 2 and
  popular mods. Found so far:
  - **commons-lang3 (Phase 4)**: the game's copies (3.1 in 1.7.10, 3.3.2 in 1.8.9) return `null` from
    `JavaVersion.get("25")`, so `SystemUtils.isJavaVersionAtLeast` throws an NPE. FML 1.8.9's
    `BlamingTransformer` calls it for every Java 8 class file, so on 1.8.9 *no mod compiled for Java 8
    loaded*. `compat-jdk`'s `CommonsLangJavaVersion` makes `get` return the newest constant
    (`JAVA_1_8`) for Java 9+ strings, like commons-lang3 3.5+'s `JAVA_RECENT`.
  - Mixin 0.7.11's `JavaVersion` parses `25.0` correctly ("Compatibility level set to JAVA_8").
- **Default charset is UTF-8** (Java 18). Usually harmless.
- **log4j 2.0-beta9** is Log4Shell-vulnerable, and both client jars bundle an unfiltered `log4j2.xml`.
  Mojang's fix for these versions (`client-1.7.xml`, passed by the version JSON) is a `RegexFilter`
  that drops every message containing `${…}`, not `%msg{nolookups}`. When a launcher passes no config,
  `boot` points log4j at its own copy of that filter (`log4j2-safe.xml`, tested against beta9 in
  `SafeLoggingTest`). log4j is a class loader exclusion, so transformers can't patch `JndiLookup`.
  Don't swap log4j versions; FML touches log4j-core internals.
- **Twitch SDK** (dead service, native libs): on 25 it only loads a few unused DLLs, so it runs on Java 25
  as is and core leaves it alone (§0, game logic). A null stream can go in the perf mod.
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
- **Scala 2.11** (Forge 1.7.10 ships it for Scala mods like ForgeMultipart/OpenComputers): works on 25
  (2026-10-24: OpenComputers, ForgeMultipart with Mekanism).
- **Interface method handles (Phase 4)**: ASM 5.0's `Handle` had no interface flag, so mods reobfuscated
  with it (SpecialSource in ForgeGradle 1.x) refer to lambda bodies in interfaces, and to static interface
  methods like `Comparator::comparing`, with a `CONSTANT_Methodref`. Java 8 accepted it; Java 9+ throws
  `IncompatibleClassChangeError` ("should be CONSTANT_InterfaceMethodRef"; Fisk's Superheroes). In
  classes that use `LambdaMetafactory`, the compat pass sets the flag on `invokestatic`/`invokespecial`
  handles whose owner is an interface, read from the class file without loading it (`ClassFiles`).
- **Gson on JDK classes (Phase 4)**: the game's Gson 2.2.4 serializes types it has no adapter for by
  reflecting on private fields, JDK types included (Chatting: `java.util.regex.Pattern`). More
  `--add-opens` in §4.1: `java.util.regex`, `java.util.concurrent(.atomic)`, `java.time`, `java.text`,
  `java.desktop/java.awt`.
- **LWJGL 3 assumed on Java 17+**: current GTNH libraries (GTNHLib for Hodgepodge) take Java 17+ to mean
  GTNH's lwjgl3ify environment and call LWJGL 3 (`org.lwjgl.system.jni`). Comes with Phase 6.

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
   │    4. Mixin (Lich API hooks that loaded Lich mods use + Lich mods + Forge mods' mixins)
   │    5. Lich mod raw transformers (advanced, opt-in)
   │    6. Lich last: Java 25 game fixes on SRG names (now ClassPatches, later Mixins), frame recomputation
   ▼
Lich mod loader ───────────────┬──── FML (forge profile) loads Forge mods as usual
   preLaunch → init → client   │     Lich jars are hidden from FML discovery
   ▼                           ▼
Minecraft  ◄── Lich API (events, deep hooks) in both profiles; optimisations come from the perf mod
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
| `perf` (`mods/perf`) | Not core: the first-party optimisation mod, an ordinary Lich mod (§5.3) |
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
--add-opens java.base/java.util.regex=ALL-UNNAMED          # these six: Gson 2.2.4 on JDK types (§2.4)
--add-opens java.base/java.util.concurrent=ALL-UNNAMED
--add-opens java.base/java.util.concurrent.atomic=ALL-UNNAMED
--add-opens java.base/java.time=ALL-UNNAMED
--add-opens java.base/java.text=ALL-UNNAMED
--add-opens java.desktop/java.awt=ALL-UNNAMED
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
`jdk.nashorn`. Log hits per mod. Besides call redirects, `CompatTransformer` has type renames, package
renames (relocated old libraries, logged once per namespace), string renames, rebases (subclasses of a
library class moved onto a Lich subclass) and per-class `ClassPatch`es. Rules match on raw bytes first,
including a type's spelling before a package rename, so most classes are never parsed. The pass doesn't
recompute stack map frames, so its patches must not add branches.

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
classes are written to disk. Cost: about 1 s per launch for unpacking (cache the unpacked patches later
in `loader` if it matters; that's a launch-time cache, not a game change).

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
  "hooks": ["render.hud", "input.key", "chat.receive"], // Lich weaves only the hooks loaded mods list (§5.2)
  "provides": [],                                      // subsystems this mod replaces, e.g. "render.chunkMesh"
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

### 5.2 Lich API: deep hooks (both profiles, implemented with Lich's own Mixins)
Lich itself doesn't change the game (§0); it gives Lich mods the means to. The hooks go deep enough
that an aggressive optimisation mod (§5.3) or a full client mod needs no private Lich internals.

**Rules for every hook:**
- **Woven on demand.** Each hook has an id (`render.world`, `render.chunkMesh`, `input.key`, …). Before
  game classes load, the loader collects the `hooks` and `provides` lists of every Lich mod and weaves
  only those hooks' Mixins. No Lich mods means no hooks woven: the game bytecode is exactly what vanilla
  or Forge would run on Java 25. That also keeps Lich out of Forge coremods' way. Using a hook at
  runtime that no mod declared fails with a message naming the mod and the hook.
- **The default path is vanilla.** A woven hook with no listener, or a subsystem nobody replaces, runs
  the original vanilla (or Forge-patched) code unchanged. The cost when unused is one static check.
- **Both profiles, same ids.** Hooks target SRG names, and each is tested on vanilla and Forge, because
  Forge's binpatches change some of the same methods.

**Levels, from safest to deepest:**
1. **Lifecycle**: preLaunch (before game classes load: register transformers, read config) / init /
   client / shutdown.
2. **Events** (lightweight bus, no reflection-scanning on the hot path): client tick (pre/post), render
   stages (world, entities, HUD layers, GUI overlay, screen open/close), input (key/mouse, raw and
   bound), chat (send/receive), world load/unload, server connect/disconnect, resource reload.
3. **Control hooks**: cancel or replace single steps (a render pass, a HUD element, a screen), custom
   HUD layers, keybind registry, config API with a built-in GUI, resource/texture hooks, client-side
   command registry.
4. **Replaceable subsystems**: interfaces over whole pieces of the client that optimisation mods want
   to swap, each with the vanilla code as its default implementation. Candidates: chunk mesh building
   and upload, chunk/entity/tile-entity render dispatch and visibility culling, particle management,
   font rendering, texture stitching and loading, the resource reload pipeline, GL state handling. A
   mod claims one through `provides`. Two mods claiming the same subsystem is an error screen naming
   both, unless one declares it gives way to the other.
5. **Mixins**: Lich's bundled modern Mixin + MixinExtras on SRG names, in both profiles, `@Overwrite`
   included. For anything the levels above don't cover.
6. **Access wideners**: make fields, methods and classes public and non-final.
7. **Raw transformers**: opt-in ASM transformers in slot 5 of the transformer order (§3), after Forge and
   Mixin, before Lich's last pass.

**Network**: read-only packet observation by default. Packet send/modify is a separate, explicitly
documented API so authors know they're touching server-observable behavior (§6).

Every time a first-party mod (perf) has to fall back to a Mixin for something general, that's
a sign a hook is missing: add the hook to the API rather than keep the Mixin.

### 5.3 Optimisations: the `perf` mod (`mods/perf`, each one a toggle)
An ordinary Lich mod, shipped separately and installed in `mods/` like any other. Core never depends on
it. It uses the public hooks (§5.2) and, where they fall short, its own Mixins, so it can go as far as
the hooks allow. It keeps to §6.1 even though Lich mods in general don't have to.
Rendering and client-side only: entity/tile-entity culling, faster chunk mesh building and uploads, fewer
GL state changes, font/text render caching, particle limits, parallel resource and texture loading,
memory-leak fixes, a frame limiter and input-latency options, the Twitch null stream.

Not in the mod, because they don't change the game:
- **Caching transformed class bytes** belongs in `loader`.
- **GC presets** (G1 tuned / generational ZGC, `-XX:+UseCompactObjectHeaders`) are JVM flags, which have
  to be set before any mod loads, so they're an `installer` option.

---

## 6. Public-server safety (hard requirement)

Goal: no bans, no kicks, no disconnects that a plain vanilla/Forge client wouldn't also get.

### 6.1 Rule: the client's server-observable behavior must not change
"Server-observable" means anything that reaches or is inferred from the network.
- **Never touched by Lich core or the first-party `perf` mod**: player movement/physics code, the 20 TPS client tick timer,
  `PlayerControllerMP` (attack/dig/use timing), packet send order/rate/content, rotation math,
  `MathHelper` (its sine table *is* part of the physics; "fast math" replacements change movement and
  can trip prediction anticheats), keep-alive handling, entity interaction.
- **Allowed**: rendering, GUI, audio, resource loading, client-only caches, threading of
  client-only work (chunk meshing), memory and GC.
- Enforce this mechanically: keep a deny-list of target classes/methods for Lich's own Mixins (hooks
  included) and `perf`, and fail the build if anything targets them. Hooks into these areas are
  observe-only, apart from the explicitly labelled packet send/modify API (§5.2). Other Lich mods can still touch them; that's on the mod author (and the server's mod
  rules).

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
- **Mod corpus** (2026-10-24, from Modrinth, newest Forge build of each, dependencies in the same folder,
  each run alone through devlaunch's `lich-forge` profile, joining the local test server for 25 s, game
  muted). Every entry passes but Hodgepodge (LWJGL 3):
  - 1.8.9 (22): a Forge + OptiFine instance, 3D Skin Layers, Biomes O' Plenty, Blood Magic, Chatting, Controlling,
    CraftTweaker, EntityCulling, Essential, FoamFix, In-Game Account Switcher, JEI, NotEnoughUpdates,
    PolyPatcher, Replay Mod, SecurityCraft, SkyblockAddons, SkyHanni, Storage Drawers, Wavey Capes,
    Xaero's Minimap, Xaero's World Map.
  - 1.7.10 (25): a Forge + OptiFine instance, Aether, ArchaicFix, Biomes O' Plenty, Blood Magic, Botania,
    ChickenChunks, Controlling, CraftTweaker, Draconic Evolution, Ender Storage, EntityCulling
    (UniMixins), Fisk's Superheroes, FoamFix, Hodgepodge, JourneyMap, Mekanism (+ ForgeMultipart),
    Nature's Compass, NEI, OpenComputers, SecurityCraft, Storage Drawers, Tinkers' Construct, Wawla,
    Xaero's Minimap.
  - Compare mixins applied on Java 8 and Lich with `-Dmixin.debug.verbose=true` and the
    `Mixing … into …` lines. The scripts used are throwaway; the harness (above) replaces them.
- **Mod corpus ideas**: a pinned list of popular mods per version.
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
| 1. Vanilla 1.8.9 on 25 🟡 | `boot` + `launchwrapper` + flags + log4j safety + profile generator. The SRV fix came free with the class loader parent. The Twitch null stream moved to the perf mod (Phase 8): on 25 it only loads a few unused DLLs | **So far (2026-10-24):** main menu and joining a local server on Java 25 with zero JVM warnings. Prism test instances `lich-1.8.9`/`lich-1.7.10` start Lich on Java 25. **Left:** a singleplayer world and a public server with a real account in `lich-1.8.9` (§4.6) |
| 2. Vanilla 1.7.10 on 25 ✅ | Same for 1.7.10. No 1.7.10-specific code was needed | **Done 2026-10-24:** in Prism with the user's account, a singleplayer world and a public server (as.minemen.club, about 40 s, chat working), Java 25 |
| 3. Forge, no mods 🟡 | Runtime binpatching through a Pack200 shim (§4.5), ASM 9 + ASM 5 shims, SecurityManager strip, reflection compat for ObjectHolderRef/EnumHelper (§4.4), Lich's system class loader, hiding Lich's jars from FML, the SoundManager race fix (§2.4) | **So far (2026-10-24):** Forge 1.7.10 and 1.8.9 load ("successfully loaded 3 mods") and reach the main menu on Java 25 through devlaunch, with zero JVM warnings; their logs match the Java 8 Forge baseline apart from Lich's own lines. In Prism, `lich-forge-1.8.9` with the user's account ran a singleplayer world (create, play, save, quit) cleanly. **Left:** the mod list screen, a public server on Forge 1.8.9, and `lich-forge-1.7.10` on the user's account |
| 4. Forge mods 🟡 | Mixin provider, redirect table, reflection compat, JDK shims, harness + corpus | Agreed % of the corpus loads; failures name the mod and the API. **So far (2026-10-24):** a 47-entry corpus (§7) joins a local server and stays in the world through Lich on Java 25, all but one (Hodgepodge: needs LWJGL 3, Phase 6). OptiFine and a full client mod on both versions apply exactly the mixins they do on Java 8. **Left:** a singleplayer-world harness (worldgen and content mods were only tested client-side), the mod list screen, more mods per genre, Java 8 baselines for every corpus mod, the Mixin provider for Lich mods (Phase 5) |
| 5. Lich mods v0 | `remap` (vanilla-profile SRG), `loader`, `lich.mod.json`, `api` v0 (lifecycle, events, HUD, keybinds, config; hooks woven on demand, §5.2), FML-hiding, dual jars | A full client mod runs as a Lich mod on both versions, with and without Forge; with no Lich mods installed, every game class is byte-identical to what Lich defines without `loader`/`api` (a harness check) |
| 6. LWJGL 3 | `compat-lwjgl2` + redirects; Prism LWJGL override | Windows/Linux/macOS ARM native; mods calling GL11/Display/Keyboard work |
| 7. Server safety gate | Determinism replay + anticheat CI jobs; Mixin deny-list enforcement | Zero packet diffs vs Java 8 vanilla; zero anticheat flags |
| 8. Optimisations | The `perf` mod (§5.3), plus the replaceable-subsystem hooks it needs (§5.2, level 4); each optimisation benchmarked and server-safe | Measured FPS/stutter/memory gains vs Java 8, with `perf` using no private Lich internals |
| 9. Ship | Installer UX, Prism pack, docs, compat list, mod-dev docs + template | Users install without reading source; a third party can write a Lich mod |
| later | 1.12.2 (`compat-forge-1.12.2`, `compat-mc-1.12.2`, `api-1.12.2`) | — |

Phase 7 runs from Phase 1 onwards as checks get added. It's listed separately because it's a release gate.

Note on startup: Java 25's AOT cache (JEP 483/514/515) doesn't cache classes from custom class loaders,
so it helps little here. Cache *transformed class bytes* on disk instead (in `loader`).

---

## 9. Risks
- **Coremods with aggressive ASM** may assume exact bytecode or old ASM behavior.
- **OptiFine**: closed source, can't be redistributed, swaps in whole classes, heavy LWJGL 2 use.
  Stretch goal (user-supplied jar). The `perf` mod should make it less needed.
- **Lich mods vs Forge coremods on the same code**: an aggressive Lich mod (perf replacing chunk
  rendering) and a Forge mod patching the same methods can clash. Subsystem hooks (§5.2) should detect
  when the vanilla method they wrap was changed by someone else and warn, naming both mods.
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
