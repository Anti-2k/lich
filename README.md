# Lich

**Run Minecraft 1.7.10 and 1.8.9 on Java 25, with your Forge mods.**

Lich is a client-side runtime and mod loader for legacy Minecraft. It starts the game on a modern JVM and
patches the game, Forge and mod bytecode at load time so that unmodified Java 8 Forge mods keep working: no
rebuilt jars and no per-mod setup. It also has its own mod format (`lich.mod.json`) with deep, opt-in hooks
into the game, for mods written for Lich.

With no Lich mods installed, the game plays exactly as vanilla or Forge does on Java 8. Lich changes only
what the game needs to run on Java 25.

## Features

- **Real Forge, unmodified.** Forge's binpatches are applied at runtime, as on Java 8. Mods load in their
  own format (`@Mod` + `mcmod.info`), coremods and Mixins included.
- **Java 25.** Shims for the JDK APIs these versions rely on and that are gone (SecurityManager, Pack200,
  `Field.modifiers`, `sun.reflect`, JAXB, Nashorn, …), ASM 9 with ASM 5 behaviour where mods depend on it,
  and a LaunchWrapper that works with Java 9+ class loading.
- **LWJGL 3.** The game runs on LWJGL 3.3.6 through a LWJGL 2 compatibility layer, so mods that call
  `Display`, `Keyboard`, `Mouse` or `GL11` keep working.
- **Safe on public servers.** Lich never touches movement, physics, the tick timer, attack/dig timing or
  packets. Your client looks and behaves like a normal vanilla/Forge client.
- **Safer logging.** Log4Shell lookups are filtered even when the launcher passes no log config, and the
  access token is kept out of the game log.
- **Lich mods.** Own format with entrypoints, events, Mixin 0.8 + MixinExtras and access wideners. Runs with or
  without Forge, on SRG names in both cases, so one jar covers both. Writing one is covered in the
  [mod developer guide](docs/modding/README.md).

## Supported versions

| Minecraft | Forge | Runs on | Status |
|---|---|---|---|
| 1.8.9 | 11.15.1.2318 (or no Forge) | Java 25 | ✅ singleplayer, public servers, modpacks |
| 1.7.10 | 10.13.4.1614 (or no Forge) | Java 25 | ✅ singleplayer, public servers, modpacks |
| 1.12.2 | — | — | planned |

| Java | Status |
|---|---|
| 25 (Temurin 25 tested) | ✅ supported; Lich requires it |
| 8 | the game's own Java: use plain vanilla/Forge, not Lich |
| 9–24, 26+ | untested |

| Platform | Status |
|---|---|
| Windows x64 | ✅ tested |
| Linux (X11, Wayland), macOS (Apple Silicon) | untested |

## Compatibility with other mods and loaders

| Loader / mod | Status | Notes |
|---|---|---|
| Forge mods and coremods | ✅ | Tested with whole modpacks (below) against the same pack on Forge/Java 8 |
| Mixin 0.7.x (shaded by mods) | ✅ | Runs the copy the mod ships, as Forge does |
| UniMixins, GTNH mods (Hodgepodge, Angelica, ArchaicFix) | ✅ | |
| Essential (relaunching loader, Mixin 0.8) | ✅ | |
| OneConfig mods | ✅ | Including its NanoVG renderer on LWJGL 3 |
| OptiFine | ✅ | 1.8.9 L5, 1.7.10 E7 |
| LiteLoader 1.7.10 | ⚠️ | Litemods load; their class path order can differ from Java 8 |
| lwjgl3ify | ➖ | Not needed: Lich provides LWJGL 3 itself |
| Fabric / Legacy Fabric | ❌ | Not supported |

