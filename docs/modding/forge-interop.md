# Forge and Lich

Lich runs the game in two profiles, and a Lich mod must work in both:

| Profile | What runs |
|---|---|
| plain (`lich`) | Vanilla Minecraft on Java 25. Lich remaps the game from its obfuscated names to SRG itself |
| Forge (`lich-forge`) | Real Forge (11.15.1.2318 for 1.8.9, 10.13.4.1614 for 1.7.10), patched at load time, and Forge mods |

In both, the game runs in **SRG names**, and your mod is reobfuscated to SRG, so **one jar works in both**. You do not
build twice.

## Writing code that runs in both

The `dev.lich.mod` plugin compiles against **vanilla Minecraft only**: no Forge classes on the classpath. Anything
that compiles is something vanilla has, so it exists with and without Forge. That is deliberate, and it is the
simplest way to stay profile-independent.

The events of the API behave the same in both profiles. Lich's hooks for HUD, tooltips and a few others have a
vanilla implementation and a Forge one internally (Forge replaces some of the same classes), so you get the same
events either way; where Forge's own events already cover the code, Lich's fire after Forge mods have had their turn
(noted in [Events and hooks](events-and-hooks.md)).

### Asking whether Forge is there

```java
if (Lich.isForgePresent()) {
    // Forge is running
}
```

Declare your stance in the descriptor:

| `depends.forge` | Meaning |
|---|---|
| `"optional"` (default) | Works with and without Forge. Use Forge's API only behind `Lich.isForgePresent()` |
| `"required"` | Refused without Forge: "needs Forge" |
| `"incompatible"` | Refused with Forge: "doesn't work with Forge" |

If you do use Forge classes behind that check, remember that:

- the plugin does not give you Forge to compile against. You would add your own compile-only dependency on Forge,
  which Lich does not set up or test; reflection works with no extra dependency;
- the class that mentions Forge types must not be loaded when Forge is absent. Keep it separate, and only touch it
  after the check. A shared class that mentions a Forge type in a field, parameter or signature can fail to link;
- Forge's mod loading is not involved. Your classes are not an `@Mod`; they never get Forge's lifecycle events (see
  below).

## Dual jars

A jar can contain both `lich.mod.json` and Forge's metadata (`mcmod.info`, an `@Mod` class). Under Lich, **the whole
jar is hidden from FML**: Lich claims it, FML never lists it, so its `@Mod` class is **not** loaded by Forge.
Everywhere else (plain Forge on Java 8, or any launcher that does not have Lich) the jar is an ordinary Forge mod, and
Lich's descriptor is ignored.

That lets a mod ship once for people with and without Lich, but it means the two halves must not both be needed at
once: under Lich only the Lich half runs.

## Forge mods and Lich mods side by side

They share the game and the class loader (`LaunchClassLoader`). What to know:

- **Order.** Lich mod entrypoints run from Lich's own lifecycle hook. `init` runs when Forge gives its mods pre-init,
  `client` after Forge mods have initialised ([Lifecycle](lifecycle.md)).
- **Transformers.** Forge's binpatches, FML's transformers and Forge mods' coremods run before Mixin. Your Mixins
  and Lich's hooks run after them, on SRG names.
- **Mixin copies.** Forge mods that ship their own Mixin (0.7.x, UniMixins) use their own copy. Yours is Fabric's
  Mixin 0.8.7, in Lich's class loader, started only when Lich mods are installed.
- **Hidden jars.** Lich mod jars are hidden from FML, so FML's mod list does not show them. Use `Lich.mods()` for the
  Lich mods that loaded.
- **Overlap.** An aggressive Lich mod (one that replaces a whole renderer) and a Forge mod that patches the same
  methods can clash. This is a known risk (DOCS.md §9); prefer the events, and keep Mixins narrow.

## The vanilla profile needs MCP data

In the plain profile Lich maps the game from obfuscated to SRG names with MCP data it downloads at install time. If
that data is missing (a very old install) and Lich mods are installed, the game stops with:

```
Lich mods are installed, but this launch has no MCP mappings (mcp-<version>-srg.zip): reinstall Lich
```

Installing with the installer (or a current profile from `:installer:installDevProfile` in development) takes care
of it.

## Relaunching loaders

Some Forge mods relaunch the game (for example Essential's relaunching loader). **Lich mods do not load in a relaunched
game yet**: Lich logs `Lich mods can't load in a relaunched game yet; leaving out [...]` and continues without them.
