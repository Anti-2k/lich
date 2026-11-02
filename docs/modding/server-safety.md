# Server safety

Lich's core is built so that a player on a public server looks like a normal vanilla or Forge client: no extra packets,
no changed movement, no changed timing. **Lich mods are not held to that by the loader.** The API gives you a lot of
control, and what a mod does with it is up to the author and to the server's own rules. This page is the line the
project itself draws, and how to stay on the right side of it.

(The full rationale is [DOCS.md §6](https://github.com/Anti-2k/lich/blob/main/DOCS.md#6-public-server-safety-hard-requirement).)

## The rule

> The client's **server-observable behavior** must not change.

"Server-observable" means anything that reaches the server, or that the server can infer from what reaches it.

### Never touched by Lich's core

Lich itself never changes any of these, and its own hooks into them are observe-only:

- player movement and physics code;
- the 20 TPS client tick timer;
- attack, dig and use timing (`PlayerControllerMP`);
- packet send order, rate and content;
- rotation math;
- `MathHelper`. Its sine table *is* part of the physics, so a "fast math" replacement changes movement and can trip
  prediction-based anticheats;
- keep-alive handling and entity interaction.

### Allowed, because it never leaves the client

- rendering and GUI;
- audio;
- resource loading;
- client-only caches;
- threading of client-only work (for example chunk meshing);
- memory use and garbage collection.

## What that means for your mod

The API follows the rule, which tells you what is safe:

| API | Safe because |
|---|---|
| Render, HUD, fog, FOV, tooltip, screen-button events | Only change what this client draws |
| `ChatEvents.RECEIVE` | Changes only what this client *shows*, not what the server sent or receives |
| `SoundEvents.PLAY` | Local audio |
| `PlayerEvents.ATTACK` | **Observe only.** You cannot change or stop the attack; it has already been sent |
| `ClientTickEvents` | The game's tick. Reading is fine. Anything you do *inside* it that changes movement, packets or timing is yours to answer for |
| `InputEvents.MOUSE`, `ScreenEvents` input | You can cancel input. Cancelling a click is like not clicking; synthesising clicks or key presses is automation |

There is no packet send/modify API yet. When there is one, it will be separate and explicitly labelled, so authors know
they are touching server-observable behavior.

## Things you can do with Mixins that you should not do lightly

Because a Mixin can change anything, the following changes what a server sees and is on you:

- anything that sends packets the vanilla client would not (or not at that time or rate);
- changing movement, velocity, collision or rotation of the player;
- changing attack range, reach, or when attacks, digs and uses happen;
- auto-clicking, auto-aiming, or other automation;
- replacing `MathHelper`, or anything else that feeds physics.

Servers have their own rules about client mods, and they apply to Lich mods like any other mod. Lich does not
disguise a mod: its client brand stays whatever the underlying game sends (`vanilla`, or `fml,forge`), Lich registers no
plugin channels, and it adds no packets.

## Checklist

- Does my mod change a packet, a timing, or a physics value? If yes, it is not a cosmetic mod; say so in its
  description.
- Does a listener cancel input or anything the player does? Does it still behave if the player pressed the key?
- Would a server's anticheat see anything different with my mod loaded than without? If you are not sure, assume yes.
- Prefer events to Mixins: the events are observe-only exactly where the rule requires.
