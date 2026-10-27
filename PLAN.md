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
| Lich mods | Own format (`lich.mod.json`). Runs **with or without Forge**. First real user: 2k client. |
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
| JAXB `javax.xml.bind` (`DatatypeConverter`, `JAXBContext`) | 11 | Mods doing base64/hex; Enhanced Loot Bags reads its XML config with it (The Lost Era) | **Done (2026-10-27):** Lich ships JAXB 2.3 (the last line with the `javax.xml.bind` package: `jakarta.xml.bind-api` 2.3.3, `jaxb-runtime`/`txw2` 2.3.9, `istack-commons-runtime` 3.0.12, `jakarta.activation` 1.2.2), hidden from mod discovery (the jars have a `module-info`) and class-loader-excluded, so there's one copy as there was in the JDK. The runtime registers itself through `META-INF/services`, which `JAXBContext` finds through the context class loader |
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
  - Checked (2026-10-24): with OptiFine + 2k client (Mixin 0.7.11), the mixins applied on Lich/Java 25
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
  GTNH's lwjgl3ify environment and call LWJGL 3 (`org.lwjgl.system.jni`). Fixed by Phase 6: LWJGL 3 is on
  the class path, and Hodgepodge joins a server (2026-10-25).
- **`URLClassLoader` with `null` URLs**: Java 8 kept them (and only failed if a lookup reached one), Java
  9+ throws in the constructor. OneConfig's environment-LWJGL path passes one. The compat pass filters
  the array of every `URLClassLoader` constructor call, `super(...)` included (`UrlClassLoaders`, a new
  `filterFirstArgument` rule kind); arrays without `null`s pass unchanged.
