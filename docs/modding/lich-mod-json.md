# `lich.mod.json` reference

Every Lich mod has a `lich.mod.json` at the root of its jar. Lich reads it before any game class loads, checks it,
and then decides whether and in what order to load the mod. The code that does it is `ModDescriptor` (shape),
`ModResolver` (does it fit this game and the other mods) and `ModDiscovery` (finding jars), all in `core/loader`.

## A complete example

```jsonc
{
  "schema": 1,
  "id": "examplemod",
  "version": "1.0.0",
  "name": "Example Mod",
  "description": "What it does, in a sentence.",
  "authors": ["Someone"],
  "minecraft": "1.8.9",
  "depends": {
    "lich": ">=0.3",
    "forge": "optional",
    "othermod": ">=2.0 <3"
  },
  "entrypoints": {
    "preLaunch": ["dev.example.PreLaunch"],
    "init":      ["dev.example.Init"],
    "client":    ["dev.example.ClientInit"]
  },
  "hooks": ["render.hud", "input.mouse", "chat.receive"],
  "mixins": ["mixins.examplemod.json"],
  "accessWidener": "examplemod.aw",
  "javaVersion": 25
}
```

The file is plain JSON, read with the game's Gson. The comments in the example are for this page only: leave them
out of a real file.

## Fields

