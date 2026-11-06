# Lich — design and internals

The detailed companion to the [README](README.md): the design decisions, everything that breaks when
1.7.10/1.8.9 run on Java 25 and how Lich fixes it, the architecture, the Lich mod format, the test setup
and the roadmap. It's also the project's source of truth: when a decision changes, it changes here first.

A client-side runtime and mod loader that runs Minecraft 1.7.10 and 1.8.9 on Java 25. It patches game,
Forge and mod bytecode at load time so that unmodified Java 8 Forge mods work. It also loads its own
mod format, whose hooks go as deep into the game as possible, so mods get maximum control. Lich itself only
loads mods: the game plays as vanilla (or Forge) does, and anything beyond running on Java 25 is a mod.

Items marked **(verify)** come from memory or second-hand sources and need checking against the real
jars before building on them. Dates are when a finding was made or a decision taken.

## 0. Decisions

| Topic | Decision |
|---|---|
| LaunchWrapper | Lich has its own. RetroFuturaBootstrap and lwjgl3ify are references for behavior; code is only taken where the licenses allow it. |
| Forge mods | They load through real Forge in Forge's own format (`@Mod` + `mcmod.info`), patched at load time, and are meant to behave as they do on Java 8 without changes or Lich-specific setup. Lich's hooks stay out of their way: with no Lich mods, none is woven. |
| Lich mods | Their own format (`lich.mod.json`), with or without Forge. |
| Game versions | 1.7.10 (Forge `10.13.4.1614`) and 1.8.9 (Forge `11.15.1.2318`). Version-specific code stays isolated, so 1.12.2 can follow. |
| Side | Client, plus the integrated singleplayer server that ships in the client jar. Joining a public server should look and behave like a normal client (§6). |
| Java | 25 for Lich and Lich mods. Forge mods stay Java 8 bytecode. |
| Game logic | Core touches the game where Java 25 needs it to (JDK/ASM redirects, reflection compat, fixes for bugs Java 25 exposes such as the sound race). With no Lich mods the game plays as vanilla or Forge does on Java 8. |
| Hooks | Lich mods get hooks as deep as the game allows (§5.2): events, replaceable subsystems, Mixins, access wideners, raw transformers, so mods have as much control as they need. A hook is woven only when a loaded mod asks for it. |
| Branding | Lich's lines where the game says what it runs on. Under Forge, FML's "Powered by Forge" line is dropped and two entries go into its branding list, before FML's mod count: `Lich <version> with Forge <forge version> on Java <n>` and the Lich mods' `<n> mods loaded, <m> mods active`. In the vanilla profile, the main menu shows `Minecraft <mc>`, `Lich <version> on Java <n>` and the same Lich mod count, top to bottom. Text only; the brand servers see stays `vanilla`/`fml,forge` (§6.2). |
| Releases | The first public release is 0.3.0. Lich's version (`gradle.properties`) is what Prism's component list, the boot log line, the branding and the installer show. |

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
| `Field.modifiers` reflection hack | 12 | Forge `ObjectHolderRef` (both versions, fatal at startup), `EnumHelper`, many mods ("setFinalStatic") | **Done (Phase 3, completed 2026-10-31):** clearing `final` through the dummy works as on Java 8, and every `Field.set*` in those classes writes static finals, §4.4 |
| `sun.reflect.ReflectionFactory.newFieldAccessor`/`newConstructorAccessor`, `sun.reflect.FieldAccessor`/`ConstructorAccessor` | 9 | `ObjectHolderRef`, `EnumHelper`, mods that copied them | **Done (Phase 3):** Lich look-alikes, §4.4 |
| `java.util.jar.Pack200` | 14 | FML `ClassPatchManager` (`binpatches.pack.lzma`) | **Done (Phase 3):** a Pack200 shim at runtime (§4.5), not install-time pre-patching |
| JSR-250 `javax.annotation.Resource` | 11 (JEP 320) | Mixin 0.7's `LegacyTransformerHandle` (fatal at launch with any Mixin mod) | **Done (Phase 4):** renamed to a look-alike annotation in `compat-jdk` |
| JAXB `javax.xml.bind` (`DatatypeConverter`, `JAXBContext`) | 11 | Mods doing base64/hex; Enhanced Loot Bags reads its XML config with it (The Lost Era) | **Done (2026-10-27):** Lich ships JAXB 2.3 (the last line with the `javax.xml.bind` package: `jakarta.xml.bind-api` 2.3.3, `jaxb-runtime`/`txw2` 2.3.9, `istack-commons-runtime` 3.0.12, `jakarta.activation` 1.2.2), hidden from mod discovery (the jars have a `module-info`) and class-loader-excluded, so there's one copy as there was in the JDK. The runtime registers itself through `META-INF/services`, which `JAXBContext` finds through the context class loader |
| `javax.activity.InvalidActivityException` (CORBA) | 11 (JEP 320) | LiteLoader 1.7.10 (`LiteLoader.getMod`; fatal at its bootstrap): Blightfall, The 1.7.10 Pack | **Done (2026-10-27):** renamed to a look-alike in `compat-jdk` (same superclass, `RemoteException`, and constructors) |
| Nashorn `jdk.nashorn.*` | 15 | Scripting mods: Fisk's Superheroes (hero packs in JS) | **Done (Phase 4):** Lich ships `nashorn-core` 15.7 (on its ASM 9, hidden from mod discovery, a class loader exclusion) and the compat pass renames `jdk/nashorn/` → `org/openjdk/nashorn/`, plus the class names mods pass to `Class.forName`. Its engines load game classes through the context class loader (LaunchClassLoader) |
| `sun.misc.BASE64Encoder/Decoder` | 9 | Old mods/libs | Not done, no tested mod or pack has needed it yet. Plan: redirect to a shim |
| `sun.reflect.Reflection.getCallerClass` | 9 | log4j 2.0-beta9 (has a fallback), mods | Not done, no tested mod has needed it yet. Plan: a shim on `StackWalker` |
| `Thread.stop/suspend/resume` now throw | 20 | Rare mods | Not done. Plan: redirect to a no-op that logs a warning |
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
    are exactly those applied on Forge/Java 8 (`-Dmixin.debug.verbose`), with the same warnings.
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
  as is and core leaves it alone (§0, game logic).
