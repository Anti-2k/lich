# AGENTS.md

Lich is a client-side runtime and mod loader that runs Minecraft 1.7.10 and 1.8.9 on Java 25. It loads
unmodified Java 8 Forge mods through real Forge, patched at load time, and its own mod format
(`lich.mod.json`) for mods written for Lich, such as 2k Client later on. Package root: `dev.lich`.

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
- The game's stdout is log4j XML (Mojang's launcher config). Read `run/<version>/logs/latest.log`
  for plain text. The main menu is reached once it shows `Sound engine started` and a `…atlas` line.
- To stop a game you started, match the java process on `lich\run\cache` and its main class
  (`dev.lich.boot.Main`, `net.minecraft.launchwrapper.Launch` or `net.minecraft.client.main.Main`) in
  its command line. Never kill java processes by name alone: Gradle daemons and the user's own game
  run on java too.

## Testing in Prism (real account, public servers, singleplayer)

- Prism instances `lich-1.8.9` ("1.8.9 (Lich)") and `lich-1.7.10` ("1.7.10 (Lich)") are copies of the
  user's Forge instances `1.8.9` and `1.7.10` with Forge removed and Lich added as a custom component.
  Java 25 is pinned, `AutomaticJava=false` and `IgnoreJavaCompatibility=true`. The Forge mods sit in
  `minecraft/mods-disabled`. Resource packs weren't copied. **Never edit the original `1.8.9`/`1.7.10`
  instances**: they're the user's 2k client test instances (Forge + OptiFine).
- `lich-forge-1.8.9` ("1.8.9 Forge (Lich)") and `lich-forge-1.7.10` ("1.7.10 Forge (Lich)") are the same
  with Forge kept (its component entry copied from the original instance), mods still disabled. Refresh
  them with `-Plich.prismInstance=%APPDATA%/PrismLauncher/instances/lich-forge-<mc>`. Prism only lists a
  new instance folder after a rescan or restart; don't restart the user's Prism for them.
- After a Lich build, refresh an instance with `./gradlew :installer:installPrism "-Plich.mc=1.8.9"`. It
  rewrites `patches/dev.lich.json` (main class, `+libraries` with `MMC-hint: local`, `+jvmArgs`), copies
  the jars into the instance's `libraries/` and keeps the `dev.lich` entry last in `mmc-pack.json`
  (Prism takes the main class from the last component that sets one, and Forge sets LaunchWrapper's). `-Plich.prismInstance=<dir>` targets another instance.
- Smoke test without the user's account: `prismlauncher.exe --launch lich-1.8.9 --offline Dev`. The game
  log is `instances/lich-<mc>/minecraft/logs/latest.log`, and Prism's own log (`PrismLauncher/logs`)
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
core/               boot, launchwrapper, transform, remap, loader, mixin-provider, perf
compat/             jdk (removed-API shims, reflection compat), asm (ASM 5 on ASM 9), lwjgl2 (LWJGL 2 API on LWJGL 3)
api/                public version-neutral Lich API
versions/<mc>/      compat-mc, compat-forge, api-impl; Gradle projects are named v1_8_9-compat-mc etc.
versions/shared/    compat-mc (project shared-compat-mc): game fixes identical in 1.7.10 and 1.8.9
tools/              devlaunch, installer
test/               testmod, harness
```

Working so far:
- `launchwrapper`: Lich's LaunchWrapper 1.12 (`net.minecraft.launchwrapper.*`), plus
  `dev.lich.launchwrapper` for the launch logic, hooks, Lich's first/last transformers and `LichSources`.
- `boot`: `dev.lich.boot.Main` (flag check, safe logging, compat pass, profile, vanilla tweaker) and
  `LichSystemClassLoader`, the JVM's system class loader under Lich.
- `transform`: the compat pass, `CompatTransformer`. `compat-jdk` and `compat-asm` hold the shims and
  register their rules with it (`JdkCompat`, `AsmCompat`).
- `shared-compat-mc`, `v1_7_10-compat-mc` and `v1_8_9-compat-mc`: game fixes on SRG names, run as
  Lich's last transformer in the Forge profile.
- `installer`: `ProfileInstaller` writes `versions/{lich,forge,lich-forge}-<mc>/` and `libraries/`, and
  `PrismComponent` writes Prism components.
- `devlaunch`.

The other modules are placeholders. Each one's build file comment names its phase and PLAN.md section.

How Lich's classes load, and where a new class goes:
- `LichSystemClassLoader` defines everything on the class path. Its compat pass skips `dev.lich.*` and ASM.
- Boot makes `dev.lich.boot.`, `.launchwrapper.`, `.transform.` and `.compat.` class loader exclusions,
  plus `org.objectweb.asm.` (one ASM for everyone). So shims that game or mod code calls must live under
  `dev.lich.compat.`, and there is one copy of each, whichever loader the caller comes from.
- Patches to game classes run *last*, keyed on SRG names: running them first would change the bytes
  that Forge's binpatches checksum.
- Lich's jars and ASM 9 are hidden from FML's mod discovery (`LichSources`), because FML's ASM-5-API
  visitors fail on Java 9+ class files. A new Lich jar is hidden automatically: the convention plugin's
  `Implementation-Title: lich-<name>` is what `LichSystemClassLoader` recognizes.

LaunchWrapper compatibility rule: `LaunchClassLoader` keeps 1.12's private fields by name and type,
because Mixin, coremods and FoamFix-style mods reflect on them (`LaunchClassLoaderTest` pins them). Lich
code doesn't add public API to the `net.minecraft.launchwrapper` classes. Hooks go in `dev.lich.launchwrapper`.
Two deliberate differences: the compat pass also runs on transformer-excluded classes, and
`getSources()` leaves out `LichSources`.

## Conventions

- Java 25 everywhere, except code that Forge or Java 8 must load (the Forge side of `testmod`), which will
  get its own convention plugin.
- 4-space indents. Each class gets a short Javadoc that says what it does. Comments explain *why*.
- Keep version-specific code in `versions/<mc>/`. Core modules stay version-neutral, so 1.12.2 can be
  added later as three new modules.
- **Server safety** (PLAN.md §6): Lich core and `perf` never touch movement/physics, the tick timer,
  attack/dig timing, packet timing/content, or `MathHelper`.
- Don't commit unless asked.
