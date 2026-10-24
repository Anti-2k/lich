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
- `./gradlew build` builds every module.
- Gradle 9.8.2 with the configuration cache on. Inside task lambdas, use only locals of the task's
  configure block. Script-level vals capture the script object, and the cache rejects it.

## Running the game: devlaunch

`tools/devlaunch` is a small launcher. It resolves a version JSON (Mojang's, or a local one with
`inheritsFrom`), downloads the jar, libraries, natives, assets and Mojang's log4j config into
`run/cache`, and starts the game offline (user `Dev`) in `run/<version>`.

```
./gradlew :devlaunch:runGame -Plich.mc=1.8.9 "-Plich.gameJavaHome=C:/Program Files/Java/jdk-8"
./gradlew :devlaunch:runGame -Plich.mc=1.7.10 "-Plich.gameJavaHome=C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot"
./gradlew :devlaunch:runGame "-Plich.args=--dry-run --skip-assets"
```

- `-Plich.gameJavaHome` is needed for JDKs Gradle doesn't detect (unzipped ones have no registry entry).
  Without it, `-Plich.gameJava=<n>` (default 8) picks a detected toolchain.
- Other devlaunch options (`--version-json`, `--jvm-arg`, `--extra-cp`, `--main-class`, …): see `Options.USAGE`.
- The game's stdout is log4j XML (Mojang's launcher config). Read `run/<version>/logs/latest.log`
  for plain text. The main menu is reached once it shows `Sound engine started` and a `…atlas` line.
- To stop a game you started, match the java process on both `net.minecraft.client.main.Main` and
  `lich\run\cache` in its command line. Never kill java processes by name alone: Gradle daemons and
  the user's own game run on java too.

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

Most modules are still placeholders. Their build file comment names the phase and the PLAN.md section.

## Conventions

- Java 25 everywhere, except code that Forge or Java 8 must load (the Forge side of `testmod`), which will
  get its own convention plugin.
- 4-space indents. Each class gets a short Javadoc that says what it does. Comments explain *why*.
- Keep version-specific code in `versions/<mc>/`. Core modules stay version-neutral, so 1.12.2 can be
  added later as three new modules.
- **Server safety** (PLAN.md §6): Lich core and `perf` never touch movement/physics, the tick timer,
  attack/dig timing, packet timing/content, or `MathHelper`.
- Don't commit unless asked.
