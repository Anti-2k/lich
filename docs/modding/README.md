# Writing mods for Lich

This is the guide for people who write **Lich mods**: mods in Lich's own format (`lich.mod.json`) that use
Lich's API, Mixins and access wideners to change the client. Forge mods are a different thing: they load through
Forge as usual and need nothing from this guide (see [Forge and Lich](forge-interop.md) if you want to ship both
in one jar).

Lich itself changes nothing in the game beyond what Java 25 needs. Everything else a player sees (a HUD, a
different fog, a bigger tooltip) is a mod, and these pages are how you write one.

## Read in this order

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | Project setup with the `dev.lich.mod` Gradle plugin, a first mod, building it, running it |
| [`lich.mod.json` reference](lich-mod-json.md) | Every field of the descriptor, version ranges, dependency rules, discovery |
| [Lifecycle and entrypoints](lifecycle.md) | `preLaunch`, `init`, `client`, shutdown: when each runs and what is safe in it |
| [Events and hooks](events-and-hooks.md) | The event bus, hook ids, and every event Lich fires, with per-version differences |
| [Mixins](mixins.md) | Mixin 0.8 and MixinExtras on SRG names: configs, refmaps, targeting, pitfalls |
| [Access wideners](access-wideners.md) | Opening up private and final members, the file format, how it is applied |
| [Several Minecraft versions in one jar](multi-version.md) | `minecraft("1.7.10", "1.8.9")`, source sets, the byte-for-byte check |
| [Forge and Lich](forge-interop.md) | Running with and without Forge, dual jars, class loading |
| [Server safety](server-safety.md) | What a mod may change without getting a player banned |
| [Troubleshooting](troubleshooting.md) | Loader errors, what they mean, and how to read the log |

## The model in one page

- **A Lich mod is a jar with `lich.mod.json` at its root.** Lich finds it in `mods/` (and `mods/<minecraft
  version>/`), checks it against the running game and the other mods, and puts it on the game's class path.
- **Java 25, SRG names.** Lich mods are Java 25 classes. They run against the game in its *SRG* names
  (`func_71410_x`, `field_71443_c`), the names Forge uses at runtime, in both the Forge and the plain profile. You
  write your code with *MCP* names (`Minecraft.getMinecraft()`); the Gradle plugin compiles against MCP names and
  renames the finished jar to SRG. One jar therefore works with and without Forge.
- **Hooks are woven on demand.** The game classes are untouched until a mod asks for a hook. Each hook has an id
  such as `render.hud`; you list the ids you use under `"hooks"` in `lich.mod.json`, and Lich weaves exactly the
  union of what the loaded mods list. With no Lich mods installed, no hook is woven and the game is byte for byte
  what vanilla or Forge would run on Java 25.
- **Three layers, from safe to deep:**
  1. *Events* ([`dev.lich.api`](events-and-hooks.md)): typed events on a small bus. Use these first.
  2. *Mixins* ([Mixins](mixins.md)): Mixin 0.8.7 plus MixinExtras, for anything the API does not expose.
  3. *Access wideners* ([Access wideners](access-wideners.md)): make a private field or a final class reachable from
     your own code.
- **Client only.** Lich mods run on the client (and the integrated server that ships in it). There is no dedicated
  server side.

## What works today, and what does not

Stated plainly so nobody builds on something that is not there.

| Area | State |
|---|---|
| Entrypoints (`preLaunch`, `init`, `client`), shutdown event | Works, 1.7.10 and 1.8.9, with and without Forge |
| Event hooks (tick, frame, HUD, world, entity, fog, FOV, tooltips, screens, mouse, chat, sound, attack) | Works. List in [Events and hooks](events-and-hooks.md) |
| Key bindings (`KeyBindings.register`) | Works |
| Mixins with refmaps, MixinExtras | Works |
| Access wideners | Works |
| Several Minecraft versions in one jar | Works |
| Dependencies between Lich mods, ranges, load order | Works. Dependencies are all required: there is no optional mod dependency |
| `provides` (replaceable subsystems) | **Reserved.** The field is parsed and two mods claiming the same name are refused, but no subsystem exists yet to replace |
| `transformers` (raw ASM) | **Not supported.** A mod that declares any is refused with an error |
| Config API, HUD layer registry, client commands | Not there yet. Mods bring their own for now |
| Packet send/modify API | Not there yet. Network access is whatever the game's own classes give you |
| Lich mods in a *relaunched* game (for example under Essential's relaunching loader) | Not supported yet; the mods are left out with a warning |
| Mods built against Forge's API | Not what this plugin does: it compiles against vanilla Minecraft only. See [Forge and Lich](forge-interop.md) |
| Published Maven artifacts for the API and the Gradle plugin | **Not published yet.** For now a mod is a module in a checkout of the Lich repository. See [Getting started](getting-started.md#where-your-mod-lives) |

## Versions and names used in these pages

| Thing | 1.7.10 | 1.8.9 |
|---|---|---|
| Forge Lich supports | 10.13.4.1614 | 11.15.1.2318 |
| Names you write against (MCP) | Forge 1.7.10's userdev names (`conf/fields.csv`, `methods.csv`) | MCP `stable_22` |
| Mixin | Fabric's Mixin 0.8.7 (`0.17.3+mixin.0.8.7`), MixinExtras 0.5.0 | same |
| Java | 25 | 25 |

The MCP names differ between the two versions in places (for example `fontRenderer` on 1.7.10 and
`fontRendererObj` on 1.8.9). Code you want on both versions needs the [multi-version setup](multi-version.md).

## Where the design lives

[DOCS.md](https://github.com/Anti-2k/lich/blob/main/DOCS.md) is the source of truth for decisions: §3 (architecture), §3.1 (the SRG runtime
namespace), §5 (the mod format and the hook rules) and §6 (public-server safety). These pages describe how to use
what DOCS.md decides; if the two ever disagree, DOCS.md and the code win, and these pages have a bug.

The examples in the repository are real, built, and exercised by the project:

- `test/testmod`: a 1.8.9 mod that touches every part of the loader (entrypoints, hooks, a Mixin, a widener).
- `test/multimod`: one jar for 1.7.10 and 1.8.9.