- **Sound race in `SoundManager` (1.7.10 and 1.8.9, fixed in Phase 3)**: a resource reload during the
  async "Sound Library Loader" starts a second sound system next to the first. OpenAL refuses the
  second context, and the game has no sound until the menu music crashes it (`UnsatisfiedLinkError` in
  `AL10.nalGetSourcei`). FML reloads right after the first load; on Java 25 Forge 1.7.10 lost the race
  every time, while Java 8 narrowly wins it. `shared-compat-mc`'s `SoundLoaderRace` makes a reload wait
  for the loader thread. Like every game fix it keys on SRG names, so it runs in both profiles (the vanilla
  profile has them through `remap`), but not in a vanilla install without MCP's mappings.
- **Shutdown race in 1.8.9's `IntegratedServer.initiateShutdown`**: "Save and Quit" sends the disconnect, then
  queues a log-out task for the server thread and waits for it. If the server has already seen the disconnect and
  left its tick loop, the task never runs and the client freezes on "Saving world" for good. Vanilla's race (the
  Java 8 baseline froze once in two packs), but Lich froze 2 of 2 (found 2026-10-31, CubeSoft and SolitaryCraft
  VanillaPlus). `v1_8_9-compat-mc`'s `ShutdownRace` ends the wait once `isServerStopped()`: stopping has saved
  and removed the players by then, which is all the task does. 1.7.10 has no such task. Checked 2026-10-31: CubeSoft,
  SolitaryCraft VanillaPlus, Lucky Block Add On and the 1.8 PvP Pack all save, quit and reopen their world in world mode
  on Lich, both quits included.
- **User-Agent** (found 2026-10-31, Survival Industry): `HttpURLConnection` sends `Java/<version>`, and Cloudflare
  answers Java 8 and Java 25 differently: pastebin refuses `Java/1.8.0_*` with a 403, which mods catch, but redirects
  `Java/25` to HTTPS with an HTML page, which LLibrary 1.5.1 parsed as its patron list and crashed on in mod
  construction. Boot sets `http.agent` to `Java/1.8.0_51` (the Java Mojang's launcher shipped) unless the user set
  one, so requests say `Java/1.8.0_51 Java/25...` and get Java 8's answers.
- **Access token in the game log (fixed 2026-10-31)**: vanilla 1.7.10 and 1.8.9 log `(Session ID is token:<access
  token>:<uuid>)` at startup, in plain text in `logs/latest.log`, which users paste into support
  chats. Forge (both versions, checked in the logs) drops the line; vanilla doesn't, so Lich's vanilla profile
  logged it. Boot's `SessionLogFilter` drops it, and any other line containing the `--accessToken` value (16+ chars,
  so offline placeholders don't count), in both profiles and whatever the launcher's log4j config. log4j keeps a
  context per class loader and the game logs through LaunchClassLoader's, so boot sets `Log4jContextSelector` to a
  selector that wraps the launcher's (or log4j's default) and filters every context it hands out; the filter goes
  back on after a reconfiguration. Checked: vanilla 1.7.10 and 1.8.9 through devlaunch drop the line, Forge 1.7.10's
  log is otherwise unchanged.
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
- **SHA-1-signed mod jars count as unsigned** (found 2026-10-27, FTB Infinity Evolved): `jdk.jar.disabledAlgorithms`
  has `SHA1 denyAfter 2019-01-01` (Java 8 too since 8u351, so a current Java 8 fails the same way; the packs' own
  launchers ship older ones). Forge hands a mod its classes' certificates, mods compare them with their fingerprint,
  and EnderTech stops the game when they're missing. The JDK rule stays: `LegacySignatures` gives the certificates
  back only for jars pinned by the SHA-256 of the whole file (`legacy-signed-jars.txt`: IC2, EnderTech, Bigger
  Packets, LunatriusCore, InGameInfoXML, Mercurius, each from a well-known pack and checked to verify in full with
  SHA-1 allowed), so a modified jar stays unsigned. Transformer-excluded classes from those jars are defined by Lich
  too, because a package's classes must all have the same signers.