- **Broken ImageIO provider lists** (found 2026-10-27 by the modpack harness, §7): Dungeons Guide shades
  TwelveMonkeys under its own package but keeps `META-INF/services/javax.imageio.spi.*` naming the original
  classes. Java 8's `IIORegistry` printed each missing one and went on, because FML had a SecurityManager set;
  Java 25 has none, and its `ServiceLoader` throws while looking the class up, outside `IIORegistry`'s catch.
  With any OneConfig mod, OneConfig's loader touches ImageIO at launch and the game exits. The caller is in
  OneConfig's own class loader, out of the compat pass's reach, so `LaunchClassLoader.getResources` (the context
  class loader ImageIO uses) serves those lists without the classes it can't find (`ImageIoProviders`).

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
- **Done (Phase 6, 2026-10-25): the game runs on LWJGL 3.3.6** (`compat-lwjgl2`). How it's built:
  - Why 3.3.6 and not 3.4: everything written for LWJGL 3 on these game versions (lwjgl3ify/GTNH,
    PojavLauncher, OneConfig's NanoVG renderer) is compiled against 3.3.x, and 3.4 changed signatures
    (`nvgCreateFontMem`'s `int freeData` became `boolean`), so they'd fail with `NoSuchMethodError`.
  - Classes LWJGL 3 doesn't have (`Display`, `Keyboard`, `Mouse`, `Cursor`, `Controllers`, `Sys`,
    `LWJGLUtil`, `GLContext`/`ContextCapabilities`, `SharedDrawable`, `Pbuffer`, `ALCdevice`, ...) are in
    `compat-lwjgl2` under their LWJGL 2 names, on GLFW. Mixins and coremods that target them still match.
    Their logic lives in `dev.lich.compat.lwjgl2.display` (one copy, class-loader-excluded).
  - Members LWJGL 3 dropped or changed from classes both have go through one `CallTable` in the compat
    pass, run as Lich's *last* transformer (after mods' transformers and Mixins have matched the original
    calls) and in the system class loader (lwjgl_util, paulscode, LaunchWrapper's exclusions). 734 are
    renames generated from both jars (`renames.txt`: `glGetFloat(int, FloatBuffer)` → `glGetFloatv`,
    `EFX10` → `EXTEfx`); the rest are hand-written static mirrors (`Lwjgl2GL11`, `Lwjgl2AL`, ...:
    typeless pointer functions, `ByteBuffer` shader sources, `GLSync`, debug callbacks, `PointerBuffer`).
    `ContextCapabilities` is generated from LWJGL 2's fields, filled from LWJGL 3's `GLCapabilities` or
    the extension string.
  - `:compat-lwjgl2:checkLinkage` lists every LWJGL reference in the game, Forge and the mod corpus that
    still wouldn't link after the table: the to-do list, and the regression check. Left on 2026-10-25:
    only lwjgl_util internals nothing calls (jinput, mapped objects) and optional LWJGL 3 modules that
    Essential and ReplayMod probe for (Lich ships stb, tinyfd, tinyexr and nanovg, so they resolve).
  - The system class loader picks the LWJGL: when LWJGL 3 and `compat-lwjgl2` are both on the class path
    it drops LWJGL 2's jar (lwjgl_util stays: it's plain Java on top). `-Dlich.lwjgl=2` keeps LWJGL 2 and
    drops the other two instead, for comparisons. LWJGL 3's jars count as Lich's (hidden from FML, no compat
    pass). The installer ships LWJGL 3 with every platform's natives jar on the class path; LWJGL 3
    extracts the right one itself, so launchers' native extraction (LWJGL 2's) doesn't matter.
  - Behavior kept from LWJGL 2, where the game can tell: a key press and the character it typed are one
    event (GLFW sends two callbacks); repeats are queued and skipped unless enabled; printable keys get
    the code of what the layout prints on Windows/Linux (`KEY_Z` for German Z), positional on macOS;
    coordinates are framebuffer pixels from the bottom left; the wheel counts 120 per notch; grabbing
    keeps OS mouse acceleration (no raw input); `Display.create` creates Mouse and Keyboard; window
    messages are processed only on the main thread (Forge's splash thread swaps but doesn't poll);
    `Display.sync` sleeps then yields like LWJGL 2's; `Sys.getTime()` is milliseconds from `nanoTime`
    (the tick timer compares it with `nanoTime`, so its sync adjustment stays at 1: not server-visible).
  - `Sys.getVersion()` returns LWJGL 3's version, so logs and crash reports say what really ran.
  - LWJGL 3 aborts the JVM on a GL call from a thread with no context, where LWJGL 2 threw. The game's
    crash report asks for `glGetString` regardless, so that one is mirrored to throw like LWJGL 2 (it's
    never called per frame); otherwise an early crash ends without a report.
  - **Mods that bring their own LWJGL 3** (OneConfig, for NanoVG, isolated in its own class loader): two
    LWJGL 3 copies in one JVM overwrite each other's per-thread GL function table (kept in the JNIEnv),
    and the game's next GL call aborts. OneConfig skips its copy when it finds PojavLauncher's LWJGL 3,
    detected by `org.lwjgl.glfw.CallbackBridge`; the call table renames that string to `GLFW` in classes
    that also name `org.lwjgl.system` (only OneConfig in the corpus checks it). Lich ships NanoVG for it,
    and mirrors the one NanoVG function whose signature changed since the LWJGL 3.3.1 OneConfig was built
    against (`Lwjgl331NanoVG`). OneConfig also removes LaunchWrapper's `org.lwjgl.` exclusion, so boot puts
    it in `LichExclusions` (§4.2).
  - **Private fields mods reflect on** keep LWJGL 2's names and types: `Mouse.x`, `y`, `event_x`, `event_y`
    hold the position the getters return, refreshed when LWJGL 2 refreshed them (`poll` from
    `Display.update`, `next`, `setCursorPosition`, grabbing). Essential's `GlobalMouseOverride` sets them to
    move the position the game sees while it draws an overlay; without them it crashed the game seconds after
    joining a world (found 2026-10-27 by the modpack harness, SkyBlock Enhanced). `Mouse.readBuffer` holds the
    events `next` hasn't returned yet, in LWJGL 2's 22-byte layout: TC4Tweak checks it for pending events
    (The Lost Era).
  - **Tested (Windows x64):** vanilla 1.8.9 to the main menu; Forge 1.7.10 and 1.8.9 through the
    loading screen into a local server; typing in chat (shift, punctuation, backspace), turning the
    camera, clicking menus, F11 fullscreen and back; the mod corpus (§7). **(verify)** Linux X11/Wayland,
    macOS (Apple Silicon): GLFW runs there as `glfw_async` unless the JVM has `-XstartOnFirstThread`.

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
   │    6. Lich last: Java 25 game fixes on SRG names (now ClassPatches, later Mixins), the LWJGL 2 → 3
   │       call table (§2.5), frame recomputation
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
- Mixin refmaps target SRG in all cases. That's what the 2k client builds already produce.
- Dev mappings (what `mcdev` compiles Lich mods against): for 1.8.9 MCP `stable_22`; for 1.7.10 the MCP names
  Forge 1.7.10 ships in its userdev jar (`conf/fields.csv`, `methods.csv`), which ForgeGradle and
  RetroFuturaGradle use by default, so 1.7.10 Forge mod code ports unchanged. (Changed 2026-10-25 from
  `stable_12`, which names many members Forge's names leave as `func_…` and renames a few, like
  `fontRenderer` → `fontRendererObj`: porting 2k Client hit ~40 of them.) The compile jar also gets the
  `InnerClasses`/`EnclosingMethod` attributes from MCP's `exceptor.json`, which 1.7.10's client lacks.

