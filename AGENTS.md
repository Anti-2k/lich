# AGENTS.md

Lich is a client-side runtime and mod loader that runs Minecraft 1.7.10, 1.8.9 and 1.12.2 on Java 25. It loads
unmodified Java 8 Forge mods through real Forge, patched at load time, and its own mod format
(`lich.mod.json`) for mods written for Lich. Package root: `dev.lich`.

`README.md` is the user-facing overview. `docs/modding/` is the guide for Lich mod authors: when a change touches what
a mod sees (`lich.mod.json`, the `dev.lich.api` events and hook ids, the `dev.lich.mod` plugin, wideners), update the
matching page there too. The guide is published to GitHub Pages from `main` (`mkdocs.yml`, `.github/workflows/docs.yml`):
a new page goes in `mkdocs.yml`'s `nav`, links leaving `docs/modding/` use full GitHub URLs, and the strict build
(`pip install -r docs/requirements.txt`, `mkdocs build`) fails on broken links. **`DOCS.md` is the source of truth** for the design, the decisions
already made (own LaunchWrapper, SRG runtime namespace, client only, public-server safety) and the phased
roadmap. Read it before starting a phase, and update it when a decision changes or a **(verify)** item gets
checked. Code comments cite it by section (`DOCS.md §2.5`), so keep its section numbers stable. If the
README's support tables change (a new version, platform or loader), update them too.

## Build