| Field | Type | Required | Meaning |
|---|---|---|---|
| `schema` | number | yes | Must be `1`. Anything else is refused: "Unsupported lich.mod.json schema" |
| `id` | string | yes | 2 to 64 characters of `a-z`, `0-9`, `_`, `-`, starting with a letter. Unique across installed mods |
| `version` | string | yes | Dotted numbers, see [Versions](#versions-and-version-ranges) |
| `name` | string | no | Display name. Defaults to the id |
| `description` | string | no | Defaults to empty |
| `authors` | string or list of strings | no | |
| `minecraft` | string or list of strings | yes | The Minecraft versions the jar runs on, a **whitelist**, see below |
| `depends` | object | no | Other mods, `lich`, `minecraft`, and `forge` |
| `entrypoints` | object | no | `preLaunch`, `init`, `client`, each a class name or a list of them |
| `hooks` | list of strings | no | Hook ids the mod listens to, see [Events and hooks](events-and-hooks.md) |
| `provides` | list of strings | no | **Reserved** for replaceable subsystems. See below |
| `mixins` | list of strings | no | Mixin config files in the jar |
| `accessWidener` | string | no | Path of one access widener in the jar |
| `transformers` | list of strings | no | **Not supported.** Declaring any makes the mod fail to load |
| `javaVersion` | number | no | The lowest Java feature version the mod needs. `0`/absent means it does not say |
| `versions` | object | no | The parts of a multi-version jar that only one Minecraft version uses, see [multi-version](multi-version.md) |

Fields that accept "a string or a list of strings" take either form: `"authors": "me"` and `"authors": ["me"]` are
the same.

**Unknown top-level keys are ignored**, so a typo in `"hooks"` (say `"hook"`) is *not* reported and the mod loads
without it. Two places are strict instead: entrypoint kinds and the keys of a `versions` part. An unknown kind of
entrypoint, or a key in a `versions` part other than the five it may set, is a loading error.

### `id`

The id is how other mods name you in `depends`, how Lich reports you in errors and in `Lich.mod(id)`. Two jars with
the same id are an error ("Mod X is installed twice: a.jar and b.jar"). Pick it once and keep it.

### `minecraft`

A whitelist: one string or a list, each entry an exact version or a [range](#versions-and-version-ranges).

```json
"minecraft": "1.8.9"
"minecraft": ["1.7.10", "1.8.9"]
"minecraft": ">=1.7.10 <1.9"
```

The mod loads if **any** entry accepts the running version. Otherwise Lich reports which versions the mod is for:
"Example Mod (examplemod.jar) is for Minecraft 1.8.9, not 1.7.10". Lich supports exactly 1.7.10 and 1.8.9 today, so
`>=1.7.10 <1.9` currently means both.

Most jars list one version. List two only when the code really runs on both:
[Several Minecraft versions in one jar](multi-version.md).

### `depends`

An object from a name to a [version range](#versions-and-version-ranges). Three names are special:

| Name | Value | Meaning |
|---|---|---|
| `lich` | range | Lich's version must satisfy it. A *development build* of Lich (no version) skips the check |
| `minecraft` | range | An extra constraint on the game, on top of the `minecraft` whitelist |
| `forge` | `"required"`, `"optional"` or `"incompatible"` | Whether Forge must be, may be, or must not be present. Default `"optional"` |

Any other name is the **id of another Lich mod** that must be installed in a matching version. All of these are
required: there is no "optional dependency on a mod". For something that works with or without another mod, check at
run time with `Lich.isModLoaded("id")` and do not list it in `depends`.

The `forge` value is a *string*, not a range. `"forge": ">=11"` is an error:
"depends.forge must be required, optional or incompatible".

Only Lich mods count as installed mods for `depends`; a Forge mod with that id does not satisfy it.

### `entrypoints`

```json
"entrypoints": {
  "preLaunch": ["dev.example.PreLaunch"],
  "init":      ["dev.example.Init"],
  "client":    "dev.example.ClientInit"
}
```

The three kinds, what each class implements, and when it runs are in [Lifecycle and entrypoints](lifecycle.md).
A mod may list several classes per kind, run in the order listed. One class may appear under several kinds.

### `hooks`

The hook ids from [Events and hooks](events-and-hooks.md#hooks). Lich weaves exactly the hooks the loaded mods list.
Listening to an event of a hook you did not list throws, naming your mod. The lifecycle hook is implicit: every Lich
mod gets it, which is what runs entrypoints and fires `LifecycleEvents.SHUTDOWN`.

### `provides`

Reserved for **replaceable subsystems** (a mod swapping, say, chunk mesh building for its own). No subsystem is
defined yet. Today, each id you list counts as a hook id to weave (there is none by that name, so it does nothing),
and if two installed mods list the same id, loading fails: "Mods a and b both replace X; remove one of them". Do not
use it yet.

### `mixins`

Names of Mixin config JSON files in the jar, in load order. See [Mixins](mixins.md). Lich registers them after
its own hooks' config, so your Mixins see the game with Lich's hooks already woven.

### `accessWidener`

The path of one `.aw` file inside the jar. See [Access wideners](access-wideners.md). The file in the built jar must
use the `srg` namespace, which the Gradle plugin makes sure of.

### `transformers`

Raw ASM transformers are designed (DOCS.md §5.2, level 7) but not implemented. A mod that declares any is refused:
"declares raw transformers, which this version of Lich doesn't support yet". Use Mixins.

### `javaVersion`

The lowest Java feature version the mod needs, as a number. Lich runs on Java 25, so a mod needing a higher version
(`26`) is refused: "needs Java 26; the game runs on Java 25". A mod built with `release = 25` should say `25`.

## Versions and version ranges

Mod versions and Lich's own version are dotted numbers: `1`, `1.2`, `1.2.3`, `0.3.1`.

- Missing parts count as `0`: `1.2` equals `1.2.0`.
- **Anything after the first `-` or `+` is kept for display and ignored for comparison.** `1.0.0-beta.2` and `1.0.0`
  compare as equal, and a Lich snapshot satisfies `>=0.3` like the release it leads to. There is no pre-release
  ordering.
- Every numeric part must be an integer. `1.0.x` is "Not a version".

A **range** is used in `depends` and (as the same syntax) in `minecraft`:

| Syntax | Meaning |
|---|---|
| `*` | Anything |
| `1.2.0` or `=1.2.0` | Exactly that |
| `>=0.4`, `>1`, `<2`, `<=2.1` | A bound |
| `>=1.2 <2` | Several bounds separated by spaces: all must hold |
| `>=1.0 <2 \|\| >=3` | Alternatives separated by `\|\|`: any alternative may hold |

There is no caret or tilde syntax. Pick bounds explicitly.

## Discovery

Lich looks for mods in:

1. `<game dir>/mods/*.jar`
2. `<game dir>/mods/<minecraft version>/*.jar` (for example `mods/1.8.9`, where Forge 1.8.9 also looks)
3. whatever `-Dlich.devMods=<path>[;<path>...]` lists: jars or directories, separated by the platform's path
   separator. This is for running a mod from its build output without touching a mods folder

Within each folder, jars are scanned in case-insensitive name order. A jar without `lich.mod.json` at its root is not a Lich mod and
Lich leaves it alone (Forge handles it). A jar with an unreadable or invalid descriptor stops the launch.

Only the **root** of the jar counts: `lich.mod.json` inside a nested jar is not found.

A jar that is both a Lich mod and a Forge mod (has `lich.mod.json` and `mcmod.info`/`@Mod`) is claimed by Lich when
running under Lich, and still works as a plain Forge mod elsewhere. See [Forge and Lich](forge-interop.md).

## Resolution and load order

Lich checks every discovered mod and collects **all** problems into one message, so you fix them in one pass rather
than one launch each. It checks, per mod: Minecraft version, `forge` requirement, `javaVersion`, raw transformers,
`provides` conflicts, and each `depends` entry. Then it orders the mods: **a mod after the mods it depends on,
otherwise by id.** A dependency cycle is an error ("Mods depend on each other in a circle: a → b → a").

Load order decides the order of entrypoints within each kind, the order of Mixin configs, and the order of
`Lich.mods()`. Listeners on events run by [priority](events-and-hooks.md#priorities-and-cancelling), then in
registration order, so mod order also decides ties between mods' listeners.

When any mod fails to resolve, the launch stops before the game window opens, with every problem logged as fatal.

## Reading it back at run time

```java
Lich.version();            // "0.3.1", or "dev" for a development build
Lich.minecraftVersion();   // "1.7.10" or "1.8.9"
Lich.isForgePresent();
Lich.mods();               // List<ModInfo>, in load order
Lich.mod("othermod");      // Optional<ModInfo>
Lich.isModLoaded("othermod");
```

`ModInfo` is a record of `id`, `version`, `name`, `description` and `authors` from the descriptor.
