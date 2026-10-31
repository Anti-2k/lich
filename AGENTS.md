# AGENTS.md

Lich is a client-side runtime and mod loader that runs Minecraft 1.7.10 and 1.8.9 on Java 25. It loads
unmodified Java 8 Forge mods through real Forge, patched at load time, and its own mod format
(`lich.mod.json`) for mods written for Lich, such as full client mods later on. Package root: `dev.lich`.

**`PLAN.md` is the source of truth** for the design, the decisions already made (own LaunchWrapper, SRG
runtime namespace, client only, public-server safety) and the phased roadmap. Read it before starting
a phase, and update it when a decision changes or a **(verify)** item gets checked.

## Build

- JDK 25 (Temurin, installed at `C:\Program Files\Eclipse Adoptium\jdk-25.*`) builds everything and runs
  the Gradle daemon (`gradle/gradle-daemon-jvm.properties`). JDK 8 (`C:\Program Files\Java\jdk-8`)
  runs the baseline games. Toolchain auto-download is off, so JDKs are installed by hand.
- `./gradlew build` builds every module and runs the unit tests (JUnit 5). `launchwrapper`'s tests load
  the `fixtures` source set through `LaunchClassLoader`. Those classes are deliberately kept off the
  test classpath.
- The game's own libraries (log4j 2.0-beta9, jopt-simple 4.6) are `compileOnly`: the vanilla version
  provides them at runtime, and Lich never ships them.
- Gradle 9.8.2 with the configuration cache on. Inside task lambdas, use only locals of the task's
  configure block. Script-level vals capture the script object, and the cache rejects it.

## Running the game: devlaunch

`tools/devlaunch` is a small launcher. It resolves a version JSON (Mojang's, or a local one with
`inheritsFrom`), downloads the jar, libraries, natives, assets and Mojang's log4j config into
`run/cache`, and starts the game offline (user `Dev`) in `run/<version>`.

```
# Vanilla (or plain Forge: -Plich.profile=forge) baseline on Java 8
./gradlew :devlaunch:runGame "-Plich.mc=1.8.9" "-Plich.gameJavaHome=C:/Program Files/Java/jdk-8"
# Through Lich on Java 25, without Forge (lich) or with it (lich-forge). Every profile but vanilla first
# writes lich-<mc>, forge-<mc> and lich-forge-<mc> into run/cache (:installer:installDevProfile)
./gradlew :devlaunch:runGame "-Plich.profile=lich-forge" "-Plich.mc=1.7.10" "-Plich.gameJavaHome=C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
# Join a server straight away, or just print the command
./gradlew :devlaunch:runGame "-Plich.profile=lich" "-Plich.args=--game-arg --server --game-arg 127.0.0.1 --game-arg --port --game-arg 25599"
./gradlew :devlaunch:runGame "-Plich.args=--dry-run --skip-assets"
```

- In PowerShell, **quote every `-P` argument**. Otherwise PowerShell splits `-Plich.profile=lich` at
  the dot and Gradle reports an unknown task.
- `-Plich.gameJavaHome` is needed for JDKs Gradle doesn't detect (unzipped ones have no registry entry).
  Without it, `-Plich.gameJava=<n>` (default 8) picks a detected toolchain.