Modpacks that pass Lich's harness (boot, mod list, server join, world tour, save and reload) include
GT New Horizons, FTB Infinity Evolved, FTB SkyFactory 2.5, Blightfall, Tekxit 2, The Lost Era, Resonant Rise 3
(1.7.10), SkyBlock Enhanced, the 1.8 PvP Pack, BedWars+ and SolitaryCraft VanillaPlus (1.8.9). The full list,
and what each one turned up, is in [DOCS.md §7](DOCS.md#7-testing).

## Installing

Lich installs into [Prism Launcher](https://prismlauncher.org/) (other launchers aren't supported yet).

1. Install Java 25 or newer, e.g. [Temurin](https://adoptium.net/).
2. Close Prism, then open `lich-installer-<version>.jar` (double-click it, or `java -jar lich-installer-<version>.jar`).
3. Pick **New instance** (Minecraft 1.8.9 or 1.7.10, with or without Forge), or **Existing instance** to add Lich
   to one you already have. Then press **Install**.
4. Start Prism and launch the instance. On the first launch Prism downloads Minecraft, Forge and Lich's
   libraries.

The installer:
- installs the Forge build Lich supports (`11.15.1.2318` for 1.8.9, `10.13.4.1614` for 1.7.10). If an
  existing instance has another Forge build, it asks before switching it.
- sets the instance to Java 25 and turns off Prism's Java compatibility check.
- won't install into instances with Fabric, Quilt or Prism's LWJGL 3 component.
- keeps a copy of the instance's original `mmc-pack.json` and `instance.cfg`, saved as `*.before-lich`.
- can remove Lich again: **Uninstall** in the window, or `--uninstall`.

On the command line (`--help` lists everything):

```sh
java -jar lich-installer.jar --mc 1.7.10                 # new 1.7.10 Forge instance
java -jar lich-installer.jar --instance "My Pack" --yes  # add Lich to an instance, fixing its Forge build
java -jar lich-installer.jar --list                      # instances, and whether Lich can go into them
```

Build the installer with `./gradlew :installer:installerJar` (output: `tools/installer/build/installer/`). More
on launcher integration in [DOCS.md §4.6](DOCS.md#46-launcher-integration).

## Building

Requirements: JDK 25 to build and run Gradle; JDK 8 only to run the Java 8 baselines that Lich is compared
against. Gradle doesn't download JDKs for this project, so install them yourself.

```sh
./gradlew build                                    # every module + unit tests + the installer jar
./gradlew :devlaunch:runGame "-Plich.profile=lich-forge" "-Plich.mc=1.8.9" "-Plich.gameJavaHome=<JDK 25>"
```

`tools/devlaunch` downloads the game, Forge and their libraries into `run/` and starts the game offline.
Contributor and agent notes are in [AGENTS.md](AGENTS.md). Writing a Lich mod? Start with the
[mod developer guide](docs/modding/README.md).

## How it works

```
launcher (Prism / MultiMC / vanilla) ─ Java 25, mainClass = dev.lich.boot.Main
  └─ LichSystemClassLoader     runs the compat pass on the whole class path
      └─ Lich boot             JVM flag check, safe logging, profile (vanilla / Forge)
          └─ Lich LaunchWrapper (API-compatible with LaunchWrapper 1.12)
              transformers: compat pass → [obf → SRG] → Forge/FML + coremods → Mixin
                            → Lich mods → Java 25 game fixes + LWJGL 2 → 3 calls
```

The full design, every Java 25 breakage and its fix, the Lich mod format and the roadmap are in
**[DOCS.md](DOCS.md)**.

## Project status

Current release: **0.3.1** (first release: 0.3.0). Running Forge and its mods on Java 25 works and is tested
with modpacks; Lich mods v0 work, one jar can target both 1.7.10 and 1.8.9, and there's a Prism installer. Mod developer docs are in [docs/modding](docs/modding/README.md). Still to come: Linux and macOS testing, other
launchers, and publishing the API and Gradle plugin so a mod can live outside this repository. See the
[roadmap](DOCS.md#8-roadmap).

## License

Lich is licensed under the [GNU Lesser General Public License v3.0](LICENSE), which builds on the
[GNU GPL v3.0](COPYING). You can ship mods for Lich under any license you like. Changes to Lich itself must
stay under the LGPL. The ASM 5 classes in `compat/asm` are ASM's own code, under ASM's BSD license
(`compat/asm/src/main/resources/META-INF/LICENSE-ASM.txt`).

## Disclaimer

Lich is not affiliated with Mojang, Microsoft or the Forge project. It doesn't ship Minecraft or Forge: the
launcher downloads them as usual, and Lich patches them in memory at load time.