- JDK 25 (Temurin) builds everything and runs the Gradle daemon (`gradle/gradle-daemon-jvm.properties`).
  JDK 8 runs the baseline games. Toolchain auto-download is off, so JDKs are installed by hand; Gradle
  finds them through the usual detection (registry, `JAVA_HOME`, `org.gradle.java.installations.paths` in
  your user `gradle.properties`).
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
./gradlew :devlaunch:runGame "-Plich.mc=1.8.9" "-Plich.gameJavaHome=<JDK 8 home>"
# Through Lich on Java 25, without Forge (lich) or with it (lich-forge). Every profile but vanilla first
# writes lich-<mc>, forge-<mc> and lich-forge-<mc> into run/cache (:installer:installDevProfile)
./gradlew :devlaunch:runGame "-Plich.profile=lich-forge" "-Plich.mc=1.7.10" "-Plich.gameJavaHome=<JDK 25 home>"
# 1.12.2 has three supported Forge builds (14.23.5.2859 recommended, the default; 14.23.5.2860; 14.23.5.2864 latest): pick one for the
# forge-1.12.2 and lich-forge-1.12.2 profiles with -Plich.forge
./gradlew :devlaunch:runGame "-Plich.profile=lich-forge" "-Plich.mc=1.12.2" "-Plich.forge=14.23.5.2864" "-Plich.gameJavaHome=<JDK 25 home>"
# Join a server straight away, or just print the command
./gradlew :devlaunch:runGame "-Plich.profile=lich" "-Plich.args=--game-arg --server --game-arg 127.0.0.1 --game-arg --port --game-arg 25599"
./gradlew :devlaunch:runGame "-Plich.args=--dry-run --skip-assets"
```

- In PowerShell, **quote every `-P` argument**. Otherwise PowerShell splits `-Plich.profile=lich` at
  the dot and Gradle reports an unknown task.
- `-Plich.gameJavaHome` is needed for JDKs Gradle doesn't detect (unzipped ones have no registry entry).
  Without it, `-Plich.gameJava=<n>` (default 8) picks a detected toolchain.
- `-Plich.gameDir=<dir>` puts the game directory (mods, saves, logs) elsewhere while downloads stay in `run/cache`,
  for projects that keep their test games in their own repository (see Conventions). It's a property of its own because
  `-Plich.args` is split on spaces.
- Each profile has its own game directory, `run/<profile>-<mc>` (vanilla: `run/<mc>`). Boot logs
  `Lich <version> on Java …, <profile> profile` as its first line, then every compat-pass hit
  (`Compat: …`) and game fix (`Game fix: …`). The `forge-<mc>` JSONs come from
  `tools/installer/src/main/resources/dev/lich/installer/forge-<mc>.json` (Forge's own library list);
  devlaunch downloads Forge from Forge's Maven into `run/cache`.
- To check a change to Forge on Java 25, diff the game log against the Java 8 `forge` profile's:
  apart from Lich's own lines, Java versions and thread interleaving they should match line for line.
- Local test servers: `run/server-1.8.9` (port 25599), `run/server-1.7.10` (port 25598) and `run/server-1.12.2` (port 25597, RCON
  25587 with password `lichtest`), vanilla
  `server.jar` from the version JSON, `online-mode=false`, `server-ip=127.0.0.1`. `run/` isn't in git, so
  create them on a new machine (download the jar, accept the EULA, set those two properties and the port).
  Start them with JDK 8 (`java -jar server.jar nogui` in that folder) and wait for `Done (` in the console.
  Stop them by the PID that owns the listening port (Windows: `Get-NetTCPConnection -LocalPort 25599 -State
  Listen`; elsewhere `lsof -i :25599`).
- Other devlaunch options (`--version-json`, `--jvm-arg`, `--extra-cp`, `--main-class`, …): see `Options.USAGE`.
- Lich profiles run the game on LWJGL 3 (DOCS.md §2.5). `--jvm-arg -Dlich.lwjgl=2` runs the same profile on the
  game's LWJGL 2, to tell a Lich bug from an LWJGL 3 one. Boot logs each LWJGL call it rewrote (`LWJGL 3: …`),
  once per member and package.
- The game's stdout is log4j XML (Mojang's launcher config). Read `run/<version>/logs/latest.log`
  for plain text. The main menu is reached once it shows `Sound engine started` and a `…atlas` line.
- Someone may be using the same desktop while test games run: always pass `--jvm-arg -Dlich.dev.noMouseGrab=true`
  in `-Plich.args` (Lich profiles), so the game never locks or moves the real cursor; it still behaves as if grabbed.
- To stop a game you started, match the java process on this repo's `run/cache` and its main class
  (`dev.lich.boot.Main`, `net.minecraft.launchwrapper.Launch` or `net.minecraft.client.main.Main`) in
  its command line. Never kill java processes by name alone: Gradle daemons and the user's own game
  run on java too.

## Testing in Prism (real account, public servers, singleplayer)

- By convention the test instances are `lich-forge-1.8.9`, `lich-forge-1.7.10` and `lich-forge-1.12.2`: a Forge instance with
  Lich added as a custom component, Java 25 pinned, `AutomaticJava=false` and `IgnoreJavaCompatibility=true`
  in `instance.cfg` (otherwise Prism falls back to Java 8). Make one by copying a Forge instance, or ask which
  to use. **Only touch instances made for Lich testing**: any other instance is the user's own, and may be what
  they play on. Test the `lich` profile (no Forge) through devlaunch. Prism only lists a new instance folder
  after a rescan or restart; don't restart the user's Prism for them.
- After a Lich build, refresh an instance with `./gradlew :installer:installPrism "-Plich.mc=1.8.9"` (default
  instance: `%APPDATA%/PrismLauncher/instances/lich-forge-<mc>`; `-Plich.prismInstance=<dir>` for any other,
  e.g. on Linux or a portable Prism). It rewrites `patches/dev.lich.json` (main class, `+libraries` with `MMC-hint: local`, `+jvmArgs`), copies
  the jars into the instance's `libraries/` and keeps the `dev.lich` entry last in `mmc-pack.json`
  (Prism takes the main class from the last component that sets one, and Forge sets LaunchWrapper's).
- Smoke test without a real account: `prismlauncher --launch lich-forge-1.8.9 --offline Dev`. The
  game log is `instances/lich-forge-<mc>/minecraft/logs/latest.log`, and Prism's own log (`PrismLauncher/logs`)
  shows `Applying "dev.lich" GOOD` and the full JVM arguments. `--world` doesn't open worlds on these
  old versions. If a launch stalls, read the console window's text (on Windows, with UI Automation). Offline mode
  never downloads missing libraries; a copied 1.7.10 instance can lack guava 15 and commons-lang3
  3.1, which Forge instances never fetch.
- Prism starts games through `org.prismlauncher.EntryPoint` and passes the main class on stdin, so to
  stop a Prism test game, match `instances/<instance>/` and `org.prismlauncher.EntryPoint` in the
  command line, and check there's no other game from that instance (the user may be playing).

## Layout

```
build-logic/        convention plugin `lich.java` (Java 25, UTF-8, -Xlint:all)
core/               boot, launchwrapper, transform, remap, loader, mixin-provider
compat/             jdk (removed-API shims, reflection compat), asm (ASM 5 on ASM 9), lwjgl2 (LWJGL 2 API on LWJGL 3)
api/                public version-neutral Lich API
versions/<mc>/      compat-mc, compat-forge, api; Gradle projects are named v1_8_9-compat-mc etc. (1.7.10, 1.8.9, 1.12.2)
versions/shared/    compat-mc (project shared-compat-mc): game fixes identical in several versions
                    (the sound and shutdown races: 1.8.9 and 1.12.2; branding: all three);
                    api: sources both per-version api projects compile (no project of its own)
mods/               first-party Lich mods, not part of the runtime
tools/              devlaunch, installer; git-hooks (local guard against jars and private names, see Conventions)
test/               testmod, multimod, harness
mcdev/              included build: remap-core and the dev.lich.mod Gradle plugin (Minecraft with MCP names
                    to compile against, refmaps, reobfuscation to SRG) for Lich mods and the api projects
```

Working so far:
- `launchwrapper`: Lich's LaunchWrapper 1.12 (`net.minecraft.launchwrapper.*`), plus
  `dev.lich.launchwrapper` for the launch logic, hooks, Lich's first/last transformers and `LichSources`.
- `boot`: `dev.lich.boot.Main` (flag check, safe logging, compat pass, profile, vanilla tweaker) and
  `LichSystemClassLoader`, the JVM's system class loader under Lich. Safe logging is the Log4Shell-safe config
  plus `SessionLogFilter`, which keeps the access token out of every log4j context; boot also sends a Java 8
  `http.agent` (DOCS.md §2.4).
- `transform`: the compat pass, `CompatTransformer`. `compat-jdk` and `compat-asm` hold the shims and
  register their rules with it (`JdkCompat`, `AsmCompat`).
- `shared-compat-mc`, `v1_7_10-compat-mc`, `v1_8_9-compat-mc` and `v1_12_2-compat-mc`: game fixes on SRG names, run as
  Lich's last transformer in both profiles (the vanilla profile needs MCP's mappings for SRG names).
  `v1_7_10-compat-forge`/`v1_8_9-compat-forge`/`v1_12_2-compat-forge`: hide Lich mods from FML's discovery.
- `remap`: the vanilla profile's obf → SRG remapper. `loader`, `api` and the `versions/<mc>/api` projects: Lich
  mods (`lich.mod.json`, entrypoints, hooks woven on demand); a full client mod runs on them. `mixin-provider`: the Mixin
  Lich mods run on, started only when Lich mods are installed. `testmod`: a Lich mod for 1.8.9 that touches every
  part of the loader (`:testmod:installDev`). `multimod`: one jar for 1.7.10, 1.8.9 and 1.12.2, built with the plugin's
  multi-version mode (`lich { minecraft("1.7.10", "1.8.9", "1.12.2") }`, DOCS.md §5.1); its build fails if a shared class
  links differently on the two versions. Try it without touching a mods folder through
  `--jvm-arg -Dlich.devMods=<jar>` in `-Plich.args`.
- `compat-lwjgl2`: LWJGL 2's API on LWJGL 3 (DOCS.md §2.5). LWJGL 2-only classes under their own names
  (`org.lwjgl.opengl.Display`, ...), with their logic in `dev.lich.compat.lwjgl2.display`; `Lwjgl2Compat`'s
  call table for members LWJGL 3 renamed (`renames.txt`, generated) or dropped (the `Lwjgl2*` mirrors).
  `./gradlew :compat-lwjgl2:checkLinkage` lists the LWJGL references in the game, Forge and `run/corpus`
  that still don't link; rerun it after changing the table. `generateRenames` and `generateCapabilities`
  regenerate the generated parts after an LWJGL update.
- `installer`: `ProfileInstaller` writes `versions/{lich,forge,lich-forge}-<mc>/` and `libraries/`, and
  `PrismComponent` writes Prism components. `PrismInstaller` (window and CLI, `:installer:installerJar`) is the
  player-facing Prism installer (DOCS.md §4.6): it creates or fixes the instance (Forge build, Java 25), and the jar
  carries Lich's jars plus a download list for the rest (`Payload`). Test it against a scratch Prism data folder
  (`--prism <dir>`, or `prismlauncher --dir <dir>` for a real launch), never the user's own instances.
- `devlaunch`.
- `harness` (`test/harness`): Forge mod compatibility through whole modpacks, pinned in `packs.json` (Modrinth, Technic
  Solder or zip, FTB, CurseForge (through FTB's mirror) or ATLauncher sources; DOCS.md §7). `./gradlew :harness:runPack "-Plich.pack=rlcraft"` plays one
  scenario through a driver tweaker (boot, the local test server, a singleplayer world:
  spawn, tour, creative inventory, hold, save, reload, exit) on Forge/Java 8 and Lich/Java 25, and writes
  `run/harness/<pack>/report.md` (a verdict: pass, review, FAIL, pack broken; the steps side by side; suspect lines
  only Lich logged first) and `run/harness/summary.md`. The Forge run is a cached baseline (`baseline.json`), rerun
  only when the pack, driver or scenario settings change, so a rerun after a Lich change runs Lich alone.
  `-Plich.pack` takes ids, comma-separated, `all`, `@quick` or `@1.12.2`; `-Plich.until=boot` stops after a step;
  `-Plich.skip=server` leaves steps out (straight to the world); `-Plich.mods=on` adds FML's mod list and every
  config screen, off by default, for looking into a pack's trouble; `-Plich.baseline=rerun|skip|forge` (`skip` plays Lich alone; after a
  break, `forge` plays Forge alone and compares it with that Lich run), `-Plich.hold=<s>`, `-Plich.gallery=on` (place every block), `-Plich.server=off`. No
  `-Plich.mc`: both versions' dev profiles get installed. A stall leaves a thread dump in `stall-threads-<profile>.txt`.
  Run one game at a time (a big 1.7.10 pack takes ~6 GB of RAM; the harness does), and no Hypixel
  SkyBlock packs (project policy: repetitive, RAT risk). `:harness:prismPack`
  creates Prism instances `<pack>-forge-<mc>` and `<pack>-lich-<mc>`.
  Refresh the `-lich-` ones with `installPrism` after a Lich build.

Each module's build file comment names its phase and DOCS.md section.

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
Five deliberate differences (DOCS.md §4.2): Lich's first and last transformers also run on transformer-excluded
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
- **Core doesn't change the game** (DOCS.md §0): a patch to a game class goes in core only if the game
  needs it to run on Java 25. Improvements go in a Lich mod, and
  whatever those mods need from the game becomes a hook in the API, woven only when a loaded mod
  declares it (§5.2). With no Lich mods, the game plays exactly as vanilla or Forge does on Java 8. The one
  exception is the branding line (`Lich <version> on Java <n>` in the main menu and FML's branding, §0).
- The version lives in `gradle.properties` (and `mcdev/gradle.properties`); everything shown to players reads it
  from the jars' `Implementation-Version`.
- **Server safety** (DOCS.md §6): Lich core never touches movement/physics, the tick timer,
  attack/dig timing, packet timing/content, or `MathHelper`.
- **Projects built on Lich stay out of this repository.** A mod or client that lives in a repository of its own builds
  against this checkout through a composite build (its `-Plich.dir`) and keeps everything of its own on its side:
  - Its test games run through this checkout's devlaunch with `-Plich.gameDir=<its repository>/run/<profile>-<mc>`
    (from its own directory: `./gradlew :lich:devlaunch:runGame ...`), so its jars, worlds, screenshots and logs never
    land in this checkout's `run/`.
  - A Lich change it needs (a new hook, a fix) goes on a branch in a git worktree beside this checkout:
    `git worktree add ../lich-<topic> -b <topic>`, with a topic that describes the change, not the project. The project
    builds and tests against the worktree (`-Plich.dir=<worktree>`). To reuse the download cache instead of
    downloading it again, link the worktree's `run/cache` to this checkout's (Windows:
    `New-Item -ItemType Junction <worktree>/run/cache -Target <checkout>/run/cache`; elsewhere `ln -s`). devlaunch
    reinstalls the Lich profile into the cache before every run, so don't run games from two checkouts at once. The
    work stays uncommitted in the worktree until the user asks; `git worktree remove` once the branch is merged.
  - Nothing of such a project (jars, source, names) goes into this repository, its branches, tags or messages: describe
    the need generically and use `examplemod` / `dev.example` in examples. The local hooks in `tools/git-hooks` back
    this up. Install them once per clone (worktrees share them) with
    `cp tools/git-hooks/* "$(git rev-parse --git-common-dir)/hooks/"`, and list the words to refuse in
    `.git/info/forbidden-patterns` (one case-insensitive regex per line; local, never versioned). They refuse a jar
    other than the Gradle wrapper's, and any staged path, added line, commit or tag message or pushed ref that
    matches. Never bypass them (`--no-verify`); if one fires, remove the text.
- Don't commit unless asked.