- Each profile has its own game directory, `run/<profile>-<mc>` (vanilla: `run/<mc>`). Boot logs
  `Lich <version> on Java …, <profile> profile` as its first line, then every compat-pass hit
  (`Compat: …`) and game fix (`Game fix: …`). The `forge-<mc>` JSONs come from
  `tools/installer/src/main/resources/dev/lich/installer/forge-<mc>.json` (Forge's own library list);
  devlaunch downloads Forge from Forge's Maven into `run/cache`.
- To check a change to Forge on Java 25, diff the game log against the Java 8 `forge` profile's:
  apart from Lich's own lines, Java versions and thread interleaving they should match line for line.
- Local test servers: `run/server-1.8.9` (port 25599) and `run/server-1.7.10` (port 25598), vanilla
  `server.jar` from the version JSON, offline mode, bound to `127.0.0.1` only. Start them with JDK 8
  (`java -jar server.jar nogui` in that folder) and wait for `Done (` in the console. Stop them by the
  PID that owns the listening port (`Get-NetTCPConnection -LocalPort 25599 -State Listen`).
- Other devlaunch options (`--version-json`, `--jvm-arg`, `--extra-cp`, `--main-class`, …): see `Options.USAGE`.
- Lich profiles run the game on LWJGL 3 (PLAN.md §2.5). `--jvm-arg -Dlich.lwjgl=2` runs the same profile on the
  game's LWJGL 2, to tell a Lich bug from an LWJGL 3 one. Boot logs each LWJGL call it rewrote (`LWJGL 3: …`),
  once per member and package.
- The game's stdout is log4j XML (Mojang's launcher config). Read `run/<version>/logs/latest.log`
  for plain text. The main menu is reached once it shows `Sound engine started` and a `…atlas` line.
- The user works on the same desktop while test games run: always pass `--jvm-arg -Dlich.dev.noMouseGrab=true` in
  `-Plich.args` (Lich profiles), so the game never locks or moves the real cursor; it still behaves as if grabbed.
- To stop a game you started, match the java process on `lich\run\cache` and its main class
  (`dev.lich.boot.Main`, `net.minecraft.launchwrapper.Launch` or `net.minecraft.client.main.Main`) in
  its command line. Never kill java processes by name alone: Gradle daemons and the user's own game
  run on java too.

## Testing in Prism (real account, public servers, singleplayer)