### 3.2 Gradle modules (Java 25 toolchain everywhere)
| Module | Contents |
|---|---|
| `boot` | Main class, flag checks, profile selection, crash-report hints ("mod X used removed JDK API Y") |
| `launchwrapper` | Own implementation of `Launch`, `LaunchClassLoader`, `ITweaker`, `IClassTransformer`, `IClassNameTransformer`, `LogWrapper`, blackboard |
| `transform` | The compat pass (`CompatTransformer`): type and string renames, call redirects, class patches |
| `remap` | obf → SRG runtime remapper for the vanilla profile |
| `compat-jdk` | Shims for removed JDK APIs and their redirect table: Pack200 (nested commons-compress), SecurityManager, reflection compat |
| `compat-asm` | ASM 5 classes ASM 9 removed (verbatim), redirects for ASM 5 behavior |
| `compat-lwjgl2` | LWJGL 2 API on LWJGL 3.3.x (§2.5), its call table and the linkage check |
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
- Differences from 1.12, all in Lich's favor and covered by tests: Lich's first and last transformers
  also run on transformer-excluded classes; `getSources()` leaves out the jars in `LichSources` (Lich's
  own, ASM 9, LWJGL 3), so FML's mod discovery never scans them; and prefixes in `LichExclusions` always
  go to the parent, kept outside `classLoaderExceptions` so mods can't remove them (§2.5: OneConfig removes
  `org.lwjgl.`, which on LWJGL 3 would load a second LWJGL).
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
  `AutomaticJava=false`, or Prism falls back to Java 8. The instance keeps its LWJGL 2 component: Lich's
  component brings LWJGL 3 and its natives, and Lich's system class loader leaves LWJGL 2 out (§2.5).
- No custom launcher.

---

## 5. Lich mods

### 5.1 Format: `lich.mod.json` at the jar root
```jsonc
{
  "schema": 1,
  "id": "twokclient",
  "version": "2.0.0",
  "name": "2k Client",
  "authors": ["..."],
  "minecraft": "1.8.9",                 // one jar per MC version (multi-version jars: maybe later)
  "depends": {
    "lich": ">=0.4",
    "forge": "optional"                 // "required" | "optional" | "incompatible"
  },
  "entrypoints": {
    "preLaunch": ["dev.twok.client.lich.PreLaunch"],   // before game classes load
    "init":      ["dev.twok.client.lich.Init"],        // after game bootstrap, before first frame
    "client":    ["dev.twok.client.lich.ClientInit"]   // after the Minecraft instance is ready
  },
  "hooks": ["render.hud", "input.key", "chat.receive"], // Lich weaves only the hooks loaded mods list (§5.2)
  "provides": [],                                      // subsystems this mod replaces, e.g. "render.chunkMesh"
  "mixins": ["mixins.twokclient.json"],
  "accessWidener": "twokclient.lich.aw",               // make fields/methods public / non-final
  "transformers": [],                                  // raw ASM, discouraged, needs explicit opt-in
  "javaVersion": 25
}
```
- **Discovery**: jars in `mods/` that contain `lich.mod.json`. They are **hidden from FML's
  `ModDiscoverer`**, so FML never scans them.
- **Dual jars**: a jar that has both `lich.mod.json` and `mcmod.info`/`@Mod` is claimed by Lich when
  running under Lich, and still works as a plain Forge mod elsewhere. That lets 2k client ship one jar
  during the transition.