- **Writes to `LaunchClassLoader.getSources()`** (found 2026-10-27, Blightfall): GTNH's `MinecraftURLClassPath.addJar`
  (UniMixins, TC NEI Additions' mixins) adds a jar with `addURL`, then removes it from the sources so FML's mod
  discovery doesn't find it twice. Lich's `getSources()` hides Lich's jars and returned a copy, so the removal was
  lost and FML stopped on "Found a duplicate mod". It's a live view now (`VisibleSources`).
- **JDK-internal ImageIO**: Decocraft's model loader (CraftStudio) constructs `com.sun.imageio.plugins.png.PNGImageReader`;
  one more `--add-exports` in `jvm-flags.txt` (FTB SkyFactory 2.5). JourneyMap 5.1's `PixelPaint` writes map tiles
  through `sun.awt.image.IntegerComponentRaster`, and without the export every chunk it renders fails (found
  2026-10-31, LOTR Survival and Lucky Block: thousands of "Error rendering chunk"); another `--add-exports`.
- **Mods that hash ASM's text output** (found 2026-10-27, FTB Infinity Evolved): Logistics Pipes recognizes Thermal
  Dynamics' item duct renderer by a SHA-1 of `Textifier`'s dump of one method, and stops the game when it doesn't
  know the hash. ASM 6+ prints interface calls with `(itf)` and number constants with a type suffix, so under Lich's
  ASM 9 the hash never matched. The compat pass gives mod code an ASM 5.0.3-style printer (`Asm5Textifier`: mods'
  Textifier subclasses are rebased onto it, their `new Textifier()` swapped); LP's own check now yields the hash it
  expects for TD 1.2.1.
- **Classes a transformer makes from nothing** (found 2026-10-27, Blightfall, The 1.7.10 Pack): UniMixins generates
  GTNH's `com.gtnewhorizon.mixinextras` classes at load time. Lich's first transformers had seen no bytes for them, so
  the compat pass never reached them, and GTNHMixins failed to start: no early mixins at all (ArchaicFix's
  `CraftingManager` mixin, BugTorch's config). `LaunchClassLoader` now runs Lich's first transformers again on a class
  that only a registered transformer produced.
- **UniMixins 0.1.15's relocated ASM 9.5** stops at Java 21 class files. Renaming it to Lich's ASM (as Mixin 0.7's
  ASM 5.2 is) cut UniMixins' bridges for 0.7-era plugins (CoreTweaks' plugin failed), so a relocated ASM 9 is kept and
  only its `ClassReader` limit is raised to Java 25 (`ShadedAsmClassReader`).
- **LiteLoader's class path injection** (open): LiteLoader 1.7.10 reflects on `sun.misc.URLClassPath` to put jars at a
  chosen place in the class path, logs an error when the class isn't there, and appends them with `addURL` instead.
  Litemods load; only their order relative to other jars differs from Java 8.

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
    keeps OS mouse acceleration (no raw input) unless a Lich mod turns raw input on (`RawMouseInput`, off by
    default); `Display.create` creates Mouse and Keyboard; window
    messages are processed only on the main thread (Forge's splash thread swaps but doesn't poll);
    `Display.sync` sleeps then yields like LWJGL 2's (on Windows the sleep is one wait on a high-resolution
    waitable timer, `-Dlich.preciseSync=false` for LWJGL 2's 1 ms sleeps), then processes window messages again, so the next frame
    reads input from after the sleep, not from before it (`-Dlich.lateInputPoll=false` turns that off; input
    the game reads is otherwise the same: movement accumulates, events queue in order); `Sys.getTime()` is milliseconds from `nanoTime`
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
    (The Lost Era). `Mouse` declares all 35 of LWJGL 2.9's fields in its order, the unused ones too, because
    Witching Gadgets takes `getDeclaredFields()[17]` for `readBuffer` (found 2026-10-31, GT New Horizons: an
    `ArrayIndexOutOfBoundsException` at init, then a null buffer when the Thaumonomicon search opens).
  - **`org.lwjgl.MemoryUtil`** (LWJGL 2's buffer addresses and string encoding; found 2026-10-31, GT New Horizons):
    Angelica's Sodium writes vertex data through `getAddress`, and without the class the game crashed on joining a
    world. Lich's copy has LWJGL 2's whole public API, on LWJGL 3's `org.lwjgl.system.MemoryUtil`.
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
   │    6. Lich last: Java 25 game fixes on SRG names (ClassPatches), the LWJGL 2 → 3
   │       call table (§2.5), frame recomputation
   ▼
Lich mod loader ───────────────┬──── FML (forge profile) loads Forge mods as usual
   preLaunch → init → client   │     Lich jars are hidden from FML discovery
   ▼                           ▼
Minecraft  ◄── Lich API (events, deep hooks) in both profiles
```

### 3.1 One runtime namespace: SRG
1.7.10/1.8.9 have no official Mojang mappings, and Forge runs the game with SRG names. Lich runs the
game **in SRG in both profiles**. In the vanilla profile, Lich does the obf → SRG remap itself, using MCP
SRG data downloaded at install time (not bundled). Because of this:
- One Lich mod jar (SRG-reobfuscated, same as a Forge mod) works with and without Forge.
- Mixin refmaps target SRG in all cases. That's what Lich mod builds produce.
- Dev mappings (what `mcdev` compiles Lich mods against): for 1.8.9 MCP `stable_22`; for 1.7.10 the MCP names
  Forge 1.7.10 ships in its userdev jar (`conf/fields.csv`, `methods.csv`), which ForgeGradle and
  RetroFuturaGradle use by default, so 1.7.10 Forge mod code ports unchanged. (Changed 2026-10-25 from
  `stable_12`, which names many members Forge's names leave as `func_…` and renames a few, like
  `fontRenderer` → `fontRendererObj`.) The compile jar also gets the
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
| `compat-forge-<ver>` | Changes to FML itself: how Lich and FML share a game (FML's discovery leaves Lich mods alone). FML's Java 25 fixes are generic and live in `compat-jdk`/`compat-asm` |
| `compat-mc-<ver>` | Java 25 fixes to the game, as ClassPatches on SRG names; fixes identical in both versions live in `shared-compat-mc` |
| `loader` | Lich mod discovery, descriptor parsing, dependency resolution, entrypoints, Mixin configs, access wideners, FML-hiding |
| `api` + `versions/<mc>/api` | Public Lich API: the version-neutral half, and per version the events with game types and the hooks' Mixins (sources shared by both versions in `versions/shared/api`) |
| `mixin-provider` | The Mixin Lich mods run on (Fabric's Mixin 0.8.7 + MixinExtras), started only when Lich mods are installed |
| `installer` | Version JSON / Prism component, LWJGL 3 + libs, MCP SRG data |
| `testmod` + `harness` | Automated tests (§7) |
| `mcdev` (included build) | `remap-core` (mappings, hierarchy-aware remapper, also used by `remap` at runtime) and the `dev.lich.mod` Gradle plugin that Lich mods and the per-version API modules build with (§3.1) |

To add 1.12.2 later, add `compat-forge-1.12.2`, `compat-mc-1.12.2` and `api-1.12.2`. Nothing else should
need to change. Keep version-specific code out of the core modules.

Patching rule: FML/launch-time classes load before Mixin is up, so patch them with **raw ASM
transformers** (compat-pass rules). Core's Java 25 fixes to game classes are ClassPatches on SRG names in
Lich's last transformer, since they must run without Lich mods, and so without Lich's Mixin. The Lich API's
hooks into the game are **Mixins**, woven only for loaded Lich mods.

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
  own, ASM 9, LWJGL 3), so FML's mod discovery never scans them, and is a live view that mods can remove
  entries through (§2.4); prefixes in `LichExclusions` always go to the parent, kept outside
  `classLoaderExceptions` so mods can't remove them (§2.5: OneConfig removes `org.lwjgl.`, which on LWJGL 3
  would load a second LWJGL); ImageIO's provider lists leave out classes that aren't there
  (`ImageIoProviders`, §2.4); and jars pinned in `legacy-signed-jars.txt` get back the SHA-1 signatures
  current JDKs ignore (`LegacySignatures`, §2.4).
- Tweaker chain, `--tweakClass` handling and blackboard keys (`Tweaks`, `TweakClasses`,
  `ArgumentList`, `fml.deobfuscatedEnvironment`) behave exactly like LaunchWrapper 1.12. Write tests
  that compare against the real LaunchWrapper running on Java 8.

### 4.3 Redirect transformer
One table-driven ASM pass: `owner/name/desc` → `dev/lich/compat/...` static replacement. It covers removed
JDK APIs, `System.setSecurityManager` and `jdk.nashorn` (LWJGL 2 has its own call table, §2.5). Still to
add: `System.exit` in mods, `Thread.stop` (§2.3). Each hit is logged (`Compat: …`). Besides call redirects, `CompatTransformer` has type renames, package
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
`Field.modifiers` and `Field.set` writes static finals. `getInt`/`setInt` (and `set`) on the dummy read and write
the target `Field`'s own modifiers, as on Java 8, and clearing `final` gives that `Field` an accessor that writes
through `Unsafe` (`UnfinalizedFields`, which implements `jdk.internal.reflect.FieldAccessor`), so a plain
`Field.set` anywhere afterwards works. HardcoreEnderExpansion clears the flag in one class and writes in
another, and stopped in pre-init without it (found 2026-10-31, Tekxit 2). The `Field.set<Primitive>` variants
are redirected in those classes too, so like `Field.set` they write a `static final` without clearing `final` first,
with reflection's widening and type checks. EnumHelper's `cleanEnumCache` needs nothing: `Class.enumConstants` and
`enumConstantDirectory` still exist in Java 25 and aren't hidden from reflection, and with `--add-opens java.lang`
blanking them makes `valueOf`, `values()` and `EnumSet` see the new constant (`LegacyReflectionTest` runs Forge
1.8.9's whole `addEnum`). Still to do: other spellings of the hack, as packs turn them up.

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
- **Player-facing installer (2026-10-31): Prism only for now.** `:installer:installerJar` builds
  `lich-installer-<version>.jar`: a Swing window when double-clicked, a command line with arguments (`--help`).
  It creates an instance (Minecraft, LWJGL 2 and Forge components; Prism fetches their metadata on first load) or
  adds Lich to an existing one, and then:
  - **Forge**: the instance gets the one build Lich supports (`PrismInstance.FORGE_VERSIONS`, §0). If Forge is
    missing it's added right after Minecraft (unless the person opts out). Another Forge build gets switched,
    after asking (`--yes` on the command line), and Prism's cached metadata of the old build is dropped.
  - **Refuses**: Minecraft versions other than 1.7.10/1.8.9, Fabric, Legacy Fabric, Quilt, NeoForge, and Prism's
    `org.lwjgl3` component (two LWJGL 3s in one JVM, §2.5). LiteLoader is allowed.
  - **Java**: `JavaPath` is the Java running the installer (`javaw.exe` on Windows), or `--java`, checked to be
    25+ through `java -version`. `AutomaticJava=false`, `IgnoreJavaCompatibility=true`, and Prism's cached facts
    about the previous Java are removed.
  - **Libraries**: Lich's own jars travel inside the installer and are copied into the instance (`MMC-hint: local`).
    Everything else (ASM, LWJGL 3 + natives, Nashorn, JAXB, Mixin, and MCP's SRG zip from Forge's Maven) is listed
    with `downloads.artifact` (URL, SHA-1, size), so Prism downloads and checks them on the first launch.
    Nothing third-party is redistributed. The build writes this list per version (`Payload`, `lich/<mc>.json`
    in the jar).
  - **Backups and reinstalls**: before its first change, it saves `mmc-pack.json` and `instance.cfg` as
    `*.before-lich`. A reinstall deletes the previous version's local jars. `--uninstall` removes the component
    and its jars and gives the Java choice back to Prism; Forge stays.
  - **Prism must be closed**: it keeps instance settings in memory and writes them back. The installer looks for
    a `prismlauncher` process and asks (or refuses without `--force`).
  - **Old Java**: the jar's main class is a Java 8 stub (`src/start`), so a double-click on an old Java shows
    a "Java 25 needed" message instead of failing silently.
  - Prism's data folder is found at `%APPDATA%`, `$XDG_DATA_HOME`/`~/.local/share`, Flatpak's
    `~/.var/app/org.prismlauncher.PrismLauncher/data` or `~/Library/Application Support`, or given with
    `--prism` (portable installs). The instances folder comes from `InstanceDir` in `prismlauncher.cfg`.
    **(verify)** Linux, Flatpak and macOS.
  - Checked 2026-10-31 (Windows, Prism 11.0.2, a real account): new 1.8.9 and 1.7.10 Forge instances made by the
    installer reach the main menu in about 20 s, `forge profile`. Prism applies `dev.lich` and downloads the remote
    libraries (MCP zips, LWJGL 3 natives, Mixin, ASM) on the first launch. The only errors are Forge's own on
    Java 8 too: missing signature data, and on 1.7.10 `splash.properties` in a fresh instance and the dead
    version-check URL.
- No custom launcher.

---

## 5. Lich mods

This section is the design. For how to write a mod (project setup, every field of `lich.mod.json`, every event, Mixins,
wideners, multi-version builds) see the [mod developer guide](docs/modding/README.md), which says what is implemented
today and what is still reserved (`provides`, raw `transformers`).

### 5.1 Format: `lich.mod.json` at the jar root
```jsonc
{
  "schema": 1,
  "id": "examplemod",
  "version": "1.0.0",
  "name": "Example Mod",
  "authors": ["..."],
  "minecraft": "1.8.9",                 // a whitelist: "1.8.9", ["1.7.10", "1.8.9"] or ">=1.7.10 <1.9"
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
  "javaVersion": 25,
  "versions": {                                        // optional: parts only one Minecraft version uses
    "1.7.10": { "mixins": ["mixins.examplemod.1_7_10.json"], "accessWidener": "examplemod.1_7_10.aw" }
  }
}
```
- **Minecraft versions** (decided 2026-10-31, like Fabric's `depends.minecraft`): `minecraft` is a
  whitelist, one string or a list, each entry an exact version or a range in the `depends` syntax. The mod
  loads if any entry accepts the running version; otherwise the error screen says which versions it's for.
  Most jars list one version. A jar can list several when its code really runs on each: code on
  `dev.lich.api` alone, or game code whose SRG names didn't change between the versions (many did, with 1.8's
  `BlockPos`, block states and renderer). The rest goes in `versions`, keyed by exact versions the whitelist
  accepts. A version's part may set `entrypoints`, `hooks`, `provides` and `mixins`, which are added to the
  top-level ones, and `accessWidener`, which replaces the top-level one. Any other key is an error, so a typo
  can't silently drop a Mixin. The loader merges in the running version's part before resolving
  (`ModDescriptor.forMinecraft`), so `provides` conflicts and everything after see the mod as it is on this
  version.
- **Building one** (`dev.lich.mod`, `lich { minecraft("1.7.10", "1.8.9") }`): `src/main` holds the shared
  code and is compiled against every listed version (`compileJava` against the first, `compileJavaFor<v>` for
  the rest), and each version gets a source set `mc<v>` (`src/mc1_7_10/java`, `/resources`, configurations
  `mc1_7_10CompileOnly` …) for its Mixins, access widener and other version-only code, each in a package of
  its own. `lich.forMinecraft("<v>") { mixinRefmap(…); accessWidener(…) }` configures a version. Every version's
  dev jar is reobfuscated with its own mappings, and `reobfJar` merges them into one. A file in more than one of
  them must be byte for byte the same. That's the check that a shared class links to the same SRG names on
  every version: the same MCP name doesn't guarantee that (`ModelBiped.bipedHeadwear` is `field_78114_d` on 1.7.10,
  `field_178720_f` on 1.8.9), and the build names the class that differs. Shared code can't hold Mixins (refmaps
  are per version). `test/multimod` is the example. The plugin doesn't write `lich.mod.json`'s `versions` block:
  the author lists the same Mixin configs, wideners and entrypoints there.
- **Discovery**: jars in `mods/` that contain `lich.mod.json`. They are **hidden from FML's
  `ModDiscoverer`**, so FML never scans them.
- **Dual jars**: a jar that has both `lich.mod.json` and `mcmod.info`/`@Mod` is claimed by Lich when
  running under Lich, and still works as a plain Forge mod elsewhere. That lets a mod ship one jar
  for both.
- **Dependency resolution**: semver ranges, clear error screens for missing or incompatible mods,
  load-order sorting.
- **Forge interop**: a Lich mod with `forge: optional` can check `Lich.isForgePresent()` and use Forge
  APIs only behind that check.

### 5.2 Lich API: deep hooks (both profiles, implemented with Lich's own Mixins)
Lich itself doesn't change the game (§0); it gives Lich mods the means to. The hooks go as deep as the
game allows, so a mod can take over almost anything without private Lich internals.

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
4. **Replaceable subsystems**: interfaces over whole pieces of the client that mods may want
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

Every time a first-party mod has to fall back to a Mixin for something general, that's
a sign a hook is missing: add the hook to the API rather than keep the Mixin.

---

## 6. Public-server safety (hard requirement)

Goal: no bans, no kicks, no disconnects that a plain vanilla/Forge client wouldn't also get.

### 6.1 Rule: the client's server-observable behavior must not change
"Server-observable" means anything that reaches or is inferred from the network.
- **Never touched by Lich core**: player movement/physics code, the 20 TPS client tick timer,
  `PlayerControllerMP` (attack/dig/use timing), packet send order/rate/content, rotation math,
  `MathHelper` (its sine table *is* part of the physics; "fast math" replacements change movement and
  can trip prediction anticheats), keep-alive handling, entity interaction.
- **Allowed**: rendering, GUI, audio, resource loading, client-only caches, threading of
  client-only work (chunk meshing), memory and GC.
- Kept by review, not by a build check (the deny-list check was dropped with Phase 7, 2026-10-31). Hooks into
  these areas are observe-only, apart from the explicitly labelled packet send/modify API (§5.2). Other Lich mods can still touch them; that's on the mod author (and the server's mod
  rules).

### 6.2 Protocol and identity
- No Lich plugin channels, no `REGISTER` additions, no extra packets unless a mod asks for them.
- Client brand: by default send exactly what the underlying game sends (`vanilla` / `fml,forge`).
  Open question: offer an opt-in `lich` brand.
- Login/encryption on Java 25: 1.7/1.8 use RSA-1024 + `AES/CFB8/NoPadding` and zlib (1.8). **Works for
  1.7.10** (2026-10-24: a public server joined with a Microsoft account through Lich on Java 25,
  so session join, RSA and AES/CFB8 work); **works for 1.8.9** too, zlib included (2026-10-27: a public server
  played with a real account in the `lich-forge` profile). Mojang session-server
  HTTPS works better on 25 (newer CA certs and TLS).

### 6.3 Floating point
Java 17 made all floating point strict (JEP 306). On x86-64, Java 8 already behaved that way (SSE2), so
physics should match bit for bit. `Math.*` intrinsics (e.g. `Math.atan2`, which 1.7.10 uses) may
differ by 1 ulp between JDKs **(verify)**.

### 6.4 Tests
- Manual checks on public servers with anticheats. 2026-10-27: PvP on a public server with an anticheat through
  Lich on Java 25, no flags or bans; by 2026-10-31 also a server running the AGC anticheat. The determinism replay and
  local anticheat job were dropped with Phase 7 (2026-10-31): these checks are enough.

---

## 7. Testing
- **Modpack harness** (`test/harness`, 2026-10-27; rewritten 2026-10-31): Forge mod compatibility is tested with whole
  modpacks instead of one mod at a time (much faster, and mods meet each other as they do for players). A pack is
  pinned in `packs.json` by its source: a Modrinth `.mrpack` and its SHA-512, a Technic pack's Solder build (mod zips
  with MD5s, unpacked in order; `bin/` skipped after checking its Forge is the profile's), a Technic pack that is one
  client zip (its SHA-512 added by the pin), an FTB pack version (files with SHA-1s, a fifth of them from CurseForge's
  CDN, so FTB is also the keyless way to CurseForge's files), or an ATLauncher pack version (MD5s from its
  `Configs.json`); one installer class per source. A pack that names an older 1.7.10 Forge (`packForge`) runs on the
  profile's 1614, as most such packs do in practice. No more Hypixel SkyBlock packs (2026-10-27): they repeat
  the same mods, and their many small-author mods are the likeliest to carry a RAT. `:harness:fetchPack` installs a
  pack into `run/packs/<pack>/minecraft` the way its launcher would, every file hash-checked; packs that leave their
  mods to McInstanceLoader get its list resolved directly (the loader itself is left out: it downloads through a
  third-party CurseForge proxy with certificate checks off). A pack's `setup` answers first-launch prompts once in the
  template, the way a player would, so runs are unattended (for SkyBlock Enhanced, PackCore's config-preset dialog).
  `:harness:prismPack` makes Prism instances of a pack, `<pack>-forge-<mc>` and `<pack>-lich-<mc>`.
  - **One scenario** (`:harness:runPack "-Plich.pack=<packs>"`): each profile (`forge` on Java 8, `lich-forge` on
    Java 25) starts from a fresh copy of the template, with the same `options.txt` overrides (no sound or fullscreen,
    no pause on lost focus, view distance 8, 30 fps in the world), and plays the same steps: `boot` (the first screen
    settles), `mods` (FML's mod list, every mod selected so its logo and description draw, every config screen opened
    the way the Config button makes it), `server` (joins the local vanilla test server, which starts while the game
    boots, stays 15 s, leaves), `world` (a creative world with a fixed seed and the pack's `worldType`, or the map the
    pack ships, `save`; Blightfall's template `flans/` is copied into `saves/` by its `setup.copy`), `spawn`, `tour`
    (8 teleports 1000 blocks out, so new chunks generate in many biomes), `inventory` (every creative tab; the search
    tab scrolled page by page, so every item is drawn once), `gallery` (opt-in per pack: one of every block placed
    through `/setblock` and kept through the save), `hold` (20 s: machines, mobs, timers), `save` (save and quit as the
    pause menu does; `level.dat` and region files must be there), `reload` (the world opens again; the gallery's tile
    entities must all come back) and `exit`. `-Plich.until=<step>` cuts the list (`boot` only checks the pack starts).
  - **The driver** (`dev.lich.harness.driver`) joins the launch as an extra LaunchWrapper tweaker (Java 8 bytecode, in
    a jar on the class path), so the same jar drives Forge on Java 8 and Lich. It reaches the game only by reflection
    on SRG names (the same in 1.7.10 and 1.8.9, apart from tile entity positions), changes it only through the client
    thread's task queue, and waits for it with `findLoadedClass`, so it never loads a game class before FML's
    transformers are in. It asks every live thread's context class loader as well as `Launch.classLoader`: FoamFix
    swaps `cachedClasses` for another map, and Essential relaunches the whole launch (Lich's boot included) in a class
    loader of its own. Steps wait on the game, not on a clock: a place counts as arrived at when every chunk within 6
    of the player is loaded and populated on the server and the player's chunk is on the client (waiting for the chunk
    counts to stop changing doesn't work: after a teleport the server unloads the old area a few chunks a tick). A
    screen watcher logs every screen, answers FML's `StartupQuery` prompts yes (from its own thread: the client thread
    runs no tasks while one is up) and fails the step at once on FML's error screens or the out-of-memory screen.
    Events go to the harness over a loopback socket (and into the game log as `LICH-HARNESS` lines), so a mod that
    reconfigures log4j doesn't blind it. A failure the game caused is a `StepFailed`; any other exception is the
    driver's own bug and is reported as a harness problem, so it can't pass for a pack or Lich failure.
  - **Following a run** (`GameRun`): the game is the harness's own child process (devlaunch's `command()` builds its
    command line in-process, everything downloaded first). A run ends on a crash report (CoreTweaks keeps the game
    running after one), an `hs_err` file, an `OutOfMemoryError`, an early exit, a driver failure, or a step that
    overruns its deadline (a generous default, or 3× the baseline's time plus a minute; a stall saves `jcmd
    Thread.print` from the game's own JDK to `stall-threads-<profile>.txt`). The Java 8 run gets a copy of LWJGL 2's
    `Mouse` that never grabs or moves the real cursor (`NoGrabMouse`), the counterpart of `-Dlich.dev.noMouseGrab`.
  - **Baseline and verdict**: the Forge run is stored as `run/harness/<pack>/baseline.json` and reused while nothing
    it depends on changes (the pin and setup, the driver's classes, the scenario's settings, the Forge profile, the
    heap, the Java 8), so a rerun after a Lich change runs Lich alone (`-Plich.baseline=rerun|skip`). A baseline that
    played more steps is cut to the run's. Lich's run is compared with it step by step: **pass** (as far as Forge),
    **review** (as far, but with log lines only Lich has that look like Java 25 problems: linkage and access errors,
    JDK internals, LWJGL; or driver problems in a step Forge didn't have; or a slow exit Forge didn't have), **FAIL**
    (stopped earlier, or in the same step for another reason), **pack broken** (Forge fails the same way, or Lich gets
    further) and **harness problem**. Log lines are compared normalized (no times, threads, hashes, game paths, numbers;
    what Java 8 and 25 print differently for the same exception made equal). `report.md` has the verdict, the steps
    side by side, the driver's numbers (chunks, entities, tile entities, mean tick time, heap) and the log differences,
    suspect lines first; `run/harness/summary.md` a row per pack with what changed since its previous run
    (`history.jsonl`). `-Plich.pack` takes ids, `all`, `@quick` or `@<mc>`; packs with a `skip` reason (they can't run
    unattended) only run when named. One game at a time: the dev machine has 16 GB, and a 1.7.10 pack with a 4 GB heap
    uses ~6 GB.
  - **Speed** (2026-10-31, SolitaryCraft VanillaPlus, every step including the gallery): the Forge run took 130 s,
    the old world mode 5–6 min whatever the pack (a fixed 5.3 min of waits in its driver, which also counted the first
    `Sound engine started` as the menu: 1.7.10 logs it before the mods load, so GT New Horizons' "63 s" was 156 s). Its
    tour went from 173 s to 40 s once stops ended on chunks loaded rather than on counts settling. **(verify)** a rerun
    reusing the baseline, the scenario on the other packs, and a mutation check (a known Lich fix reverted, e.g. the
    fixed-arity constructor handle Regional Water needs, must give FAIL).
  - **More packs, Lich only** (2026-10-27, world mode, no Forge baseline): 1.7.10: **FTB Infinity Evolved 3.1.0**
    (139 mod jars) ✅, **FTB SkyFactory 2.5.8** (145) ✅, **The 1.7.10 Pack 0.10.15** (Technic, 218): loads and
    enters its world, the run was cut short by low memory (not rerun since); **Blightfall 3.1.1-CE** (Technic, 103,
    LiteLoader + VoxelMap) ✅ (2026-10-31). 1.8.9: **Everest Client 2.0.4** ✅, **1.8 PvP Pack v6** ✅,
    **BedWars+ 1.5.0** (Essential, SkyHanni) ✅; one earlier BedWars+ run hung in save-and-quit, most likely the
    shutdown race fixed on 2026-10-31 (§2.4). They turned up nine Lich bugs (§2.3, §2.4): LiteLoader's `javax.activity` exception, Decocraft's
    `PNGImageReader`, SHA-1 jar signatures, signer mismatches between excluded and transformed classes, writes to
    `getSources()`, ASM 9's `Textifier` output (Logistics Pipes), classes generated by transformers, and UniMixins'
    relocated ASM 9 (its bridges, its reader limit and Mixin's version detection). No more Hypixel SkyBlock packs:
    repetitive, and the most likely to carry a RAT; Hypixel Premium and SkyClient were dropped.
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
    The first singleplayer world played by hand in Prism (2026-10-27) crashed within a few ticks: Regional Water adds a
    `BiomeDictionary.Type` through `EnumHelper`, whose constructor is varargs, and Lich's `newConstructorAccessor`
    wrapped the array argument in another array (fixed: fixed-arity handle). The server-join harness can't see
    server-side code like this, hence the world mode.
  - **Sixteen more packs** (2026-10-31, world mode, Forge/Java 8 and Lich). They turned up six Lich bugs
    (§2.4, §2.5, §4.4): `Field.modifiers` cleared in one class and written in another (Tekxit 2), Witching Gadgets'
    index into `Mouse`'s fields and Angelica's `org.lwjgl.MemoryUtil` (GT New Horizons), the `Java/25` User-Agent
    (Survival Industry), JourneyMap's `sun.awt.image` (LOTR Survival, Lucky Block) and 1.8.9's shutdown race
    (CubeSoft, SolitaryCraft). Where things stand, from the last run of each:
    - Pass on Lich: **GT New Horizons 2.8.4** (309 mods; like on Java 8, the game takes over 60 s to exit after the
      run), **Tekxit 2**, **Survival Industry** (rerun after their fixes), HBM's Nuclear Tech Automation, LOTR
      Survival, Resonant Rise 3 (1.7.10); CubeSoft, SolitaryCraft VanillaPlus, Lucky Block Add On (1.8.9, after the
      shutdown fix). From the earlier packs, Blightfall now passes too, and the 1.8 PvP Pack still does with the
      shutdown fix.
    - Fail the same way on Forge/Java 8, so the pack's own problem: Dragon Block Apollo and Bountiful Redux (a
      server-tick crash), FTB Horizons: Daybreaker (never reaches the world), the FiskHeroes server pack (exits
      early); Dunkirk Spirit stalls on Java 8 and runs out of heap on Lich.
    - Can't start unattended: their mods download libraries at launch from hosts that are gone, and the packs don't
      ship them. Ultimate Warfare (CodeChickenCore's DepLoader: CodeChickenLib, ForgeMultipart, and bspkrsCore from
      bspk.rs, which also opens an error dialog on the desktop) and Eldritch Revolution (Thaumcraft's loader: Baubles
      from a dead Dropbox link). Not Lich problems; they'd need those jars added to the pack's `setup`.
- **Multimod** (`test/multimod`): one jar for 1.7.10 and 1.8.9 (§5.1). Shared code, plus a Mixin, a widener and
  an entrypoint per version, each logging a `Lich multimod:` line. Its build is the regression check for the
  plugin's multi-version mode (2026-10-31: all three lines on both versions, `lich` profile).
- **Testmod** (`test/testmod`): a small Lich mod for 1.8.9 that touches every part of the loader (entrypoints,
  hooks, its own Mixin config, an access widener) and logs one `Lich testmod:` line per part. Planned: a Forge-mod
  half, and a `-Dlich.test=…` mode that loads a world, runs N ticks, dumps a report and exits with a status code
  (the harness's scenario covers much of this for Forge mods).
- **CI**: Linux + Xvfb + Mesa llvmpipe. Matrix: {1.7.10, 1.8.9} × {vanilla, forge, forge+modset,
  lich-mods} × {Java 8 baseline, Java 25}.
- **Mod corpus** (2026-10-24, from Modrinth, newest Forge build of each, dependencies in the same folder,
  each run alone through devlaunch's `lich-forge` profile, joining the local test server for 25 s, game
  muted). Every entry passes on LWJGL 2; on LWJGL 3 (Phase 6, 2026-10-25) every entry passes, Hodgepodge
  included:
  - 1.8.9 (22): OptiFine, 3D Skin Layers, Biomes O' Plenty, Blood Magic, Chatting, Controlling,
    CraftTweaker, EntityCulling, Essential, FoamFix, In-Game Account Switcher, JEI, NotEnoughUpdates,
    PolyPatcher, Replay Mod, SecurityCraft, SkyblockAddons, SkyHanni, Storage Drawers, Wavey Capes,
    Xaero's Minimap, Xaero's World Map.
  - 1.7.10 (25): OptiFine, Aether, ArchaicFix, Biomes O' Plenty, Blood Magic, Botania,
    ChickenChunks, Controlling, CraftTweaker, Draconic Evolution, Ender Storage, EntityCulling
    (UniMixins), Fisk's Superheroes, FoamFix, Hodgepodge, JourneyMap, Mekanism (+ ForgeMultipart),
    Nature's Compass, NEI, OpenComputers, SecurityCraft, Storage Drawers, Tinkers' Construct, Wawla,
    Xaero's Minimap.
  - Compare mixins applied on Java 8 and Lich with `-Dmixin.debug.verbose=true` and the
    `Mixing … into …` lines. The scripts used are throwaway; the harness (above) replaces them, and new
    mods are tested through packs, not one by one.
- **LaunchWrapper conformance**: the same tweaker/transformer scenarios run on real LaunchWrapper
  (Java 8) and Lich's, with the same results.

---

## 8. Roadmap

| Phase | Goal | Status |
|---|---|---|
| 0. Setup ✅ | Gradle multi-project on JDK 25, `devlaunch`, Java 8 baselines | Done |
| 1. Vanilla 1.8.9 on 25 ✅ | `boot`, `launchwrapper`, flags, log4j safety, profile generator | Done: main menu, local and public servers, with a real account |
| 2. Vanilla 1.7.10 on 25 ✅ | Same for 1.7.10 | Done |
| 3. Forge, no mods ✅ | Runtime binpatching (§4.5), ASM 9 + ASM 5 shims, SecurityManager strip, reflection compat (§4.4), Lich's system class loader, hiding Lich's jars from FML, the SoundManager race fix (§2.4) | Done on both versions |
| 4. Forge mods ✅ | Mixin provider, redirect table, reflection compat, JDK shims, modpack harness (§7) | Done: whole modpacks join and play on Lich with the same mixins applied as on Java 8 |
| 5. Lich mods ✅ | `remap`, `loader`, `lich.mod.json`, `api` (lifecycle, events, HUD, keybinds, config; hooks woven on demand, §5.2), FML-hiding, dual jars | Done on both versions, with and without Forge |
| 6. LWJGL 3 🟡 | `compat-lwjgl2` + redirects | Windows x64 done (§2.5). Left: Linux (X11, Wayland), macOS Apple Silicon, controllers |
| 7. Ship 🟡 | Installer, Prism pack, docs, compat list, mod-dev docs and template | 0.3.0: Prism installer (§4.6), README tables, in-game branding. 0.3.1: multi-version Lich mod jars (§5.1). Mod developer guide in `docs/modding` (2026-11-03). Left: other launchers, publishing the API and the Gradle plugin (a mod is still a module in a Lich checkout), project template |
| later | 1.12.2 (`compat-forge-1.12.2`, `compat-mc-1.12.2`, `api-1.12.2`) | — |

Note on startup: Java 25's AOT cache (JEP 483/514/515) doesn't cache classes from custom class loaders,
so it helps little here. Cache *transformed class bytes* on disk instead (in `loader`).

---

## 9. Risks
- **Coremods with aggressive ASM** may assume exact bytecode or old ASM behavior.
- **OptiFine**: closed source, can't be redistributed, swaps in whole classes, heavy LWJGL 2 use.
  Stretch goal (user-supplied jar).
- **Lich mods vs Forge coremods on the same code**: an aggressive Lich mod (one replacing chunk
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
