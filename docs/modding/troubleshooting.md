# Troubleshooting

## Where to look

- **The game log.** `run/<profile>-<mc>/logs/latest.log` under devlaunch, or the instance's
  `minecraft/logs/latest.log` in Prism. (The game's stdout is log4j XML; the file is plain text.)
- **The first line** is `Lich <version> on Java ..., <profile> profile`. Then, if Lich mods were found:
  `Lich mods: [<id> <version> (<jar>), ...]; hooks: [...]`. If that line is missing, no Lich mod was found.
- **Compat and fix lines** (`Compat: ...`, `Game fix: ...`, `LWJGL 3: ...`) are Lich's own work on the game and mod
  bytecode, not errors.

## Mods that fail to load

When mods can't load, Lich writes **one fatal message listing every problem** and exits before the game window opens:

```
Lich can't load the installed mods:
  - Hello Lich (hello.jar) is for Minecraft 1.8.9, not 1.7.10
  - Other (other.jar) needs the mod hello >=2.0, which isn't installed
```

There is no in-game error screen at that point; read the log or the launcher's console. Fix everything in the list,
then relaunch.

| Message | What it means and what to do |
|---|---|
| `lich.mod.json isn't valid JSON: ...` | A syntax error in the descriptor |
| `Unsupported lich.mod.json schema (missing)` | Add `"schema": 1` |
| `Mod id "X" must be 2-64 characters of a-z, 0-9, _ and -, starting with a letter` | Fix the `id` |
| `Mod X has no version` / `Not a version: "..."` | `version` missing, or has a non-numeric dotted part. Suffixes after `-` or `+` are fine |
| `Mod X doesn't say which Minecraft versions it's for ("minecraft")` | Add `minecraft` |
| `"foo" must be a string` / `a string or a list of strings` / `an object` / `a number` | A field has the wrong JSON type. The name says which |
| `Mod X has an unknown kind of entrypoint "y"` | Only `preLaunch`, `init`, `client` |
| `Mod X: depends.forge must be required, optional or incompatible, not ...` | `forge` is not a version range |
| `Mod X: "versions" keys are exact Minecraft versions, not "..."` | See [multi-version](multi-version.md) |
| `Mod X has a "versions.1.8.9" part, but "minecraft" doesn't list 1.8.9` | The part's version must be accepted by `minecraft` |
| `Mod X: "versions.1.8.9" can't set "foo" (it may set [...])` | Only `entrypoints`, `hooks`, `provides`, `mixins`, `accessWidener` |
| `<name> (<jar>) is for Minecraft A, not B` | The jar doesn't list the running version |
| `Mod X is installed twice: a.jar and b.jar` | Two jars with the same `id`. Remove one |
| `<name> needs Forge` / `doesn't work with Forge` | `depends.forge` vs the running profile |
| `<name> needs Java N; the game runs on Java M` | `javaVersion` is higher than the JVM's |
| `<name> declares raw transformers, which this version of Lich doesn't support yet` | Remove `transformers` and use a Mixin |
| `Mods a and b both replace S; remove one of them` | Two mods list the same `provides` id |
| `<name> needs Lich <range>; this is Lich <version>` | Update Lich, or loosen `depends.lich` |
| `<name> needs the mod X <range>, which isn't installed` | Install it, or remove the dependency |
| `<name> needs X <range>; <version> is installed` | Wrong version of a dependency |
| `Mods depend on each other in a circle: a → b → a` | A dependency cycle |
| `<jar>: its access widener X is missing` | `accessWidener` path not in the jar |
| `<mod>: its access widener uses "named" names; a built mod's must use "srg"` | The jar was not made by `reobfJar` |
| `<mod>: bad access widener X: ...` | See [Access wideners](access-wideners.md#errors) |
| `Can't list <dir>: ...` | The `mods` folder could not be read |
| `Lich mods are installed, but this launch has no MCP mappings` | Reinstall Lich ([Forge and Lich](forge-interop.md#the-vanilla-profile-needs-mcp-data)) |
| `Lich mods can't load in a relaunched game yet; leaving out [...]` | A relaunching loader is in play; Lich mods are skipped (a warning, not a stop) |

## Errors while the game runs

| Message | What it means and what to do |
|---|---|
| `<mod> listens to <Event>, but doesn't list the hook "<id>" under "hooks" in its lich.mod.json, so Lich didn't weave it into the game` | You called `register` on an event whose hook isn't listed. Add the id to `hooks` |
| `Mod X failed in its init entrypoint C` | Your entrypoint threw. The cause is in the stack trace below it |
| `Mod X's init entrypoint C can't be created (it needs a public no-argument constructor)` | See [Lifecycle](lifecycle.md#writing-one) |
| `Mod X's init entrypoint C doesn't implement dev.lich.api.entrypoint.InitEntrypoint` | Wrong interface for the kind |
| `Key bindings can only be registered once the game's options exist (from an init entrypoint on)` | `KeyBindings.register` ran in `preLaunch` |
| `IllegalClassLoadError` for a class in your Mixin package | Something referenced a Mixin class directly. See [Mixins](mixins.md#rules-and-pitfalls) |
| `NoSuchMethodError` / `NoSuchFieldError` on a `func_` / `field_` name | Your code was compiled against MCP names but the jar wasn't reobfuscated. Ship `reobfJar`'s jar, not the `-dev` jar |
| `NoSuchMethodError` for a name that exists on one Minecraft version only | Version-specific code in a shared class. See [multi-version](multi-version.md) |

### "Why does nothing happen?"

Check, in this order:

1. **Is the mod in the log?** Look for `Lich mods: [...]`. No: wrong folder, no `lich.mod.json` at the *root* of the
   jar, or the jar wasn't built.
2. **Is the hook in the log's `hooks: [...]`?** If not, add it to `lich.mod.json`.
3. **Did you ship the right jar?** `lich-<name>-<version>.jar` (SRG), not `...-dev.jar` (MCP names).
4. **Does the event fire in this profile?** Test `lich` and `lich-forge`. A Mixin of yours that targets a class Forge
   changes can behave differently.
5. **Is the entrypoint listed under the kind you expect?** `preLaunch` is too early for game objects.
6. **For a Mixin:** is the refmap in the jar, and does the config's `"refmap"` name match? Did the Mixin report an
   error during startup (a `required` config stops the game)?

## Build errors

| Message | Cause |
|---|---|
| `Call lich.minecraft("<version>") first` | `mixinRefmap`/`accessWidener` before `minecraft(...)` |
| `lich.minecraft(...) can only be set once` | Two calls |
| `lich.minecraft(...) lists a version twice` | Duplicate in the list |
| `lich.forMinecraft(...) is for builds with several versions` | You called it with a single-version build. Set `mixinRefmap`/`accessWidener` in `lich { }` directly |
| `With several Minecraft versions, set mixinRefmap per version: lich.forMinecraft("1.7.10") { ... }` | Move them into `forMinecraft` |
| `Access widener X is not in [...]` | The file is not in that version's resources |
| `These files differ between the Minecraft versions they're built for: ...` | Shared code links differently on two versions. See [the check](multi-version.md#the-check) |
| `Mixins in src/main: [...]. In a multi-version build they go in a version's src/mc<version>/java` | See [multi-version](multi-version.md) |
| `Couldn't set up Minecraft <version>` | The first build couldn't download the game, its libraries or MCP's data. Check the connection; the plugin needs `piston-meta.mojang.com`, the download hosts Mojang's version JSON names (the client jar and libraries) and `maven.minecraftforge.net` |

The build also needs Maven Central and `https://maven.fabricmc.net` (Mixin) as repositories; in the Lich repository
they are declared in the root `settings.gradle.kts`.

## Reporting a bug

Include the Lich version (the first log line), the profile, the Minecraft version, the jar, and the section of
`latest.log` from the first `Lich` line to the first error. For a mod that loads but misbehaves only with Forge,
say which Forge mods are installed.