- **Dependency resolution**: semver ranges, clear error screens for missing or incompatible mods,
  load-order sorting.
- **Forge interop**: a Lich mod with `forge: optional` can check `Lich.isForgePresent()` and use Forge
  APIs only behind that check.

### 5.2 Lich API: deep hooks (both profiles, implemented with Lich's own Mixins)
Lich itself doesn't change the game (§0); it gives Lich mods the means to. The hooks go deep enough
that an aggressive optimisation mod (§5.3) or a full client (2k client) needs no private Lich internals.

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

Every time a first-party mod (perf, 2k client) has to fall back to a Mixin for something general, that's
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
  so session join, RSA and AES/CFB8 work); **works for 1.8.9** too, zlib included (2026-10-27: the user played on
  a public server with their account in `lich-forge-1.8.9`). Mojang session-server
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
- Manual checks on big public servers (e.g. Hypixel) before each release. First one done 2026-10-27: PvP on a
  public server with an anticheat through Lich on Java 25, no flags or bans.

---

## 7. Testing
- **Modpack harness** (`test/harness`, 2026-10-27): Forge mod compatibility is tested with whole modpacks
  instead of one mod at a time (much faster, and mods meet each other as they do for players). A pack is
  pinned in `packs.json` (a Modrinth `.mrpack` and its SHA-512). `:harness:fetchPack` installs it into
  `run/packs/<pack>/minecraft` the way a launcher would, every file hash-checked; packs that leave their mods
  to McInstanceLoader get its list resolved directly (the loader itself is left out: it downloads through a
  third-party CurseForge proxy with certificate checks off). `:harness:runPack` starts the local test server,
  then runs each profile (`forge` on Java 8, `lich-forge` on Java 25) from a fresh copy of that directory
  through devlaunch, joins the server and stays 60 s in the world, and writes `run/harness/<pack>/report.md`:
  each outcome, mods loaded, time to menu and join, then the warnings/errors and mixins that only one side
  logged (normalized: no times, threads, hashes or game paths). The Java 8 run gets a copy of LWJGL 2's
  `Mouse` that never grabs or moves the real cursor (`NoGrabMouse`), the counterpart of
  `-Dlich.dev.noMouseGrab`. `:harness:prismPack` makes the user's Prism instances of the same pack,
  `<pack>-forge-<mc>` and `<pack>-lich-<mc>`, for testing by hand. A pack's `setup` answers first-launch prompts
  once in the template, the way a player would, so runs are unattended (each run is a fresh copy, so a prompt would
  otherwise come up every time, on the desktop): for SkyBlock Enhanced, PackCore's config-preset dialog (the 1080p
  preset, `PromptSetDefaultConfig` off).
  - Packs: **The Lost Era 1.6.10** (1.7.10, 185 mod jars: IC2, BuildCraft, Thaumcraft 4, AE2 GTNH,
    Mekanism, Thermal, Tinkers, Botania, Witchery, Railcraft, Forestry, GregTech 4, ...) and **SkyBlock
    Enhanced 2.3.21** (1.8.9, 20 mods: Essential, OneConfig mods, NEU, SkyHanni, Skytils, Dungeons Guide,
    Phosphor, FoamFix, ...). 1.8.9 has no big content packs; its corpus entries (below) cover the rest.
  - SkyBlock Enhanced (2026-10-27): found two Lich bugs, broken ImageIO provider lists (§2.4) and
    Essential's reflection on `Mouse` (§2.5); with both fixed it joins and stays on both profiles, 28 mods,
    727 vs 726 mixins applied. What's left in "only with Lich" is explained: Essential relaunches later on Lich
    (its trigger on Java 8 is Forge's ASM 5.0.3, which Lich replaces), so its first, abandoned launch logs a
    few extra warnings; Skytils tries to install its own SecurityManager when FML's is missing and logs the
    failure.
  - The Lost Era (2026-10-27): found three Lich bugs, ASP + GS Patcher's compressed frames in a Java 5 class
    (ASM 9 throws, ASM 5 wrote them: `Asm5Frames`), JAXB (§2.3) and TC4Tweak's reflection on `Mouse.readBuffer`
    (§2.5). With them fixed both profiles join and stay: 255 mods, 896 mixins applied on each side and none on
    only one, 1217 warnings and errors each. Lich reached the menu in 48 s (Java 8: 65 s) and joined in 122 s
    (196 s). The one real difference: ModularUI turns its custom cursors off when `Sys.getVersion()` is 3+
    (written for lwjgl3ify, which has no LWJGL 2 cursors), so its screens show the normal cursor.
- **Harness**: a `testmod` (built as both a Forge mod and a Lich mod). With `-Dlich.test=…` set it
  loads a world, runs N ticks, optionally joins a local server, dumps a report, and exits with a status
  code.
- **CI**: Linux + Xvfb + Mesa llvmpipe. Matrix: {1.7.10, 1.8.9} × {vanilla, forge, forge+modset,
  lich-mods} × {Java 8 baseline, Java 25}, plus the determinism replay and anticheat jobs.
- **Mod corpus** (2026-10-24, from Modrinth, newest Forge build of each, dependencies in the same folder,
  each run alone through devlaunch's `lich-forge` profile, joining the local test server for 25 s, game
  muted). Every entry passes on LWJGL 2; on LWJGL 3 (Phase 6, 2026-10-25) every entry passes, Hodgepodge
  included:
  - 1.8.9 (22): 2k client + OptiFine, 3D Skin Layers, Biomes O' Plenty, Blood Magic, Chatting, Controlling,
    CraftTweaker, EntityCulling, Essential, FoamFix, In-Game Account Switcher, JEI, NotEnoughUpdates,
    PolyPatcher, Replay Mod, SecurityCraft, SkyblockAddons, SkyHanni, Storage Drawers, Wavey Capes,
    Xaero's Minimap, Xaero's World Map.
  - 1.7.10 (25): 2k client + OptiFine, Aether, ArchaicFix, Biomes O' Plenty, Blood Magic, Botania,
    ChickenChunks, Controlling, CraftTweaker, Draconic Evolution, Ender Storage, EntityCulling
    (UniMixins), Fisk's Superheroes, FoamFix, Hodgepodge, JourneyMap, Mekanism (+ ForgeMultipart),
    Nature's Compass, NEI, OpenComputers, SecurityCraft, Storage Drawers, Tinkers' Construct, Wawla,
    Xaero's Minimap.
  - Compare mixins applied on Java 8 and Lich with `-Dmixin.debug.verbose=true` and the
    `Mixing … into …` lines. The scripts used are throwaway; the harness (above) replaces them.
- **Mod corpus ideas**: a pinned list of popular mods per version.
  - 1.8.9 ideas: OneConfig-based mods, SkyblockAddons, NotEnoughUpdates, Skytils, OldAnimations,
    Chatting, 2k client.
  - 1.7.10 ideas: NEI/CodeChickenCore, ForgeMultipart (Scala), Thaumcraft, IC2, BuildCraft, AE2,
    OpenComputers, 2k client.
- **LaunchWrapper conformance**: the same tweaker/transformer scenarios run on real LaunchWrapper
  (Java 8) and Lich's, with the same results.

---

## 8. Roadmap

| Phase | Goal | Done when |
|---|---|---|
| 0. Setup ✅ | Gradle multi-project on JDK 25 + `devlaunch`; still to do: decompile LaunchWrapper 1.12, FML 1.7.10/1.8.9; read lwjgl3ify/RFB | **Done 2026-10-24:** repo builds; vanilla 1.8.9 and 1.7.10 reach the main menu on **Java 8** through `devlaunch` (baseline). The Lich version JSON generator moves to Phase 1, since there's no `boot` to point it at yet. |
| 1. Vanilla 1.8.9 on 25 🟡 | `boot` + `launchwrapper` + flags + log4j safety + profile generator. The SRV fix came free with the class loader parent. The Twitch null stream moved to the perf mod (Phase 8): on 25 it only loads a few unused DLLs | **So far (2026-10-24):** main menu and joining a local server on Java 25 with zero JVM warnings. Prism test instances `lich-1.8.9`/`lich-1.7.10` start Lich on Java 25. On 2026-10-27 the user ran a singleplayer world and a public server with their account in `lich-forge-1.8.9`, which covers boot, launchwrapper and login on 1.8.9 (in the Forge profile). **Left:** the vanilla profile with a real account: it has no Prism instance any more, so only devlaunch (offline) runs it |
| 2. Vanilla 1.7.10 on 25 ✅ | Same for 1.7.10. No 1.7.10-specific code was needed | **Done 2026-10-24:** in Prism with the user's account, a singleplayer world and a public server (as.minemen.club, about 40 s, chat working), Java 25 |
| 3. Forge, no mods 🟡 | Runtime binpatching through a Pack200 shim (§4.5), ASM 9 + ASM 5 shims, SecurityManager strip, reflection compat for ObjectHolderRef/EnumHelper (§4.4), Lich's system class loader, hiding Lich's jars from FML, the SoundManager race fix (§2.4) | **So far (2026-10-24):** Forge 1.7.10 and 1.8.9 load ("successfully loaded 3 mods") and reach the main menu on Java 25 through devlaunch, with zero JVM warnings; their logs match the Java 8 Forge baseline apart from Lich's own lines. In Prism, `lich-forge-1.8.9` with the user's account ran a singleplayer world (create, play, save, quit) cleanly. On 2026-10-27 the user ran singleplayer and a public server with their account in both `lich-forge-1.8.9` and `lich-forge-1.7.10`. **Left:** the mod list screen |
| 4. Forge mods 🟡 | Mixin provider, redirect table, reflection compat, JDK shims, harness + corpus | Agreed % of the corpus loads; failures name the mod and the API. **So far (2026-10-24):** a 47-entry corpus (§7) joins a local server and stays in the world through Lich on Java 25, all but one (Hodgepodge: needs LWJGL 3; passes since Phase 6). OptiFine + 2k client on both versions apply exactly the mixins they do on Java 8. **2026-10-27:** the modpack harness (§7): The Lost Era (1.7.10, 255 mods) and SkyBlock Enhanced (1.8.9, 28) join and stay on Forge/Java 8 and Lich/Java 25 with the same mixins applied, after five Lich fixes they turned up; the user has Prism instances of both packs for each. **Left:** a singleplayer-world harness (worldgen and content mods were only tested client-side), the mod list screen, more packs, the Mixin provider for Lich mods (Phase 5) |
| 5. Lich mods v0 | `remap` (vanilla-profile SRG), `loader`, `lich.mod.json`, `api` v0 (lifecycle, events, HUD, keybinds, config; hooks woven on demand, §5.2), FML-hiding, dual jars | 2k client runs as a Lich mod on both versions, with and without Forge; with no Lich mods installed, every game class is byte-identical to what Lich defines without `loader`/`api` (a harness check). **So far (2026-10-25):** 2k Client is ported (worktrees `lich-1.8.9`, `lich-1.7.10`) and runs through devlaunch on both versions in the `lich` and `lich-forge` profiles, with OptiFine too; its menus and HUD match its Forge builds. The F3 text hook works on vanilla 1.7.10. Both Prism instances run it (1.7.10 with OptiFine E7); against OptiFine 1.8.9 L5's patched classes every Lich and 2k injector still finds its target (checked statically, not yet in game). **Left:** the byte-identical harness check |
| 6. LWJGL 3 🟡 | `compat-lwjgl2` + redirects; Prism LWJGL override | Windows/Linux/macOS ARM native; mods calling GL11/Display/Keyboard work. **So far (2026-10-25, Windows x64):** the game runs on LWJGL 3.3.6 in every Lich profile (§2.5): vanilla 1.8.9, Forge 1.7.10 and 1.8.9 through devlaunch and Prism (`lich-forge-1.8.9`, offline); input, menus, fullscreen checked by hand; all 47 corpus entries join a server (§7), Hodgepodge and the OneConfig mods included; `checkLinkage` is clean. On 2026-10-27, singleplayer and a public server with the user's account in both Prism instances (`lich-forge-1.8.9`, `lich-forge-1.7.10`). **Left:** Linux (X11, Wayland) and macOS Apple Silicon, controllers (untested) |
| 7. Server safety gate | Determinism replay + anticheat CI jobs; Mixin deny-list enforcement | Zero packet diffs vs Java 8 vanilla; zero anticheat flags. **So far (2026-10-27):** the manual check: the user PvPed on a public server with an anticheat through Lich on Java 25, with no flags or bans. **Left:** the determinism replay, the anticheat CI job and the Mixin deny-list check |
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