- Prism instances `lich-forge-1.8.9` ("1.8.9 Forge (Lich)") and `lich-forge-1.7.10` ("1.7.10 Forge
  (Lich)") are copies of the user's Forge instances `1.8.9` and `1.7.10` with Lich added as a custom
  component (Forge's component entry copied from the original instance). Java 25 is pinned,
  `AutomaticJava=false` and `IgnoreJavaCompatibility=true`. Mods the user isn't testing sit in
  `minecraft/mods-disabled`. Resource packs weren't copied. **Never edit the original `1.8.9`/`1.7.10`
  instances**: they're the user's own test instances (Forge + OptiFine). There are no Prism
  instances without Forge any more (removed 2026-10-24 at the user's request); test the `lich` profile through
  devlaunch. Prism only lists a new instance folder after a rescan or restart; don't restart the
  user's Prism for them.
- After a Lich build, refresh an instance with `./gradlew :installer:installPrism "-Plich.mc=1.8.9"`. It
  rewrites `patches/dev.lich.json` (main class, `+libraries` with `MMC-hint: local`, `+jvmArgs`), copies
  the jars into the instance's `libraries/` and keeps the `dev.lich` entry last in `mmc-pack.json`
  (Prism takes the main class from the last component that sets one, and Forge sets LaunchWrapper's). `-Plich.prismInstance=<dir>` targets another instance.
- Smoke test without the user's account: `prismlauncher.exe --launch lich-forge-1.8.9 --offline Dev`. The
  game log is `instances/lich-forge-<mc>/minecraft/logs/latest.log`, and Prism's own log (`PrismLauncher/logs`)
  shows `Applying "dev.lich" GOOD` and the full JVM arguments. `--world` doesn't open worlds on these
  old versions. If a launch stalls, read the console window's text with UI Automation. Offline mode
  never downloads missing libraries; that's how the 1.7.10 copy first failed (guava 15 and commons-lang3
  3.1, which Forge instances never fetch).
- Prism starts games through `org.prismlauncher.EntryPoint` and passes the main class on stdin, so to
  stop a Prism test game, match `instances/<instance>/` and `org.prismlauncher.EntryPoint` in the
  command line, and check there's no other game from that instance (the user may be playing).

## Layout

```
build-logic/        convention plugin `lich.java` (Java 25, UTF-8, -Xlint:all)
core/               boot, launchwrapper, transform, remap, loader, mixin-provider
compat/             jdk (removed-API shims, reflection compat), asm (ASM 5 on ASM 9), lwjgl2 (LWJGL 2 API on LWJGL 3)
api/                public version-neutral Lich API
versions/<mc>/      compat-mc, compat-forge, api; Gradle projects are named v1_8_9-compat-mc etc.
versions/shared/    compat-mc (project shared-compat-mc): game fixes identical in 1.7.10 and 1.8.9;
                    api: sources both per-version api projects compile (no project of its own)
mods/               first-party Lich mods, not part of the runtime: perf (optimisations)
tools/              devlaunch, installer
test/               testmod, harness
mcdev/              included build: remap-core and the dev.lich.mod Gradle plugin (Minecraft with MCP names
                    to compile against, refmaps, reobfuscation to SRG) for Lich mods and the api projects
```

Working so far:
- `launchwrapper`: Lich's LaunchWrapper 1.12 (`net.minecraft.launchwrapper.*`), plus
  `dev.lich.launchwrapper` for the launch logic, hooks, Lich's first/last transformers and `LichSources`.
- `boot`: `dev.lich.boot.Main` (flag check, safe logging, compat pass, profile, vanilla tweaker) and
  `LichSystemClassLoader`, the JVM's system class loader under Lich. Safe logging is the Log4Shell-safe config
  plus `SessionLogFilter`, which keeps the access token out of every log4j context; boot also sends a Java 8
  `http.agent` (PLAN.md §2.4).
- `transform`: the compat pass, `CompatTransformer`. `compat-jdk` and `compat-asm` hold the shims and
  register their rules with it (`JdkCompat`, `AsmCompat`).
- `shared-compat-mc`, `v1_7_10-compat-mc` and `v1_8_9-compat-mc`: game fixes on SRG names, run as
  Lich's last transformer in both profiles (the vanilla profile needs MCP's mappings for SRG names).
  `v1_7_10-compat-forge`/`v1_8_9-compat-forge`: hide Lich mods from FML's discovery.
- `remap`: the vanilla profile's obf → SRG remapper. `loader`, `api` and the `versions/<mc>/api` projects: Lich
  mods (`lich.mod.json`, entrypoints, hooks woven on demand); a full client mod runs on them. `mixin-provider`: the Mixin
  Lich mods run on, started only when Lich mods are installed. `testmod`: a Lich mod for 1.8.9 that touches every
  part of the loader (`:testmod:installDev`).
- `compat-lwjgl2`: LWJGL 2's API on LWJGL 3 (PLAN.md §2.5). LWJGL 2-only classes under their own names
  (`org.lwjgl.opengl.Display`, ...), with their logic in `dev.lich.compat.lwjgl2.display`; `Lwjgl2Compat`'s
  call table for members LWJGL 3 renamed (`renames.txt`, generated) or dropped (the `Lwjgl2*` mirrors).
  `./gradlew :compat-lwjgl2:checkLinkage` lists the LWJGL references in the game, Forge and `run/corpus`
  that still don't link; rerun it after changing the table. `generateRenames` and `generateCapabilities`
  regenerate the generated parts after an LWJGL update.
- `installer`: `ProfileInstaller` writes `versions/{lich,forge,lich-forge}-<mc>/` and `libraries/`, and
  `PrismComponent` writes Prism components.
- `devlaunch`.
- `harness` (`test/harness`): Forge mod compatibility through whole modpacks, pinned in `packs.json` (Modrinth, Technic
  Solder or zip, FTB or ATLauncher sources; PLAN.md §7). `./gradlew :harness:runPack "-Plich.pack=lost-era" "-Plich.mc=1.7.10"` runs the pack on Forge/Java 8
  and Lich/Java 25 against the local test server, and writes `run/harness/<pack>/report.md`, which lists what only
  one side logged. `-Plich.profiles=lich-forge` reruns Lich alone. `:harness:worldPack` (same options) plays a
  singleplayer world instead (create, tour, hold, save, reopen) through a driver tweaker, and writes `world-report.md`;
  a stalled run leaves a thread dump in `stall-threads.txt`. Run one game at a time (a big 1.7.10 pack takes ~6 GB
  of the machine's 16), and no Hypixel SkyBlock packs (the user's call: repetitive, RAT risk). `:harness:prismPack`
  creates Prism instances `<pack>-forge-<mc>` and `<pack>-lich-<mc>` (none exist now; the user had them deleted).
  Refresh the `-lich-` ones with `installPrism` after a Lich build.

`perf` is still a placeholder (Phase 8). Each module's build file comment names its phase and PLAN.md section.

How Lich's classes load, and where a new class goes:
- `LichSystemClassLoader` defines everything on the class path. Its compat pass skips `dev.lich.*`, ASM and
  the jars Lich ships (its own, LWJGL 3, Nashorn). It also drops LWJGL 2 when LWJGL 3 and `compat-lwjgl2` are there.
- LaunchWrapper itself excludes `org.lwjgl.`, so `compat-lwjgl2`'s `org.lwjgl.*` classes, like LWJGL 3's,
  come from the system class loader: one copy each.
- Boot makes `dev.lich.boot.`, `.launchwrapper.`, `.transform.`, `.compat.`, `.remap.`, `.loader.` and `.mixin.`
  class loader exclusions, plus `org.objectweb.asm.` (one ASM for everyone). So shims that game or mod code calls
  must live under `dev.lich.compat.`, and there is one copy of each, whichever loader the caller comes from. The
  Lich API (`dev.lich.api`) is not excluded: it loads in LaunchClassLoader, next to the game and the mods.
- Patches to game classes run *last*, keyed on SRG names: running them first would change the bytes
  that Forge's binpatches checksum.
- Lich's jars and ASM 9 are hidden from FML's mod discovery (`LichSources`), because FML's ASM-5-API
  visitors fail on Java 9+ class files. A new Lich jar is hidden automatically: the convention plugin's
  `Implementation-Title: lich-<name>` is what `LichSystemClassLoader` recognizes.

LaunchWrapper compatibility rule: `LaunchClassLoader` keeps 1.12's private fields by name and type,
because Mixin, coremods and FoamFix-style mods reflect on them (`LaunchClassLoaderTest` pins them). Lich
code doesn't add public API to the `net.minecraft.launchwrapper` classes. Hooks go in `dev.lich.launchwrapper`.
Five deliberate differences (PLAN.md §4.2): Lich's first and last transformers also run on transformer-excluded
classes; `getSources()` leaves out `LichSources` (a live view: mods remove entries through it); `LichExclusions`
(`org.lwjgl.` on LWJGL 3) hold even when a mod removes the same prefix from `classLoaderExceptions` (OneConfig
does); ImageIO's provider lists (`META-INF/services/javax.imageio.spi.*`) leave out classes that aren't there
(`ImageIoProviders`: Java 8 skipped them under FML's SecurityManager); and jars pinned in `legacy-signed-jars.txt`
get back the SHA-1 signatures current JDKs ignore (`LegacySignatures`).

## Conventions

- Java 25 everywhere, except code that Forge or Java 8 must load (the Forge side of `testmod`), which will
  get its own convention plugin.
- 4-space indents. Each class gets a short Javadoc that says what it does. Comments explain *why*.
- Keep version-specific code in `versions/<mc>/`. Core modules stay version-neutral, so 1.12.2 can be
  added later as three new modules.
- **Core doesn't change the game** (PLAN.md §0): a patch to a game class goes in core only if the game
  needs it to run on Java 25. Optimisations and other improvements go in a Lich mod (`mods/perf`), and
  whatever those mods need from the game becomes a hook in the API, woven only when a loaded mod
  declares it (§5.2). With no Lich mods, the game plays exactly as vanilla or Forge does on Java 8.
- **Server safety** (PLAN.md §6): Lich core and the `perf` mod never touch movement/physics, the tick timer,
  attack/dig timing, packet timing/content, or `MathHelper`.
- Don't commit unless asked.
