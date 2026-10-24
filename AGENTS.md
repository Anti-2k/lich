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
# Vanilla baseline on Java 8
./gradlew :devlaunch:runGame "-Plich.mc=1.8.9" "-Plich.gameJavaHome=C:/Program Files/Java/jdk-8"
# Through Lich on Java 25: writes the lich-<mc> profile into run/cache first (:installer:installDevProfile)
./gradlew :devlaunch:runGame "-Plich.profile=lich" "-Plich.mc=1.7.10" "-Plich.gameJavaHome=C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
# Join a server straight away, or just print the command
./gradlew :devlaunch:runGame "-Plich.profile=lich" "-Plich.args=--game-arg --server --game-arg 127.0.0.1 --game-arg --port --game-arg 25599"
./gradlew :devlaunch:runGame "-Plich.args=--dry-run --skip-assets"
```

- In PowerShell, **quote every `-P` argument**. Otherwise PowerShell splits `-Plich.profile=lich` at
  the dot and Gradle reports an unknown task.
- `-Plich.gameJavaHome` is needed for JDKs Gradle doesn't detect (unzipped ones have no registry entry).
  Without it, `-Plich.gameJava=<n>` (default 8) picks a detected toolchain.
- The Lich profile's game directory is `run/lich-<mc>`, separate from vanilla's `run/<mc>`. Boot logs
  `Lich <version> on Java …, <profile> profile` as its first line.
- Local test servers: `run/server-1.8.9` (port 25599) and `run/server-1.7.10` (port 25598), vanilla
  `server.jar` from the version JSON, offline mode, bound to `127.0.0.1` only. Start them with JDK 8
  (`java -jar server.jar nogui` in that folder) and wait for `Done (` in the console. Stop them by the
  PID that owns the listening port (`Get-NetTCPConnection -LocalPort 25599 -State Listen`).
- Other devlaunch options (`--version-json`, `--jvm-arg`, `--extra-cp`, `--main-class`, …): see `Options.USAGE`.
- The game's stdout is log4j XML (Mojang's launcher config). Read `run/<version>/logs/latest.log`
  for plain text. The main menu is reached once it shows `Sound engine started` and a `…atlas` line.
- To stop a game you started, match the java process on both `net.minecraft.client.main.Main` and
  `lich\run\cache` in its command line. Never kill java processes by name alone: Gradle daemons and
  the user's own game run on java too.

## Testing in Prism (real account, public servers, singleplayer)

- Prism instances `lich-1.8.9` ("1.8.9 (Lich)") and `lich-1.7.10` ("1.7.10 (Lich)") are copies of the
  user's Forge instances `1.8.9` and `1.7.10` with Forge removed and Lich added as a custom component.
  Java 25 is pinned, `AutomaticJava=false` and `IgnoreJavaCompatibility=true`. The Forge mods sit in
  `minecraft/mods-disabled`. Resource packs weren't copied. **Never edit the original `1.8.9`/`1.7.10`
  instances**: they're the user's own test instances (Forge + OptiFine).
- After a Lich build, refresh an instance with `./gradlew :installer:installPrism "-Plich.mc=1.8.9"`. It
  rewrites `patches/dev.lich.json` (main class, `+libraries` with `MMC-hint: local`, `+jvmArgs`), copies
  the jars into the instance's `libraries/` and keeps the `dev.lich` entry right after Minecraft in
  `mmc-pack.json`. `-Plich.prismInstance=<dir>` targets another instance.
- Smoke test without the user's account: `prismlauncher.exe --launch lich-1.8.9 --offline Dev`. The game
  log is `instances/lich-<mc>/minecraft/logs/latest.log`, and Prism's own log (`PrismLauncher/logs`)
  shows `Applying "dev.lich" GOOD` and the full JVM arguments. `--world` doesn't open worlds on these
  old versions. If a launch stalls, read the console window's text with UI Automation. Offline mode
  never downloads missing libraries; that's how the 1.7.10 copy first failed (guava 15 and commons-lang3
  3.1, which Forge instances never fetch).

## Layout

```
build-logic/        convention plugin `lich.java` (Java 25, UTF-8, -Xlint:all)
core/               boot, launchwrapper, transform, remap, loader, mixin-provider, perf
compat/             jdk (removed-API shims, reflection compat), lwjgl2 (LWJGL 2 API on LWJGL 3)
api/                public version-neutral Lich API
versions/<mc>/      compat-mc, compat-forge, api-impl; Gradle projects are named v1_8_9-compat-mc etc.
tools/              devlaunch, installer
test/               testmod, harness
```

Working so far: `launchwrapper` (Lich's LaunchWrapper 1.12: `net.minecraft.launchwrapper.*`, plus
`dev.lich.launchwrapper` for the launch logic, hooks and Lich's first/last transformers), `boot`
(`dev.lich.boot.Main`: flag check, safe logging, profile, vanilla tweaker), `installer`
(`ProfileInstaller` writes `versions/lich-<mc>/` and `libraries/`) and `devlaunch`. The other modules
are placeholders. Each one's build file comment names its phase and PLAN.md section.

LaunchWrapper compatibility rule: `LaunchClassLoader` keeps 1.12's private fields by name and type,
because Mixin, coremods and FoamFix-style mods reflect on them (`LaunchClassLoaderTest` pins them). Lich
code doesn't add public API to the `net.minecraft.launchwrapper` classes. Hooks go in `dev.lich.launchwrapper`.

## Conventions

- Java 25 everywhere, except code that Forge or Java 8 must load (the Forge side of `testmod`), which will
  get its own convention plugin.
- 4-space indents. Each class gets a short Javadoc that says what it does. Comments explain *why*.
- Keep version-specific code in `versions/<mc>/`. Core modules stay version-neutral, so 1.12.2 can be
  added later as three new modules.
- **Server safety** (PLAN.md §6): Lich core and `perf` never touch movement/physics, the tick timer,
  attack/dig timing, packet timing/content, or `MathHelper`.
- Don't commit unless asked.
